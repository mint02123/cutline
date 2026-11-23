package com.cutline.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCleaner {

    private static final String[] TABLES = {
            "payment", "orders", "member_drop_quota", "drop_event", "product", "member", "seller", "idempotency_key"
    };

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
