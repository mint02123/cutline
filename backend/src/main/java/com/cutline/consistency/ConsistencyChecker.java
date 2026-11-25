package com.cutline.consistency;

import com.cutline.order.GateResync;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 정합성 체커. 테스트, 부하 테스트, 장애 주입이 끝날 때마다 돌려 "돈·재고·주문이 서로 맞는가"를 확인한다.
 * 같은 SQL을 docs/consistency-checks.sql에도 두어 운영 DB에서 직접 돌릴 수 있게 한다.
 *
 * <p>각 규칙은 "위반한 행"을 찾는 SELECT라, 결과가 비어 있어야 정상이다.
 * 결제가 진행 중인 순간에는 일시적으로 어긋날 수 있는 규칙(환불 대기)이 있으므로, 트래픽이 멈춘 뒤에 돌린다.
 */
@Component
public class ConsistencyChecker {

    public record Rule(String name, String description, String sql) {
    }

    public record Violation(String rule, String detail) {
    }

    public static final List<Rule> RULES = List.of(
            new Rule("stock_conservation",
                    "드롭별 총수량 = 남은 재고 + 점유 중·결제된 주문 수량 (초과판매·재고 누수 없음)",
                    """
                    SELECT CONCAT('drop ', d.id, ': total=', d.total_qty, ' remaining=', d.remaining_qty,
                                  ' held_or_sold=', COALESCE(SUM(o.qty), 0))
                    FROM drop_event d
                    LEFT JOIN orders o ON o.drop_id = d.id AND o.status IN ('PENDING_PAYMENT', 'PAID')
                    GROUP BY d.id, d.total_qty, d.remaining_qty
                    HAVING d.total_qty <> d.remaining_qty + COALESCE(SUM(o.qty), 0)"""),
            new Rule("quota_matches_orders",
                    "회원별 사용 한도 = 그 회원의 점유 중·결제된 주문 수량",
                    """
                    SELECT CONCAT('drop ', q.drop_id, ' member ', q.member_id, ': used=', q.used_qty,
                                  ' orders=', COALESCE(SUM(o.qty), 0))
                    FROM member_drop_quota q
                    LEFT JOIN orders o ON o.drop_id = q.drop_id AND o.member_id = q.member_id
                                      AND o.status IN ('PENDING_PAYMENT', 'PAID')
                    GROUP BY q.drop_id, q.member_id, q.used_qty
                    HAVING q.used_qty <> COALESCE(SUM(o.qty), 0)"""),
            new Rule("quota_within_limit",
                    "회원별 사용 한도 ≤ 1인 구매 한도",
                    """
                    SELECT CONCAT('drop ', q.drop_id, ' member ', q.member_id, ': used=', q.used_qty,
                                  ' limit=', d.per_member_limit)
                    FROM member_drop_quota q JOIN drop_event d ON d.id = q.drop_id
                    WHERE q.used_qty > d.per_member_limit"""),
            new Rule("paid_has_approved_payment",
                    "결제 완료 주문에는 승인된 결제가 있고 금액이 같다",
                    """
                    SELECT CONCAT('order ', o.id, ': payment=', COALESCE(p.status, 'none'),
                                  ' amount=', o.amount, '/', COALESCE(p.amount, 0))
                    FROM orders o LEFT JOIN payment p ON p.order_id = o.id
                    WHERE o.status = 'PAID' AND (p.status IS NULL OR p.status <> 'APPROVED' OR p.amount <> o.amount)"""),
            new Rule("approved_payment_has_paid_order",
                    "승인된 결제는 결제 완료 주문에만 있다 (아니면 환불 누락)",
                    """
                    SELECT CONCAT('order ', o.id, ': status=', o.status, ' payment=APPROVED')
                    FROM payment p JOIN orders o ON o.id = p.order_id
                    WHERE p.status = 'APPROVED' AND o.status <> 'PAID'"""),
            new Rule("no_unresolved_payment",
                    "결과를 모르는 결제가 남아 있지 않다 (복구 작업이 처리했어야 함)",
                    """
                    SELECT CONCAT('order ', p.order_id, ': payment=', p.status)
                    FROM payment p WHERE p.status IN ('REQUESTED', 'UNKNOWN')"""));

    /**
     * MySQL만 보는 규칙과 달리 Redis 관문을 함께 본다 (ADR-009). 토큰이 남은 재고보다 많으면 관문이 MySQL이 팔 수 있는 것보다
     * 많이 들여보낸다는 뜻이다. MySQL 조건부 UPDATE가 초과판매는 막지만, 설계의 불변 조건이 깨진 것이다.
     * 반대로 토큰이 적은 것은 진행 중인 주문이 있으면 정상이라 위반으로 보지 않는다(재동기화가 맞춘다).
     */
    public static final Rule GATE_RULE = new Rule("gate_tokens_within_stock",
            "Redis 관문 토큰 <= MySQL 남은 재고 (판매 예정·판매 중인 드롭)",
            "redis-cli HGET gate:{<dropId>} stock 와 drop_event.remaining_qty 비교");

    public static final int RULE_COUNT = RULES.size() + 1;

    private final JdbcTemplate jdbc;
    private final GateResync gate;

    ConsistencyChecker(JdbcTemplate jdbc, GateResync gate) {
        this.jdbc = jdbc;
        this.gate = gate;
    }

    public List<Violation> check() {
        List<Violation> violations = new ArrayList<>();
        for (Rule rule : RULES) {
            for (String detail : jdbc.queryForList(rule.sql(), String.class)) {
                violations.add(new Violation(rule.name(), detail));
            }
        }
        try {
            gate.tokensAboveStock().forEach(detail -> violations.add(new Violation(GATE_RULE.name(), detail)));
        } catch (DataAccessException e) {
            violations.add(new Violation(GATE_RULE.name(), "Redis 확인 불가: " + e.getMessage()));
        }
        return violations;
    }
}
