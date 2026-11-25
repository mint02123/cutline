package com.cutline.order;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 1차 관문 (ADR-009). "들어가도 되는가"만 판단하고, "팔렸는가"는 여전히 MySQL이 판단한다 (ADR-001).
 *
 * <p>키 (해시 태그 {dropId}로 두 키가 같은 슬롯에 놓인다):
 * <ul>
 *   <li>{@code gate:{dropId}}: 해시. {@code stock} = 남은 입장 토큰, {@code limit} = 1인 한도,
 *       {@code opens} = 오픈 시각(epoch ms). 오픈 전 요청도 DB에 닿지 않고 거절한다.</li>
 *   <li>{@code gate:{dropId}:bought}: 해시. 회원 id → 관문을 통과한 수량</li>
 * </ul>
 *
 * <p>Redis가 틀리면 덜 파는 쪽으로만 틀리게 만든다. 토큰은 MySQL의 남은 재고보다 많아지지 않게 적재하고,
 * 관문을 통과했는데 MySQL에서 실패하면 토큰을 돌려준다. 돌려주지 못한 토큰은 재동기화가 맞춘다.
 */
@Component
class StockGate {

    /**
     * 반환: -1 키 없음(적재 필요), 0 통과, 1 품절, 2 한도 초과, 3 오픈 전.
     * 거절할 때 이 회원이 이미 관문을 통과한 적이 있으면 10을 더한다(11, 12). 그런 요청만 멱등 재요청인지 DB에서 확인한다.
     */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local stock = redis.call('HGET', KEYS[1], 'stock')
            if not stock then return -1 end
            if tonumber(ARGV[4]) < tonumber(redis.call('HGET', KEYS[1], 'opens')) then return 3 end
            local qty = tonumber(ARGV[2])
            local limit = tonumber(redis.call('HGET', KEYS[1], 'limit'))
            local used = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
            local seen = 0
            if used > 0 then seen = 10 end
            if used + qty > limit then return 2 + seen end
            if tonumber(stock) < qty then return 1 + seen end
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
            redis.call('HSET', KEYS[1], 'stock', ARGV[1], 'limit', ARGV[2], 'opens', ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    /** 토큰이 ARGV[1]보다 많으면 ARGV[1]로 낮춘다. 낮췄으면 낮추기 전 값, 아니면 -1. */
    private static final RedisScript<Long> CLAMP = RedisScript.of("""
            local stock = redis.call('HGET', KEYS[1], 'stock')
            if not stock then return -1 end
            if tonumber(stock) > tonumber(ARGV[1]) then
              redis.call('HSET', KEYS[1], 'stock', ARGV[1])
              return tonumber(stock)
            end
            return -1
            """, Long.class);

    /** 토큰이 아직 ARGV[1]일 때만 ARGV[2]로 바꾼다 (그 사이 누가 통과·반환했으면 다음 기회로 미룬다). */
    private static final RedisScript<Long> COMPARE_AND_SET = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'stock') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'stock', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    enum Outcome { ADMIT, SOLD_OUT, LIMIT_EXCEEDED, NOT_OPEN, NOT_LOADED }

    /** @param boughtBefore 이 회원이 이 드롭의 관문을 이미 통과한 적이 있는지. 거절일 때만 의미가 있다. */
    record Decision(Outcome outcome, boolean boughtBefore) {
    }

