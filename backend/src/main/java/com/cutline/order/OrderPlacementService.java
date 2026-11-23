package com.cutline.order;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 생성 + 재고 점유 (설계 문서 "동시성과 정합성 전략").
 *
 * <p>하나의 트랜잭션 안에서 순서대로:
 * <ol>
 *   <li>멱등 키 등록 (이미 처리된 요청이면 저장된 응답 반환)</li>
 *   <li>재고 조건부 차감 (UPDATE 한 번, ADR-002)</li>
 *   <li>1인 구매 한도 증가 (upsert 한 번, ADR-003)</li>
 *   <li>주문 INSERT</li>
 * </ol>
 * 재고 차감이 반드시 먼저다. member_drop_quota와 orders는 drop_event를 FK로 참조하므로, 이 둘을 먼저 INSERT하면
 * FK 검사가 drop_event 행에 S 잠금을 건다. 그 뒤 재고 UPDATE가 X 잠금으로 올리려다 다른 트랜잭션의 S 잠금과
 * 서로 기다리는 데드락이 난다 (트러블슈팅 002: 1,000명 동시 요청 중 약 82%가 데드락으로 실패).
 * drop_event의 X 잠금을 처음에 잡으면 이후 FK 검사는 이미 가진 잠금으로 통과하므로 순환 대기가 생기지 않는다.
 * 실패하면 예외로 전체를 롤백해 재고 차감도 되돌린다.
 */
@Service
public class OrderPlacementService {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public OrderPlacementService(JdbcTemplate jdbc, OrderRepository orders, IdempotencyStore idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public OrderReceipt place(Long memberId, Long dropId, int qty, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new CutlineException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        Instant now = clock.instant();

        var replay = idempotency.begin(memberId, idempotencyKey, hash(dropId, qty), now);
        if (replay.isPresent()) {
            return replay.get();
        }

        DropTerms drop = loadTerms(dropId);
        if (qty > drop.perMemberLimit()) {
            throw new CutlineException(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }

        int stock = jdbc.update("""
                UPDATE drop_event SET remaining_qty = remaining_qty - ?
                WHERE id = ? AND status = 'OPEN' AND opens_at <= ? AND remaining_qty >= ?""",
                qty, dropId, Timestamp.from(now), qty);
        if (stock == 0) {
            throw new CutlineException(drop.isOpenAt(now) ? ErrorCode.SOLD_OUT : ErrorCode.DROP_NOT_OPEN);
        }

        int quota = jdbc.update("""
                INSERT INTO member_drop_quota (drop_id, member_id, used_qty) VALUES (?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE used_qty = IF(member_drop_quota.used_qty + incoming.used_qty <= ?,
                                                      member_drop_quota.used_qty + incoming.used_qty,
                                                      member_drop_quota.used_qty)""",
                dropId, memberId, qty, drop.perMemberLimit());
        if (quota == 0) {
            throw new CutlineException(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }

        Instant holdExpiresAt = now.plus(Duration.ofMinutes(drop.holdMinutes()));
        Order order = orders.save(Order.hold(dropId, memberId, qty, drop.price(), holdExpiresAt, now));

        OrderReceipt receipt = OrderReceipt.of(order);
        idempotency.complete(memberId, idempotencyKey, 201, receipt);
        return receipt;
    }

    private DropTerms loadTerms(Long dropId) {
        List<DropTerms> rows = jdbc.query("""
                SELECT d.per_member_limit, d.hold_minutes, d.status, d.opens_at, p.price
                FROM drop_event d JOIN product p ON p.id = d.product_id
                WHERE d.id = ?""",
                (rs, i) -> new DropTerms(rs.getInt("per_member_limit"), rs.getInt("hold_minutes"),
                        rs.getString("status"), rs.getTimestamp("opens_at").toInstant(), rs.getLong("price")),
                dropId);
        if (rows.isEmpty()) {
            throw new CutlineException(ErrorCode.DROP_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static String hash(Long dropId, int qty) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("POST /drops/" + dropId + "/orders qty=" + qty).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record DropTerms(int perMemberLimit, int holdMinutes, String status, Instant opensAt, long price) {

        boolean isOpenAt(Instant now) {
            return "OPEN".equals(status) && !opensAt.isAfter(now);
        }
    }
}
