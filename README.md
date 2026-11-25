# 컷라인 (CUTLINE)

한정판 스니커즈·피규어 드롭 커머스 백엔드입니다. 오픈 순간 몰리는 주문 속에서도
**초과판매 0건, 중복결제 0건**을 지키고, 장애 상황에서도 그 약속이 유지된다는 것을 측정으로 증명하는 것이 목표입니다.

## 이 프로젝트가 증명하려는 것
1. **장애 중 정합성**: 서버가 죽거나, Redis가 넘어가거나, 결제 응답이 늦어도 초과판매·중복결제 0건
2. **수평 확장**: 서버를 늘릴 때 처리량이 얼마나 늘어나는지 곡선으로 제시
3. **운영 자동화**: 나쁜 배포는 자동 롤백, 인프라는 코드로 재생성

모든 수치는 `load-test/k6/`의 스크립트와 측정 환경을 함께 공개합니다.

## 현재 단계
| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 도메인 코어 (MySQL만으로 정합성 증명, 기준선 측정) | **완료** |
| 2 | 대기열과 성능 개선 (Redis) | 진행 중 |
| 3 | 결제 안정성과 정산 (PG, Kafka, Spring Batch) | 예정 |
| 4 | 프론트엔드 (Next.js) | 예정 |
| 5 | 클라우드 인프라 (Terraform, EKS, ArgoCD, 관측) | 예정 |
| 6 | 수평 확장과 장애 주입 | 예정 |
| 7 | 배포 자동화와 재해 복구 | 예정 |

## 1단계 결과 한눈에 보기
MySQL 하나만으로 드롭 오픈을 받아 낸 기준선입니다. 자세한 분석은 [1단계 성능 기준선](docs/reports/phase1-baseline.md)에 있습니다.

| 구매자 (재고 1,000개) | 매진까지 | 처리량 | 주문 성공 p95 | 품절 응답 p95 | 5xx | 초과판매·중복결제 |
| --- | --- | --- | --- | --- | --- | --- |
| 1,000 | 5.66초 | 177건/초 | 4.35초 | - | 0 | 0 |
| 3,000 | 5.19초 | 193건/초 | 4.93초 | 7.26초 | 0 | 0 |
| 5,000 | 4.82초 | 208건/초 | 4.82초 | 9.17초 | 0 | 0 |

- **정합성은 완벽했습니다.** 모든 실행에서 주문은 정확히 1,000건이었고, 끝난 뒤 정합성 체커 6개 규칙이 모두 통과했습니다.
- **속도는 재고 행 하나가 정했습니다.** 주문 한 건이 `drop_event` 행을 약 5ms 쥐므로 초당 200건 안팎이 상한입니다.
  커넥션 풀을 10→50으로 늘려도, fsync를 꺼도 매진 시간은 그대로였고 행 잠금 대기만 52초→456초로 늘었습니다.
- **품절된 사람도 같은 줄에 섭니다.** 조건이 거짓인 UPDATE도 행을 잠그기 때문에 품절 응답이 성공 응답보다 느립니다.
  2단계의 첫 개선은 이 줄을 잠금 없이 걸러 내는 것입니다.

측정 환경(4 vCPU 한 대에 k6·앱·MySQL 동거)과 원자료는 `load-test/results/`에 함께 있습니다. 이 수치는 같은 환경에서 전후를 비교하는 용도입니다.

## 핵심 설계 원칙
- **최종 판단은 MySQL** ([ADR-001](docs/adr/0001-mysql-is-the-source-of-truth.md)): 재고·한도·주문 상태는 조건부 UPDATE 한 번으로 바꾸고, 영향 행이 0이면 실패로 본다.
- **DB 제약이 마지막 방어선**: `CHECK (remaining_qty >= 0)`, 유니크 키로 코드 버그도 막는다.
- **잠금 순서를 하나로 고정**: 주문은 재고 행부터, 결제는 주문 행부터 잠그고, 없는 행은 `FOR UPDATE`하지 않는다. 데드락 두 건을 재현하고 고친 기록이 트러블슈팅 002, 003이다.
- **결제 응답 유실 대비** ([ADR-006](docs/adr/0006-unknown-payment-and-reconcile.md)): PG 응답이 없으면 UNKNOWN으로 두고 조회로 확정하며, 그동안 주문을 만료시키지 않는다. 이미 끝난 주문에 승인이 오면 환불로 보상한다.
- **정합성을 코드로 검사**: [`docs/consistency-checks.sql`](docs/consistency-checks.sql)의 6개 규칙을 동시성 테스트와 부하 테스트가 끝날 때마다 돌린다.

