# ADR-003: 1인 구매 한도는 별도 테이블의 조건부 upsert로

- 상태: 채택
- 날짜: 2026-11-24

## 맥락
드롭마다 1인 구매 한도가 있다. 한 사람이 다른 멱등 키로 동시에 여러 번 눌러도 한도를 넘으면 안 된다.
취소나 만료가 되면 한도가 돌아와야 한다.

## 고려한 대안
| 대안 | 장점 | 단점 |
| --- | --- | --- |
| 주문 테이블에서 `SUM(qty)` 확인 후 주문 | 테이블 추가 없음 | 확인과 INSERT 사이 경쟁. 막으려면 회원 단위 잠금이 따로 필요 |
| `orders`에 `UNIQUE(drop_id, member_id)` | DB가 바로 막음 | 한도 1일 때만 가능. 취소 후 재구매가 막힘 |
| `INSERT IGNORE` 후 조건부 `UPDATE` | 이해하기 쉬움 | 첫 요청들이 같은 행에 S 잠금을 쥔 뒤 X로 올리려다 데드락 |
| **`member_drop_quota` 테이블 + 한 문장 upsert** | 처음 요청과 이후 요청이 같은 문장. 원자적 | MySQL 전용 문법, 영향 행 수 설정 필요 |

## 결정
```sql
INSERT INTO member_drop_quota (drop_id, member_id, used_qty) VALUES (?, ?, ?) AS incoming
ON DUPLICATE KEY UPDATE used_qty = IF(member_drop_quota.used_qty + incoming.used_qty <= ?,
                                      member_drop_quota.used_qty + incoming.used_qty,
                                      member_drop_quota.used_qty)
```
- 한도를 넘으면 값을 그대로 둔다. 영향 행 0으로 `PURCHASE_LIMIT_EXCEEDED`를 판단한다.
- MySQL은 기본으로 "조건에 맞은 행 수"를 돌려주므로 `useAffectedRows=true`로 "실제로 바뀐 행 수"를 받는다 (`application.yml`).
- 재고 차감(ADR-002) 다음에 실행한다. 이 행에는 FK 검사가 걸려도 이미 `drop_event`의 X 잠금을 쥐고 있어서 데드락이 없다.
- 만료·취소 때는 같은 트랜잭션에서 `used_qty`를 되돌린다 (`HoldRelease`).

## 결과와 포기한 것
- 같은 회원이 다른 키로 50번 동시에 요청해도 1건만 성공 (`OrderConcurrencyTest`). 한도 2일 때 30번 동시 요청도 정확히 2건.
- 정합성 체커의 `quota_matches_orders`(한도 장부 = 점유·결제 주문 수량)와 `quota_within_limit`가 이 테이블을 검증한다.
- 포기한 것: 쓰기가 한 행 늘어난다. 핫 로우 잠금을 쥔 채 실행하므로 주문 한 건의 잠금 시간에 더해진다. 기준선 문장 통계로는 수십~수백 µs 수준이었다.
