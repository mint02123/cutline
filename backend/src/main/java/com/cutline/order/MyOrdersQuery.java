package com.cutline.order;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 내 주문 목록. 오프셋 대신 커서(created_at, id) 페이지네이션이라 페이지가 깊어져도 비용이 같고,
 * 목록을 보는 사이 새 주문이 생겨도 항목이 밀리거나 중복되지 않는다. 인덱스 (member_id, created_at)를 탄다.
 */
@Component
public class MyOrdersQuery {

    public record Item(long orderId, long dropId, String productName, int qty, long amount, OrderStatus status,
                       Instant holdExpiresAt, Instant createdAt) {
    }

    public record Page(List<Item> items, String nextCursor) {
    }

    private final JdbcTemplate jdbc;

    MyOrdersQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page find(long memberId, String cursor, int size) {
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<Object> args = new ArrayList<>(List.of(memberId));
        String where = "o.member_id = ?";
        if (after != null) {
            where += " AND (o.created_at < ? OR (o.created_at = ? AND o.id < ?))";
            Timestamp at = Timestamp.from(after.createdAt());
            args.addAll(List.of(at, at, after.id()));
        }
        args.add(size + 1);
        List<Item> rows = jdbc.query("""
                SELECT o.id, o.drop_id, p.name, o.qty, o.amount, o.status, o.hold_expires_at, o.created_at
                FROM orders o
                JOIN drop_event d ON d.id = o.drop_id
                JOIN product p ON p.id = d.product_id
                WHERE %s
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT ?""".formatted(where),
                (rs, i) -> new Item(rs.getLong("id"), rs.getLong("drop_id"), rs.getString("name"), rs.getInt("qty"),
                        rs.getLong("amount"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("hold_expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
                args.toArray());
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        List<Item> page = rows.subList(0, size);
        Item last = page.getLast();
        return new Page(List.copyOf(page), new Cursor(last.createdAt(), last.orderId()).encode());
    }

    private record Cursor(Instant createdAt, long id) {

        String encode() {
            String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split(":");
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                        Long.parseLong(parts[2]));
            } catch (RuntimeException e) {
                throw new CutlineException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.");
            }
        }
    }
}
