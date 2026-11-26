# k6 부하 테스트

## 드롭 오픈 기준선 (`drop-rush.js`)
구매자 N명이 오픈 순간에 몰려 주문하고 일부가 결제하는 상황을 재현한다. 끝에 정합성 체커를 돌린다.

```bash
docker compose up -d mysql                 # 저장소 루트에서
(cd backend && ./gradlew bootJar)
cd load-test/k6
BUYERS=3000 STOCK=1000 ./run-baseline.sh b3000-s1000-pool10
```

`run-baseline.sh`는 DB 초기화 → 앱 기동 → 회원 시드 → 워밍업 → 측정 → 정합성 확인 → 환경 기록을 한 번에 한다.
결과는 `load-test/results/<이름>/`에 남는다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `BUYERS` | 3000 | 구매자 수 (가상 사용자 수) |
| `STOCK` | 1000 | 드롭 재고 |
| `HIKARI_MAX` | 10 | 앱 커넥션 풀 크기 |
| `WARMUP` | 500 | 워밍업 구매자 수 (기록 안 함) |
| `APP_ARGS` | 없음 | 앱에 덧붙일 설정 (예: `--cutline.order.sold-out-precheck=false`). `environment.txt`에 기록된다 |
| `REDIS_PAUSE_AT_MS` | 없음 | 정하면 오픈 뒤 이 시각에 Redis 컨테이너를 일시 정지한다 (`docker pause`, ADR-010). `redis-pause.txt`에 실제 정지 시각과 관문 건너뛰기 횟수를 남긴다 |
| `REDIS_PAUSE_MS` | 3000 | Redis 일시 정지 시간 |
| `PAY_RATIO` | 0.8 | 주문 성공 후 결제 비율 (k6 `-e`로 전달) |
| `ARRIVAL_MS` | 1000 | 오픈 시각 전후 도착 폭 (k6 `-e`로 전달) |

`drop-rush.js`는 대기열을 거치지 않으므로 `run-baseline.sh`가 앱을 `--cutline.queue.enforce-admission=false`로 띄운다
(주문 API가 입장 토큰을 요구하지 않음). 2-1, 2-2 측정과 같은 조건이다.

필요한 것: Docker Compose (MySQL, Redis), k6 v1.x, Java 21. 결과 해석은 `docs/reports/phase1-baseline.md`를 본다.
