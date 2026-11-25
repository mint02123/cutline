#!/usr/bin/env bash
# 1단계 기준선 측정을 처음부터 끝까지 재현한다.
#   1) MySQL(docker compose) 데이터베이스 초기화
#   2) 백엔드 jar 기동 (Flyway가 스키마 생성)
#   3) 회원 시드 (측정용 BUYERS명 + 워밍업용 WARMUP명), 워밍업 1회 (기록 안 함)
#   4) InnoDB 잠금 지표 기록 → k6 실행 → 지표 다시 기록
#   5) 정합성 체커 결과와 환경 정보를 결과 폴더에 저장
#
# 사용: BUYERS=3000 STOCK=1000 ./run-baseline.sh [결과폴더이름]
# 비교 실험: APP_ARGS='--cutline.order.sold-out-precheck=false' 처럼 앱 설정을 덧붙인다 (environment.txt에 기록)
# 필요: docker compose, k6, Java 21, ./gradlew bootJar 로 만든 jar
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
NAME=${1:-$(date -u +%Y%m%dT%H%M%SZ)}
OUT="$ROOT/load-test/results/$NAME"
BUYERS=${BUYERS:-3000}
STOCK=${STOCK:-1000}
HIKARI_MAX=${HIKARI_MAX:-10}
JAR=$(ls "$ROOT"/backend/build/libs/cutline-backend-*-SNAPSHOT.jar | grep -v plain | head -1)
mkdir -p "$OUT"

mysql_root() { docker exec -i cutline-mysql mysql -uroot -proot --batch --skip-column-names "$@" 2>/dev/null; }

echo "== reset database"
mysql_root -e "DROP DATABASE IF EXISTS cutline; CREATE DATABASE cutline; GRANT ALL ON cutline.* TO 'cutline'@'%';"
# DB를 새로 만들면 드롭 id가 1부터 다시 시작하므로, 이전 측정의 관문 키(gate:{dropId})도 지운다.
docker exec cutline-redis redis-cli FLUSHALL >/dev/null

echo "== start backend (hikari max=$HIKARI_MAX)"
java -Xms1g -Xmx1g -jar "$JAR" \
  --spring.datasource.hikari.maximum-pool-size="$HIKARI_MAX" \
  --logging.level.root=WARN ${APP_ARGS:-} > "$OUT/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  curl -sf localhost:8080/actuator/health >/dev/null && break
  sleep 1
done

WARMUP=${WARMUP:-500}
SEED=$((BUYERS + WARMUP))
echo "== seed $SEED members ($BUYERS measured + $WARMUP warm-up)"
mysql_root cutline <<SQL
SET SESSION cte_max_recursion_depth = 1000000;
INSERT INTO member (email, password_hash, created_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $SEED)
SELECT CONCAT('buyer', n, '@cutline.test'), 'x', UTC_TIMESTAMP(6) FROM seq;
SQL

lock_metrics() {
  mysql_root -e "SHOW GLOBAL STATUS WHERE Variable_name IN
    ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max','Innodb_deadlocks','Questions','Com_commit');"
}
echo "== warm-up ($WARMUP buyers on a separate drop, not recorded)"
# JIT 컴파일과 커넥션 풀이 데워지기 전의 첫 요청들이 기준선을 왜곡하지 않게 한 번 돌리고 버린다.
k6 run --quiet -e BUYERS="$WARMUP" -e STOCK=$((WARMUP / 3)) -e MEMBER_FROM=$((BUYERS + 1)) \
  -e SUMMARY_OUT="$OUT/warmup-summary.json" drop-rush.js > "$OUT/warmup-output.txt" 2>&1 || true

lock_metrics > "$OUT/mysql-status-before.tsv"
mysql_root -e "TRUNCATE performance_schema.events_statements_summary_by_digest;"

echo "== run k6"
k6 run --quiet -e BUYERS="$BUYERS" -e STOCK="$STOCK" -e SUMMARY_OUT="$OUT/k6-summary.json" \
  drop-rush.js 2>&1 | tee "$OUT/k6-output.txt"

lock_metrics > "$OUT/mysql-status-after.tsv"
# 문장별 실행 횟수, 평균·최대 시간(ms), 잠금 대기 합계(ms). 핫 로우를 쥔 동안 무엇이 실행되는지 본다.
mysql_root -e "
  SELECT COUNT_STAR, ROUND(AVG_TIMER_WAIT/1e9, 2), ROUND(MAX_TIMER_WAIT/1e9, 2), ROUND(SUM_LOCK_TIME/1e9, 1),
         LEFT(REPLACE(DIGEST_TEXT, '\n', ' '), 140)
  FROM performance_schema.events_statements_summary_by_digest
  WHERE SCHEMA_NAME = 'cutline' ORDER BY SUM_TIMER_WAIT DESC LIMIT 12" > "$OUT/statement-digest.tsv"
curl -s localhost:8080/internal/consistency > "$OUT/consistency.json"
# 관문 토큰과 MySQL 남은 재고 (ADR-009: 토큰 <= 남은 재고여야 한다)
LAST_DROP=$(mysql_root cutline -e "SELECT MAX(id) FROM drop_event")
echo "drop_id=$LAST_DROP redis_tokens=$(docker exec cutline-redis redis-cli HGET "gate:{$LAST_DROP}" stock) mysql_remaining=$(mysql_root cutline -e "SELECT remaining_qty FROM drop_event WHERE id = $LAST_DROP")" > "$OUT/gate.txt"
# 오픈 시각부터 마지막 재고가 팔릴 때까지 걸린 시간 (측정 대상 드롭 = 가장 최근 드롭)
mysql_root cutline -e "
  SELECT d.id, d.total_qty, COUNT(o.id),
         TIMESTAMPDIFF(MICROSECOND, d.opens_at, MIN(o.created_at)) DIV 1000,
         TIMESTAMPDIFF(MICROSECOND, d.opens_at, MAX(o.created_at)) DIV 1000
  FROM drop_event d JOIN orders o ON o.drop_id = d.id
  WHERE d.id = (SELECT MAX(id) FROM drop_event)
  GROUP BY d.id, d.total_qty, d.opens_at" \
  | awk '{print "drop_id: "$1"\ntotal_qty: "$2"\norders: "$3"\nfirst_order_after_open_ms: "$4"\nsold_out_after_open_ms: "$5}' \
  > "$OUT/sellout.txt"
cat "$OUT/sellout.txt"

{
  echo "date_utc: $(date -u +%FT%TZ)"
  echo "cpu: $(nproc) x $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
  echo "memory_gb: $(free -g | awk '/Mem:/ {print $2}')"
  echo "os: $(uname -sr)"
  echo "java: $(java -version 2>&1 | grep -m1 version)"
  echo "jvm_heap: 1g"
  echo "k6: $(k6 version | head -1)"
  echo "mysql: $(mysql_root -e 'SELECT @@version')"
  echo "redis: $(docker exec cutline-redis redis-server --version | cut -d' ' -f3)"
  echo "innodb_buffer_pool_mb: $(mysql_root -e 'SELECT @@innodb_buffer_pool_size DIV 1048576')"
  echo "hikari_max_pool: $HIKARI_MAX"
  echo "tomcat_max_threads: 200 (default)"
  echo "buyers: $BUYERS"
  echo "stock: $STOCK"
  echo "app_args: ${APP_ARGS:-(none)}"
  echo "git_commit: $(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet -- backend || echo "-dirty")"
  echo "topology: k6, backend, MySQL, Redis all on one host (docker for MySQL and Redis)"
} > "$OUT/environment.txt"

echo "== results in $OUT"
cat "$OUT/consistency.json"; echo
