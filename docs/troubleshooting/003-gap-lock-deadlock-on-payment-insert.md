# 003. 결제 동시 요청에서 INSERT끼리 데드락 (없는 행에 건 FOR UPDATE의 갭 잠금)

- 날짜: 2026-11-24
- 단계: 1단계 6~7일차 (결제 확정 경합 테스트)

## 증상
서로 다른 주문 200건을 동시에 결제하는 테스트(`PaymentRaceTest`)에서 57건이
`DeadlockLoserDataAccessException`(HTTP 500)으로 실패했다. 확인한 실패는 모두 결제 행 INSERT에서 났다.
주문은 전부 달랐으므로, 같은 행을 두고 다툴 이유가 없어 보였다.

## 당시 코드 (결제 확정 트랜잭션 1)
```sql
SELECT ... FROM orders WHERE id = ? FOR UPDATE;          -- 주문 잠금
SELECT status FROM payment WHERE order_id = ? FOR UPDATE; -- 기존 결제 확인 (보통 없음)
INSERT INTO payment (order_id, ...) VALUES (?, ...);       -- 없으면 생성
```

## 원인
`SHOW ENGINE INNODB STATUS` (행 내용 생략):

```
*** (1) TRANSACTION:
INSERT INTO payment (order_id, ...) VALUES (195, 'pk-195', ...)
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index uk_payment_order of table `test`.`payment` trx id 3040 lock_mode X
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index uk_payment_order of table `test`.`payment` trx id 3040 lock_mode X insert intention waiting

*** (2) TRANSACTION:
INSERT INTO payment (order_id, ...) VALUES (194, 'pk-194', ...)
*** (2) HOLDS THE LOCK(S):
RECORD LOCKS ... index uk_payment_order of table `test`.`payment` trx id 3041 lock_mode X
*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index uk_payment_order of table `test`.`payment` trx id 3041 lock_mode X insert intention waiting
*** WE ROLL BACK TRANSACTION (2)
```

`lock_mode X`(레코드 잠금이 아닌 next-key 잠금)는 두 번째 SELECT에서 왔다.
REPEATABLE READ에서 **없는 행**을 `FOR UPDATE`로 찾으면 InnoDB는 그 값이 들어갈 자리(갭)를 잠근다.
결제가 아직 하나도 없거나 order_id 194, 195처럼 이웃한 값이면 모두 **같은 갭**을 잠근다.
갭 잠금끼리는 서로 막지 않아서 여러 트랜잭션이 동시에 쥔다.
그다음 각자 그 갭에 INSERT하려면 insert intention 잠금이 필요한데, 이것은 다른 트랜잭션의 갭 잠금과 충돌한다.
서로가 쥔 갭 잠금을 기다리는 순환이 생긴다.

## 해결
두 번째 SELECT에서 `FOR UPDATE`를 뺐다. 이미 주문 행을 `FOR UPDATE`로 잠갔기 때문에 충분하다.

- 결제 행은 이 트랜잭션에서만, 주문 행 잠금을 쥔 채로 만들어진다. 그래서 잠금 없는 읽기로도 "결제가 있는지"는 정확하다.
- 결제 상태(FAILED인지 등)는 다른 트랜잭션이 바꿀 수 있으므로, 재시도 전이는 `WHERE status = 'FAILED'` 조건부 UPDATE로 하고 영향 행 수를 확인한다.

## 검증
같은 테스트를 5번 실행해 모두 통과했다. 데드락 0건, 500 응답 0건이었다.
돈, 재고, 주문 상태 불변식도 모두 지켜졌다.

## 배운 점
- 002는 FK가 거는 **공유 잠금**, 003은 없는 행을 찾는 **갭 잠금**이 원인이었다. 둘 다 코드에 잠금이 드러나지 않는다.
- "있으면 수정, 없으면 생성"을 `SELECT ... FOR UPDATE` + INSERT로 짜는 것은 동시성에서 위험한 패턴이다.
  더 바깥의 행(여기서는 주문)을 잠가 직렬화하거나, `INSERT ... ON DUPLICATE KEY UPDATE` 한 문장으로 바꾼다.

## 덧붙임: 경합 테스트가 보상 경로를 얼마나 밟는가
같은 테스트에서 "PG 승인 도중 주문이 만료되어 승인을 취소"하는 보상 경로는 5번 중 2번만 나왔다(각 3건).
원인을 계측해 보니 이렇다. 만료 시각이 되는 순간, 아직 시작하지 않은 결제 약 140건이 곧바로 `HOLD_EXPIRED`로 끝난다.
이 요청들이 커넥션 풀(기본 10개)을 차지하는 동안 만료 스케줄러가 주문 하나에 약 5ms씩 쓰고,
그 사이 PG 호출 중이던 주문 대부분이 먼저 결제를 확정했다.
이 경로는 `PaymentConfirmTest`가 PG 승인 직전에 만료를 끼워 넣어 매번 재현한다.
보상 취소가 실패한 뒤 복구 작업이 다시 취소하는 경로도 같은 테스트에서 매번 확인한다.
