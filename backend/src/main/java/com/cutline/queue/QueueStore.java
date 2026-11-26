package com.cutline.queue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 대기열의 Redis 저장소. 드롭마다 키 세 개를 해시 태그 {dropId}로 같은 슬롯에 둔다.
 * <ul>
 *   <li>{@code queue:{dropId}}: sorted set. 회원 id → 도착 순번. 입장하면 여기서 빠진다.</li>
 *   <li>{@code queue:{dropId}:meta}: 해시. {@code opens} = 오픈 시각(epoch ms), {@code seq} = 마지막으로 준 도착 순번,
 *       {@code lastAdmit} = 마지막 입장 처리 시각, {@code soldOut} = 있으면 매진 (MySQL 남은 재고 0).</li>
 *   <li>{@code queue:{dropId}:admitted}: 해시. 입장한 회원 id → 입장 만료 시각(epoch ms).</li>
 * </ul>
 * 그리고 입장 스케줄러가 돌 드롭 목록 {@code queue:active} (set) 하나.
 *
 * <p>점수를 도착 시각(ms)이 아니라 meta의 {@code seq}로 준다. 같은 밀리초에 수백 명이 도착하고, 서버마다 시계가 조금씩
 * 다르기 때문이다. 순번은 Lua 안에서 하나씩 늘리므로 서버가 여러 대여도 겹치지 않고, 순서는 Redis에 도착한 순서다.
 */
@Component
class QueueStore {

    static final String ACTIVE_KEY = "queue:active";