## API
| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/sellers/{sellerId}/products` | 상품 등록 |
| POST | `/api/sellers/{sellerId}/drops` | 드롭 생성 (오픈 시각, 재고, 1인 한도, 결제 대기 시간) |
| GET | `/api/drops/{dropId}` | 드롭 조회 (남은 재고, 상태) |
| POST | `/api/drops/{dropId}/orders` | 주문 (`Idempotency-Key` 헤더 필수, 재고와 한도를 결제 대기 시간 동안 점유) |
| GET | `/api/orders/{orderId}` | 주문 조회 |
| POST | `/api/orders/{orderId}/payments` | 결제 확정 (200 승인, 202 결과 확인 중) |
| POST | `/api/orders/{orderId}/cancel` | 취소 (결제 전 취소, 결제 후 환불) |
| GET | `/api/me/orders?cursor=&size=` | 내 주문 목록 (커서 페이지) |
| GET | `/internal/consistency` | 정합성 체커 실행 결과 |

오류 코드는 [`docs/errors.md`](docs/errors.md)에 정리했습니다.

## 로컬 실행
```bash
docker compose up -d          # MySQL 8.4
cd backend
./gradlew bootRun             # http://localhost:8080/actuator/health
./gradlew test                # Testcontainers로 실제 MySQL에서 테스트 (Docker 필요)
```

부하 테스트 (k6 필요, 자세한 내용은 [`load-test/k6/README.md`](load-test/k6/README.md)):
```bash
cd backend && ./gradlew bootJar && cd ../load-test/k6
BUYERS=3000 STOCK=1000 ./run-baseline.sh   # DB 초기화 → 앱 기동 → 시드 → 워밍업 → 측정 → 정합성 확인
```

## 기술 스택
Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL 8.4 · Flyway · JUnit 5 · Testcontainers · k6 · GitHub Actions

## 문서 지도
**설계 결정 (ADR)**: 고려한 대안, 결정, 포기한 것
- [001 최종 판단은 MySQL이 한다](docs/adr/0001-mysql-is-the-source-of-truth.md)
- [002 재고 차감은 조건부 UPDATE 한 번으로](docs/adr/0002-conditional-update-for-stock.md)
- [003 1인 구매 한도는 별도 테이블의 조건부 upsert로](docs/adr/0003-purchase-limit-upsert.md)
- [004 멱등 키는 주문과 같은 DB 트랜잭션에 저장](docs/adr/0004-idempotency-key-in-db.md)
- [005 점유 만료는 스케줄러 + 조건부 상태 전이로](docs/adr/0005-hold-expiry-scheduler.md)
- [006 결제 응답 유실은 UNKNOWN 상태와 PG 조회로 확정](docs/adr/0006-unknown-payment-and-reconcile.md)
- [007 도메인별 패키지 모놀리스로 시작](docs/adr/0007-modular-monolith.md)
- [008 품절 선판정은 잠금 없는 읽기로](docs/adr/0008-sold-out-precheck.md)

**트러블슈팅**: 재현 → 원인 → 해결 → 검증
- [001 MySQL CHECK 제약 위반이 DataIntegrityViolationException으로 잡히지 않음](docs/troubleshooting/001-mysql-check-constraint-not-translated.md)
- [002 1,000명 동시 주문에서 82%가 데드락으로 실패 (FK 공유 잠금)](docs/troubleshooting/002-fk-shared-lock-deadlock-on-hot-row.md)
- [003 결제 동시 요청에서 INSERT끼리 데드락 (갭 잠금)](docs/troubleshooting/003-gap-lock-deadlock-on-payment-insert.md)

**보고서**
- [1단계 성능 기준선](docs/reports/phase1-baseline.md)
- [2단계 2-1: 품절 선판정 측정](docs/reports/phase2-1-sold-out-precheck.md)
