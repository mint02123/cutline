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
| 1 | 도메인 코어 (MySQL만으로 정합성 증명, 기준선 측정) | 진행 중 |
| 2 | 대기열과 성능 개선 (Redis) | 예정 |
| 3 | 결제 안정성과 정산 (PG, Kafka, Spring Batch) | 예정 |
| 4 | 프론트엔드 (Next.js) | 예정 |
| 5 | 클라우드 인프라 (Terraform, EKS, ArgoCD, 관측) | 예정 |
| 6 | 수평 확장과 장애 주입 | 예정 |
| 7 | 배포 자동화와 재해 복구 | 예정 |

## 핵심 설계 원칙
- **최종 판단은 MySQL**: 재고·한도·주문 상태는 조건부 UPDATE 한 번으로 바꾸고, 영향 행이 0이면 실패로 본다.
- **DB 제약이 마지막 방어선**: `CHECK (remaining_qty >= 0)` 같은 제약으로 코드 버그도 막는다.
- **결제 응답 유실 대비**: PG 응답이 없으면 UNKNOWN으로 두고 조회로 확정하며, 그동안 주문을 만료시키지 않는다.

## 로컬 실행
```bash
docker compose up -d          # MySQL 8.4
cd backend
./gradlew bootRun             # http://localhost:8080/actuator/health
./gradlew test                # Testcontainers로 실제 MySQL에서 테스트 (Docker 필요)
```

## 기술 스택
Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL 8.4 · Flyway · JUnit 5 · Testcontainers · GitHub Actions

## 문서
- `docs/adr/`: 설계 결정 기록
- `docs/troubleshooting/`: 문제 재현 → 원인 → 해결 → 검증 기록
- `docs/reports/`: 부하 테스트·장애 주입·운영 보고서