    enum Load { LOADED, CLOSED, NOT_FOUND }

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
        Long result = exec(ACQUIRE, keys(dropId), String.valueOf(memberId), String.valueOf(qty), ttlSeconds,
                String.valueOf(clock.millis()));
        int code = result == null ? -1 : result.intValue();
        Outcome outcome = switch (code % 10) {
            case 0 -> Outcome.ADMIT;
            case 1 -> Outcome.SOLD_OUT;
            case 2 -> Outcome.LIMIT_EXCEEDED;
            case 3 -> Outcome.NOT_OPEN;
            default -> Outcome.NOT_LOADED;
        };
        return new Decision(outcome, code >= 10);
    }

    void release(long dropId, long memberId, int qty) {
        exec(RELEASE, keys(dropId), String.valueOf(memberId), String.valueOf(qty));
    }

    /**
     * 판매 예정이거나 판매 중인 드롭이면 MySQL의 남은 재고와 오픈 시각으로 토큰을 적재한다.
     * 오픈 전에 적재해 두면, 오픈 직전에 몰리는 요청도 DB를 거치지 않고 "오픈 전"으로 거절된다.
     *
     * <p>처음에는 열린 드롭만 적재하고 오픈 전 요청은 MySQL 경로로 보냈다. 두 가지 문제가 있었다.
     * (1) 이 조회와 주문 사이에 오픈 스케줄러가 드롭을 열면 토큰 없이 MySQL에서 팔려, 토큰이 MySQL 남은 재고보다 44개 많아졌다.
     * (2) 오픈 전 재시도가 전부 DB 커넥션을 잡아, 오픈 순간 커넥션 풀이 막혔다.
     * 지금은 오픈 시각 판정도 관문이 하고, 관문을 통과하지 않은 요청은 MySQL 주문 경로로 가지 않는다.
     * 관문은 오픈 시각이 지나면 통과시키지만 MySQL 상태가 아직 SCHEDULED면 주문이 실패하고 토큰은 반환된다.
     *
     * <p>회원별 통과 수량은 적재하지 않는다. Redis를 잃은 뒤 다시 적재하면 이미 산 회원도 관문을 통과할 수 있지만,
     * MySQL 한도 upsert가 막고 토큰은 되돌려진다. 관문은 거르는 장치이고 판정은 MySQL이 한다.
     */
    Load load(long dropId) {
        List<Object[]> rows = jdbc.query(
                "SELECT remaining_qty, per_member_limit, status, opens_at FROM drop_event WHERE id = ?",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getTimestamp(4).getTime()},
                dropId);
        if (rows.isEmpty()) {
            return Load.NOT_FOUND;
        }
        Object[] row = rows.getFirst();
        if ("CLOSED".equals(row[2])) {
            return Load.CLOSED;
        }
        exec(LOAD, List.of(stockKey(dropId)), String.valueOf(row[0]), String.valueOf(row[1]), ttlSeconds,
                String.valueOf(row[3]));
        return Load.LOADED;
    }

    /** 토큰이 max보다 많으면 max로 낮추고 낮추기 전 값을, 아니면 null을 돌려준다. */
    Integer clampTo(long dropId, int max) {
        Long before = exec(CLAMP, List.of(stockKey(dropId)), String.valueOf(max));
        return before == null || before < 0 ? null : before.intValue();
    }

    boolean compareAndSet(long dropId, int expected, int value) {
        Long changed = exec(COMPARE_AND_SET, List.of(stockKey(dropId)), String.valueOf(expected),
                String.valueOf(value));
        return changed != null && changed == 1;
    }

    /** 남은 입장 토큰. 키가 없으면 null. 정합성 확인과 테스트용. */
    Integer tokens(long dropId) {
        Object value;
        try {
            value = redis.opsForHash().get(stockKey(dropId), "stock");
        } catch (DataAccessException e) {
            throw new GateUnavailableException(e);
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    /** Redis 실패를 GateUnavailableException으로 바꾼다. MySQL(JdbcTemplate) 실패와 구분하기 위해서다. */
    private Long exec(RedisScript<Long> script, List<String> keys, String... args) {
        try {
            return redis.execute(script, keys, (Object[]) args);
        } catch (DataAccessException e) {
            throw new GateUnavailableException(e);
        }
    }

    private static List<String> keys(long dropId) {
        return List.of(stockKey(dropId), "gate:{" + dropId + "}:bought");
    }

    private static String stockKey(long dropId) {
        return "gate:{" + dropId + "}";
    }
}
