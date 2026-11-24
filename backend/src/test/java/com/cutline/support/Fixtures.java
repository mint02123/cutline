package com.cutline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/** API를 거치지 않고 테스트 데이터를 바로 넣는다. 동시성 테스트에서 준비 시간을 줄이려는 용도. */
public final class Fixtures {

    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    private final JdbcTemplate jdbc;
    private final Instant now;

    public Fixtures(JdbcTemplate jdbc, Instant now) {
        this.jdbc = jdbc;
        this.now = now;
    }

    public long seller() {
        return insert("seller", "name", "cutline shop", "fee_rate_bps", 1000);
    }

    public long product(long sellerId, long price) {
        return insert("product", "seller_id", sellerId, "name", "Jordan 1 Retro", "price", price);
    }

    public long drop(long productId, int totalQty, int perMemberLimit, String status, Instant opensAt) {
        return insert("drop_event", "product_id", productId, "total_qty", totalQty, "remaining_qty", totalQty,
                "per_member_limit", perMemberLimit, "opens_at", Timestamp.from(opensAt), "hold_minutes", 10,
                "status", status);
    }

    public long openDrop(long price, int totalQty, int perMemberLimit) {
        return drop(product(seller(), price), totalQty, perMemberLimit, "OPEN", now);
    }

    public List<Long> members(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {"m" + SEQ.incrementAndGet() + "@cutline.test", "x", Timestamp.from(now)});
        }
        jdbc.batchUpdate("INSERT INTO member (email, password_hash, created_at) VALUES (?, ?, ?)", rows);
        return jdbc.queryForList("SELECT id FROM member ORDER BY id DESC LIMIT ?", Long.class, count).reversed();
    }

    public long member() {
        return members(1).getFirst();
    }

    private long insert(String table, Object... columnValues) {
        var insert = new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id");
        var values = new java.util.HashMap<String, Object>();
        for (int i = 0; i < columnValues.length; i += 2) {
            values.put((String) columnValues[i], columnValues[i + 1]);
        }
        values.put("created_at", Timestamp.from(now));
        return insert.executeAndReturnKey(values).longValue();
    }
}
