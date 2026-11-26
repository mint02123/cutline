package com.cutline.queue;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 대기열의 Redis 저장소. 키 두 개를 해시 태그 {dropId}로 같은 슬롯에 둔다.
 * <ul>
 *   <li>{@code queue:{dropId}}: sorted set. 회원 id → 도착 순번. 앞에서부터 입장한다(7일차).</li>
 *   <li>{@code queue:{dropId}:meta}: 해시. {@code opens} = 오픈 시각(epoch ms), {@code seq} = 마지막으로 준 도착 순번,
 *       {@code soldOut} = 있으면 매진(7일차에 입장 스케줄러가 표시한다).</li>
 * </ul>
 *
 * <p>점수를 도착 시각(ms)이 아니라 meta의 {@code seq}로 준다. 같은 밀리초에 수백 명이 도착하고, 서버마다 시계가 조금씩
 * 다르기 때문이다. 순번은 Lua 안에서 하나씩 늘리므로 서버가 여러 대여도 겹치지 않고, 순서는 Redis에 도착한 순서다.
 */
@Component
class QueueStore {

    /**
     * 반환 {code, rank, joinedNow}. code: -1 meta 없음(적재 필요), 0 줄에 있음, 1 매진, 3 오픈 전.
     * 이미 줄에 있는 회원은 오픈·매진 여부와 상관없이 기존 자리를 돌려준다. 여러 탭에서 눌러도 자리는 하나다.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> JOIN = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 0 then return {-1, 0, 0} end
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if rank then return {0, rank, 0} end
            if tonumber(ARGV[2]) < tonumber(redis.call('HGET', KEYS[2], 'opens')) then return {3, 0, 0} end
            if redis.call('HEXISTS', KEYS[2], 'soldOut') == 1 then return {1, 0, 0} end
            local seq = redis.call('HINCRBY', KEYS[2], 'seq', 1)
            redis.call('ZADD', KEYS[1], 'NX', seq, ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return {0, redis.call('ZRANK', KEYS[1], ARGV[1]), 1}
            """, List.class);

    /** 반환 {metaLoaded, rank(없으면 -1), size}. 순번과 줄 길이를 같은 순간의 값으로 읽는다. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATUS = RedisScript.of("""
            local loaded = redis.call('EXISTS', KEYS[2])
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then rank = -1 end
            return {loaded, rank, redis.call('ZCARD', KEYS[1])}
            """, List.class);

    /** meta가 없을 때만 만든다. 여러 서버가 동시에 적재해도 처음 하나만 반영되고, 이미 준 순번(seq)을 덮어쓰지 않는다. */
    private static final RedisScript<Long> LOAD = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'opens', ARGV[1], 'seq', 0)
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    enum JoinOutcome { IN_LINE, SOLD_OUT, NOT_OPEN, NOT_LOADED }

    /** @param rank 내 앞에 선 사람 수 (0이면 맨 앞) */
    record Join(JoinOutcome outcome, long rank, boolean joinedNow) {
    }

    /** @param rank 줄에 없으면 -1 */
    record Status(boolean loaded, long rank, long size) {
    }

    private final StringRedisTemplate redis;
    private final String ttlSeconds;

    QueueStore(StringRedisTemplate redis, @Value("${cutline.queue.key-ttl:P7D}") Duration keyTtl) {
        this.redis = redis;
        this.ttlSeconds = String.valueOf(keyTtl.toSeconds());
    }

    Join join(long dropId, long memberId, long nowMillis) {
        List<?> r = exec(JOIN, keys(dropId), String.valueOf(memberId), String.valueOf(nowMillis), ttlSeconds);
        JoinOutcome outcome = switch (number(r, 0).intValue()) {
            case 0 -> JoinOutcome.IN_LINE;
            case 1 -> JoinOutcome.SOLD_OUT;
            case 3 -> JoinOutcome.NOT_OPEN;
            default -> JoinOutcome.NOT_LOADED;
        };
        return new Join(outcome, number(r, 1).longValue(), number(r, 2).longValue() == 1);
    }

    Status status(long dropId, long memberId) {
        List<?> r = exec(STATUS, keys(dropId), String.valueOf(memberId));
        return new Status(number(r, 0).longValue() == 1, number(r, 1).longValue(), number(r, 2).longValue());
    }

    void load(long dropId, long opensAtMillis) {
        exec(LOAD, List.of(metaKey(dropId)), String.valueOf(opensAtMillis), ttlSeconds);
    }

    @SuppressWarnings("unchecked")
    private <T> T exec(RedisScript<T> script, List<String> keys, String... args) {
        try {
            return redis.execute(script, keys, (Object[]) args);
        } catch (DataAccessException e) {
            throw new QueueUnavailableException(e);
        }
    }

    private static Number number(List<?> values, int index) {
        return (Number) values.get(index);
    }

    private static List<String> keys(long dropId) {
        return List.of(queueKey(dropId), metaKey(dropId));
    }

    static String queueKey(long dropId) {
        return "queue:{" + dropId + "}";
    }

    static String metaKey(long dropId) {
        return "queue:{" + dropId + "}:meta";
    }
}
