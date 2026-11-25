package com.cutline.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 1차 관문 (ADR-009). "들어가도 되는가"만 판단하고, "팔렸는가"는 여전히 MySQL이 판단한다 (ADR-001).
 *
 * <p>키 (해시 태그 {dropId}로 두 키가 같은 슬롯에 놓인다):
 * <ul>
 *   <li>{@code gate:{dropId}}: 해시. {@code stock} = 남은 입장 토큰, {@code limit} = 1인 한도</li>
 *   <li>{@code gate:{dropId}:bought}: 해시. 회원 id → 관문을 통과한 수량</li>
 * </ul>
 *
 * <p>Redis가 틀리면 덜 파는 쪽으로만 틀리게 만든다. 토큰은 MySQL의 남은 재고보다 많아지지 않게 적재하고,
 * 관문을 통과했는데 MySQL에서 실패하면 토큰을 돌려준다. 돌려주지 못한 토큰은 재동기화가 맞춘다.
 */
@Component
class StockGate {

    /** 반환: -1 키 없음(적재 필요), 0 통과, 1 품절, 2 한도 초과. */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local stock = redis.call('HGET', KEYS[1], 'stock')
            if not stock then return -1 end
            local qty = tonumber(ARGV[2])
            local limit = tonumber(redis.call('HGET', KEYS[1], 'limit'))
            local used = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
            if used + qty > limit then return 2 end
            if tonumber(stock) < qty then return 1 end
            redis.call('HINCRBY', KEYS[1], 'stock', -qty)
            redis.call('HINCRBY', KEYS[2], ARGV[1], qty)
            if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
            return 0
            """, Long.class);

    /** 관문은 통과했지만 주문이 만들어지지 않았을 때 토큰과 회원 수량을 되돌린다. 키가 사라졌으면 아무것도 하지 않는다. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HINCRBY', KEYS[1], 'stock', ARGV[2])
            local used = redis.call('HINCRBY', KEYS[2], ARGV[1], -tonumber(ARGV[2]))
            if used <= 0 then redis.call('HDEL', KEYS[2], ARGV[1]) end
            return 1
            """, Long.class);

    /** 키가 없을 때만 적재한다. 여러 서버가 동시에 적재해도 처음 하나만 반영된다. */
    private static final RedisScript<Long> LOAD = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'stock', ARGV[1], 'limit', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    enum Decision { ADMIT, SOLD_OUT, LIMIT_EXCEEDED, NOT_LOADED }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String ttlSeconds;

    StockGate(StringRedisTemplate redis, JdbcTemplate jdbc, Clock clock,
              @Value("${cutline.gate.key-ttl:P7D}") Duration keyTtl) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttlSeconds = String.valueOf(keyTtl.toSeconds());
    }

    Decision tryAcquire(long dropId, long memberId, int qty) {
        Long result = redis.execute(ACQUIRE, keys(dropId), String.valueOf(memberId), String.valueOf(qty), ttlSeconds);
        return switch (result == null ? -1 : result.intValue()) {
            case 0 -> Decision.ADMIT;
            case 1 -> Decision.SOLD_OUT;
            case 2 -> Decision.LIMIT_EXCEEDED;
            default -> Decision.NOT_LOADED;
        };
    }

    void release(long dropId, long memberId, int qty) {
        redis.execute(RELEASE, keys(dropId), String.valueOf(memberId), String.valueOf(qty));
    }

    /**
     * 열린 드롭이면 MySQL의 남은 재고로 토큰을 적재하고 true. 열리지 않았거나 없는 드롭이면 false (MySQL 경로가 알맞은 오류를 낸다).
     *
     * <p>회원별 통과 수량은 적재하지 않는다. Redis를 잃은 뒤 다시 적재하면 이미 산 회원도 관문을 통과할 수 있지만,
     * MySQL 한도 upsert가 막고 토큰은 되돌려진다. 관문은 거르는 장치이고 판정은 MySQL이 한다.
     */
    boolean loadIfOpen(long dropId) {
        List<int[]> rows = jdbc.query("""
                SELECT remaining_qty, per_member_limit FROM drop_event
                WHERE id = ? AND status = 'OPEN' AND opens_at <= ?""",
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, dropId, Timestamp.from(clock.instant()));
        if (rows.isEmpty()) {
            return false;
        }
        int[] row = rows.getFirst();
        redis.execute(LOAD, List.of(stockKey(dropId)), String.valueOf(row[0]), String.valueOf(row[1]), ttlSeconds);
        return true;
    }

    /** 남은 입장 토큰. 키가 없으면 null. 정합성 확인과 테스트용. */
    Integer tokens(long dropId) {
        Object value = redis.opsForHash().get(stockKey(dropId), "stock");
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private static List<String> keys(long dropId) {
        return List.of(stockKey(dropId), "gate:{" + dropId + "}:bought");
    }

    private static String stockKey(long dropId) {
        return "gate:{" + dropId + "}";
    }
}