    /**
     * 반환 {code, rank, joinedNow, admittedUntil}. code: -1 meta 없음(적재 필요), 0 줄에 있음, 1 매진, 2 입장함, 3 오픈 전.
     * 이미 줄에 있거나 입장한 회원은 오픈·매진 여부와 상관없이 기존 상태를 돌려준다. 여러 탭에서 눌러도 자리는 하나다.
     * 입장 시간이 지난 회원은 줄 맨 뒤에 다시 선다.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> JOIN = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 0 then return {-1, 0, 0, 0} end
            local now = tonumber(ARGV[2])
            local until_ = redis.call('HGET', KEYS[3], ARGV[1])
            if until_ and tonumber(until_) > now then return {2, 0, 0, tonumber(until_)} end
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if rank then return {0, rank, 0, 0} end
            if now < tonumber(redis.call('HGET', KEYS[2], 'opens')) then return {3, 0, 0, 0} end
            if redis.call('HEXISTS', KEYS[2], 'soldOut') == 1 then return {1, 0, 0, 0} end
            if until_ then redis.call('HDEL', KEYS[3], ARGV[1]) end
            local seq = redis.call('HINCRBY', KEYS[2], 'seq', 1)
            redis.call('ZADD', KEYS[1], 'NX', seq, ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return {0, redis.call('ZRANK', KEYS[1], ARGV[1]), 1, 0}
            """, List.class);

    /**
     * 반환 {metaLoaded, rank(없으면 -1), size, admittedUntil(없으면 0), soldOut}. 같은 순간의 값으로 읽는다.
     * 입장 만료 여부는 호출하는 쪽이 판단한다 (만료된 회원에게 "다시 줄 서기"를 안내하기 위해).
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATUS = RedisScript.of("""
            local loaded = redis.call('EXISTS', KEYS[2])
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then rank = -1 end
            local until_ = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
            return {loaded, rank, redis.call('ZCARD', KEYS[1]), until_, redis.call('HEXISTS', KEYS[2], 'soldOut')}
            """, List.class);

    /** meta가 없을 때만 만든다. 여러 서버가 동시에 적재해도 처음 하나만 반영되고, 이미 준 순번(seq)을 덮어쓰지 않는다. */
    private static final RedisScript<Long> LOAD = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'opens', ARGV[1], 'seq', 0)
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /**
     * 줄 앞에서 입장시킨다. 반환: -1 meta 없음, 그 외 이번에 입장한 인원.
     *
     * <p>입장 인원은 호출 간격이 아니라 지난 입장 이후 흐른 시간으로 정한다 (흐른 ms × 초당 입장 / 1000).
     * 그래서 서버 여러 대의 스케줄러가 함께 돌아도 전체 입장 속도는 설정값 그대로이고, ZPOPMIN이 스크립트 안에 있어
     * 같은 사람을 두 번 꺼내지 않는다. 스케줄러가 멈췄다가 돌아오면 밀린 만큼 한꺼번에 들이지 않게 maxBurst로 자른다.
     * 오픈 전과 매진 중에는 아무도 들이지 않고, 재개될 때 한꺼번에 들이지 않게 기준 시각만 옮긴다.
     */
    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 0 then return -1 end
            local now = tonumber(ARGV[1])
            if now < tonumber(redis.call('HGET', KEYS[2], 'opens')) then return 0 end
            local last = redis.call('HGET', KEYS[2], 'lastAdmit')
            if not last or redis.call('HEXISTS', KEYS[2], 'soldOut') == 1 then
              redis.call('HSET', KEYS[2], 'lastAdmit', now)
              return 0
            end
            last = tonumber(last)
            local rate = tonumber(ARGV[2])
            local maxBurst = tonumber(ARGV[3])
            local elapsed = now - last
            if elapsed > maxBurst then
              last = now - maxBurst
              elapsed = maxBurst
            end
            local n = math.floor(elapsed * rate / 1000)
            if n < 1 then return 0 end
            redis.call('HSET', KEYS[2], 'lastAdmit', last + math.floor(n * 1000 / rate))
            local popped = redis.call('ZPOPMIN', KEYS[1], n)
            local until_ = now + tonumber(ARGV[4])
            for i = 1, #popped, 2 do
              redis.call('HSET', KEYS[3], popped[i], until_)
            end
            if #popped > 0 and redis.call('TTL', KEYS[3]) < 0 then redis.call('EXPIRE', KEYS[3], ARGV[5]) end
            return #popped / 2
            """, Long.class);

    enum JoinOutcome { IN_LINE, ADMITTED, SOLD_OUT, NOT_OPEN, NOT_LOADED }

    /**
     * @param rank          내 앞에 선 사람 수 (0이면 맨 앞)
     * @param admittedUntil 입장했으면 입장 만료 시각(epoch ms), 아니면 0
     */
    record Join(JoinOutcome outcome, long rank, boolean joinedNow, long admittedUntil) {
    }

    /**
     * @param rank          줄에 없으면 -1
     * @param admittedUntil 입장 기록이 없으면 0. 지난 시각일 수 있다 (입장 시간 초과)
     */
    record Status(boolean loaded, long rank, long size, long admittedUntil, boolean soldOut) {
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
            case 2 -> JoinOutcome.ADMITTED;
            case 3 -> JoinOutcome.NOT_OPEN;
            default -> JoinOutcome.NOT_LOADED;
        };
        return new Join(outcome, number(r, 1).longValue(), number(r, 2).longValue() == 1, number(r, 3).longValue());
    }

    Status status(long dropId, long memberId) {
        List<?> r = exec(STATUS, keys(dropId), String.valueOf(memberId));
        return new Status(number(r, 0).longValue() == 1, number(r, 1).longValue(), number(r, 2).longValue(),
                number(r, 3).longValue(), number(r, 4).longValue() == 1);
    }

    /** meta를 적재하고 입장 스케줄러가 돌 드롭 목록에 넣는다. */
    void load(long dropId, long opensAtMillis) {
        exec(LOAD, List.of(metaKey(dropId)), String.valueOf(opensAtMillis), ttlSeconds);
        call(() -> redis.opsForSet().add(ACTIVE_KEY, String.valueOf(dropId)));
    }

    /** @return 이번에 입장한 인원. meta가 사라진 드롭이면 -1 */
    long admit(long dropId, long nowMillis, int perSecond, long maxBurstMillis, long admissionTtlMillis) {
        Long admitted = exec(ADMIT, keys(dropId), String.valueOf(nowMillis), String.valueOf(perSecond),
                String.valueOf(maxBurstMillis), String.valueOf(admissionTtlMillis), ttlSeconds);
        return admitted == null ? -1 : admitted;
    }

    void markSoldOut(long dropId, boolean soldOut) {
        call(() -> soldOut
                ? redis.opsForHash().putIfAbsent(metaKey(dropId), "soldOut", "1")
                : redis.opsForHash().delete(metaKey(dropId), "soldOut"));
    }

    Set<String> activeDrops() {
        return call(() -> redis.opsForSet().members(ACTIVE_KEY));
    }

    void deactivate(long dropId) {
        call(() -> redis.opsForSet().remove(ACTIVE_KEY, String.valueOf(dropId)));
    }

    private <T> T exec(RedisScript<T> script, List<String> keys, String... args) {
        return call(() -> redis.execute(script, keys, (Object[]) args));
    }

    private static <T> T call(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (DataAccessException e) {
            throw new QueueUnavailableException(e);
        }
    }

    private static Number number(List<?> values, int index) {
        return (Number) values.get(index);
    }

    private static List<String> keys(long dropId) {
        return List.of(queueKey(dropId), metaKey(dropId), admittedKey(dropId));
    }

    static String queueKey(long dropId) {
        return "queue:{" + dropId + "}";
    }

    static String metaKey(long dropId) {
        return "queue:{" + dropId + "}:meta";
    }

    static String admittedKey(long dropId) {
        return "queue:{" + dropId + "}:admitted";
    }
}
