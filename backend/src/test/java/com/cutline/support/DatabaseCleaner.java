package com.cutline.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCleaner {

    private static final String[] TABLES = {
            "payment", "orders", "member_drop_quota", "drop_event", "product", "member", "seller", "idempotency_key"
    };

    /**
     * 드롭 id는 테스트마다 새 값부터 시작한다. TRUNCATE로 id가 1부터 다시 시작하면
     * 앞 테스트가 Redis에 남긴 관문 키(gate:{dropId})를 다음 테스트의 드롭이 물려받기 때문이다.
     */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_DROP_ID = new java.util.concurrent.atomic.AtomicLong(10_000);

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.execute("ALTER TABLE drop_event AUTO_INCREMENT = " + NEXT_DROP_ID.getAndAdd(1_000));
    }
}
