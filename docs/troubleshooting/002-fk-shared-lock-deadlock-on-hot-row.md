# 002. 1,000명 동시 주문에서 82%가 데드락으로 실패 (FK 공유 잠금)

- 날짜: 2026-11-24
- 단계: 1단계 3~4일차 (주문 동시성 테스트)

## 증상
재고 100개, 1인 한도 1개인 드롭에 서로 다른 회원 1,000명이 동시에 주문하는 테스트(`OrderConcurrencyTest`, 스레드 32개).
정합성은 지켜졌다. 주문은 정확히 100건, 남은 재고는 0이었다.
그런데 900명이 받아야 할 `SOLD_OUT` 대신 대부분이 `DeadlockLoserDataAccessException`(HTTP 500)을 받았다.

| 실행 | 성공 | SOLD_OUT | 데드락 |
| --- | --- | --- | --- |
| 1 | 100 | 65 | 835 |
| 2 | 100 | 80 | 820 |
| 3 | 100 | 82 | 818 |

같은 회원이 한도 2개 드롭에 동시에 요청하는 테스트에서도 같은 데드락이 났다.

## 당시 코드의 순서
한 트랜잭션 안에서 다음 순서로 실행했다. "모두가 다투는 `drop_event` 행은 가장 늦게, 가장 짧게 잠근다"는 의도였다.

1. `idempotency_key` INSERT
2. `member_drop_quota` upsert (1인 한도)
3. `UPDATE drop_event SET remaining_qty = remaining_qty - ? WHERE ... AND remaining_qty >= ?`
4. `orders` INSERT

## 원인
`SHOW ENGINE INNODB STATUS`의 데드락 기록 (행 내용 생략):

```
*** (1) TRANSACTION:
UPDATE drop_event SET remaining_qty = remaining_qty - 1 WHERE id = 1 AND status = 'OPEN' ...
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `test`.`drop_event` trx id 4704 lock mode S locks rec but not gap
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index PRIMARY of table `test`.`drop_event` trx id 4704 lock_mode X locks rec but not gap waiting

*** (2) TRANSACTION:
UPDATE drop_event SET remaining_qty = remaining_qty - 1 WHERE id = 1 AND status = 'OPEN' ...
*** (2) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `test`.`drop_event` trx id 4703 lock mode S locks rec but not gap
*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index PRIMARY of table `test`.`drop_event` trx id 4703 lock_mode X locks rec but not gap waiting
*** WE ROLL BACK TRANSACTION (2)
```

두 트랜잭션 모두 `drop_event` id=1 행에 S 잠금을 들고, 같은 행의 X 잠금을 기다린다.
S 잠금은 2단계 upsert에서 왔다. `member_drop_quota.drop_id`는 `drop_event(id)`를 참조하는 FK다.
InnoDB는 자식 행을 INSERT할 때 부모 행이 사라지지 않도록 부모 행에 **공유(S) 잠금**을 걸고 커밋까지 유지한다.
S 잠금끼리는 충돌하지 않으므로 수십 개 트랜잭션이 동시에 S를 쥔다.
그다음 3단계 UPDATE가 X로 올리려 하면, 서로가 쥔 S 때문에 모두 기다리는 순환이 생긴다.

"핫 로우 잠금을 늦게 잡자"는 의도가 FK 때문에 오히려 "S를 일찍, X를 늦게" 잡는 최악의 순서가 된 것이다.

## 검토한 해결책

| 방법 | 장점 | 단점 |
| --- | --- | --- |
| 데드락 시 재시도 | 코드 순서 유지 | 82%가 재시도 대상이라 DB 부하가 몇 배가 된다. 원인을 덮을 뿐이다 |
| `member_drop_quota`, `orders`의 FK 제거 | 잠금 자체가 사라짐 | 1단계의 "DB 제약이 마지막 방어선" 원칙을 깬다 |
| **재고 UPDATE를 맨 앞으로** | 처음부터 X를 잡으므로 이후 FK 검사는 이미 가진 잠금으로 통과 | 핫 로우 X 잠금을 한도 upsert와 주문 INSERT 동안 더 오래 쥔다 |

세 번째를 택했다. 단점은 생각보다 작다. FK가 있는 한 다른 트랜잭션의 S 잠금도 X와 충돌하므로,
`drop_event` 행은 원래부터 사실상 한 줄로 직렬화되고 있었다. 잠금 순서만 바로잡은 것이다.
한도 초과 회원이 재고를 잠깐 차감했다가 롤백하는 비용이 생기지만, 같은 트랜잭션 안이라 다른 사람에게 보이지 않는다.

## 해결
`OrderPlacementService.place`의 순서를 바꿨다.

1. `idempotency_key` INSERT
2. `UPDATE drop_event ...` (재고 조건부 차감, 여기서 X 잠금)
3. `member_drop_quota` upsert
4. `orders` INSERT

## 검증
- 같은 테스트를 5번 실행해 모두 통과: 주문 100건, `SOLD_OUT` 900건, 데드락 0건, 남은 재고 0, 한도 합계 100.
- 같은 회원 50개 동시 요청(한도 1), 같은 회원 30개 동시 요청(한도 2), 같은 멱등 키 50개 동시 요청도 모두 통과.

## 배운 점
- 핫 로우를 참조하는 FK가 있으면 "자식 INSERT → 부모 UPDATE" 순서는 S→X 승격 데드락을 만든다. 부모를 먼저 X로 잠근다.
- 데드락이 나도 오버셀은 없었다. 조건부 UPDATE와 CHECK 제약은 정합성을 지켰지만, 사용자 경험(500 응답)은 지키지 못했다.
  정합성 테스트는 "결과가 맞는가"와 함께 "예상한 실패 코드만 나왔는가"도 확인해야 한다.

## 남은 과제
- 9일차 k6 기준선에서 이 순서의 처리량(TPS)과 p99 지연을 측정한다. 핫 로우 직렬화가 병목이면 2단계의 Redis 1차 관문이 이를 줄여야 한다.
- ADR-003(구매 한도 upsert)에 이 잠금 순서 규칙을 함께 적는다.
