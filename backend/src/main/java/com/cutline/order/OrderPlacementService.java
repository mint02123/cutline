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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 생성 + 재고 점유 (설계 문서 "동시성과 정합성 전략").
 *
 * <p>하나의 트랜잭션 안에서 순서대로:
 * <ol>
 *   <li>멱등 키 등록 (이미 처리된 요청이면 저장된 응답 반환)</li>
 *   <li>품절 선판정: 잠금 없는 읽기로 남은 재고가 모자라면 바로 SOLD_OUT (ADR-008)</li>
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
    private final boolean soldOutPrecheck;

    public OrderPlacementService(JdbcTemplate jdbc, OrderRepository orders, IdempotencyStore idempotency, Clock clock,
                                 @Value("${cutline.order.sold-out-precheck:true}") boolean soldOutPrecheck) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.idempotency = idempotency;
        this.clock = clock;
        this.soldOutPrecheck = soldOutPrecheck;
    }

    @Transactional
    public OrderReceipt place(Long memberId, Long dropId, int qty, String idempotencyKey) {
        return placeAndReport(memberId, dropId, qty, idempotencyKey).receipt();
    }

    /** {@link #place}와 같지만, 이번 호출이 주문을 새로 만들었는지(멱등 재요청이 아닌지)도 알려준다. */
    @Transactional
    public Placement placeAndReport(Long memberId, Long dropId, int qty, String idempotencyKey) {
        requireKey(idempotencyKey);
        Instant now = clock.instant();

        var replay = idempotency.begin(memberId, idempotencyKey, hash(dropId, qty), now);
        if (replay.isPresent()) {
            return new Placement(replay.get(), false);
        }

        DropTerms drop = loadTerms(dropId);
        if (qty > drop.perMemberLimit()) {
            throw new CutlineException(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }

        // 품절 선판정 (ADR-008). 아래 조건부 UPDATE는 조건이 거짓이어도 REPEATABLE READ에서 행을 잠그므로,
        // 품절된 사람까지 재고 행의 잠금 줄에 서게 된다 (1단계 기준선: 품절 응답 p50 6.3초).
        // loadTerms는 잠금 없는 일관된 읽기라 기다리지 않는다. 여기서 모자라 보이면 잠금 줄에 서지 않고 돌려보낸다.
        // 이 판단이 틀리는 방향은 "남았는데 품절로 보임"(그 사이 만료·취소로 재고가 돌아온 경우)뿐이고,
        // 그 사람은 다시 누르면 된다. 팔지 말아야 할 것을 파는 방향의 판단은 여전히 아래 UPDATE가 한다.
        if (soldOutPrecheck && drop.isOpenAt(now) && drop.remainingQty() < qty) {
            throw new CutlineException(ErrorCode.SOLD_OUT);
        }

        int stock = jdbc.update("""
                UPDATE drop_event SET remaining_qty = remaining_qty - ?
                WHERE id = ? AND status IN ('SCHEDULED', 'OPEN') AND opens_at <= ? AND remaining_qty >= ?""",
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
        return new Placement(receipt, true);
    }

    /** 이미 등록된 멱등 키인지. 트랜잭션 없이 읽기 한 번. 재요청을 관문보다 먼저 알아보는 데 쓴다. */
    public boolean isKnownKey(Long memberId, String idempotencyKey) {
        return idempotencyKey != null && idempotency.exists(memberId, idempotencyKey);
    }

    static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new CutlineException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    public record Placement(OrderReceipt receipt, boolean created) {
    }

    private DropTerms loadTerms(Long dropId) {
        List<DropTerms> rows = jdbc.query("""
                SELECT d.per_member_limit, d.hold_minutes, d.status, d.opens_at, d.remaining_qty, p.price
                FROM drop_event d JOIN product p ON p.id = d.product_id
                WHERE d.id = ?""",
                (rs, i) -> new DropTerms(rs.getInt("per_member_limit"), rs.getInt("hold_minutes"),
                        rs.getString("status"), rs.getTimestamp("opens_at").toInstant(), rs.getInt("remaining_qty"),
                        rs.getLong("price")),
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

    private record DropTerms(int perMemberLimit, int holdMinutes, String status, Instant opensAt, int remainingQty,
                             long price) {

        /**
         * 판매 가능 여부는 오픈 시각으로 정한다. SCHEDULED→OPEN 상태 변경은 스케줄러(1초 주기)가 뒤따라 하는 표시일 뿐이다.
         * 처음에는 status = 'OPEN'도 조건에 넣었는데, Redis 관문은 오픈 시각에 바로 통과시키므로 상태가 바뀌기 전
         * 최대 1초 동안 통과한 요청이 MySQL에서 "오픈 전"으로 실패했다. 3,000명 측정에서 재고 행 UPDATE의 30%(449건)가
         * 이런 헛걸음이었고, 그만큼 재고 행 잠금을 잡았다가 롤백했다.
         */
        boolean isOpenAt(Instant now) {
            return !"CLOSED".equals(status) && !opensAt.isAfter(now);
        }
    }
}
