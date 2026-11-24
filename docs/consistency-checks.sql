-- 컷라인 정합성 체크. 결과가 한 줄이라도 나오면 위반이다.
-- 트래픽이 멈춘 뒤 실행한다 (결제 진행 중에는 환불 대기가 잠깐 보일 수 있다).
-- 원본: backend/src/main/java/com/cutline/consistency/ConsistencyChecker.java
-- 애플리케이션에서는 GET /internal/consistency 로도 실행할 수 있다.

-- [stock_conservation] 드롭별 총수량 = 남은 재고 + 점유 중·결제된 주문 수량 (초과판매·재고 누수 없음)
SELECT CONCAT('drop ', d.id, ': total=', d.total_qty, ' remaining=', d.remaining_qty,
              ' held_or_sold=', COALESCE(SUM(o.qty), 0))
FROM drop_event d
LEFT JOIN orders o ON o.drop_id = d.id AND o.status IN ('PENDING_PAYMENT', 'PAID')
GROUP BY d.id, d.total_qty, d.remaining_qty
HAVING d.total_qty <> d.remaining_qty + COALESCE(SUM(o.qty), 0);

-- [quota_matches_orders] 회원별 사용 한도 = 그 회원의 점유 중·결제된 주문 수량
SELECT CONCAT('drop ', q.drop_id, ' member ', q.member_id, ': used=', q.used_qty,
              ' orders=', COALESCE(SUM(o.qty), 0))
FROM member_drop_quota q
LEFT JOIN orders o ON o.drop_id = q.drop_id AND o.member_id = q.member_id
                  AND o.status IN ('PENDING_PAYMENT', 'PAID')
GROUP BY q.drop_id, q.member_id, q.used_qty
HAVING q.used_qty <> COALESCE(SUM(o.qty), 0);

-- [quota_within_limit] 회원별 사용 한도 ≤ 1인 구매 한도
SELECT CONCAT('drop ', q.drop_id, ' member ', q.member_id, ': used=', q.used_qty,
              ' limit=', d.per_member_limit)
FROM member_drop_quota q JOIN drop_event d ON d.id = q.drop_id
WHERE q.used_qty > d.per_member_limit;

-- [paid_has_approved_payment] 결제 완료 주문에는 승인된 결제가 있고 금액이 같다
SELECT CONCAT('order ', o.id, ': payment=', COALESCE(p.status, 'none'),
              ' amount=', o.amount, '/', COALESCE(p.amount, 0))
FROM orders o LEFT JOIN payment p ON p.order_id = o.id
WHERE o.status = 'PAID' AND (p.status IS NULL OR p.status <> 'APPROVED' OR p.amount <> o.amount);

-- [approved_payment_has_paid_order] 승인된 결제는 결제 완료 주문에만 있다 (아니면 환불 누락)
SELECT CONCAT('order ', o.id, ': status=', o.status, ' payment=APPROVED')
FROM payment p JOIN orders o ON o.id = p.order_id
WHERE p.status = 'APPROVED' AND o.status <> 'PAID';

-- [no_unresolved_payment] 결과를 모르는 결제가 남아 있지 않다 (복구 작업이 처리했어야 함)
SELECT CONCAT('order ', p.order_id, ': payment=', p.status)
FROM payment p WHERE p.status IN ('REQUESTED', 'UNKNOWN');
