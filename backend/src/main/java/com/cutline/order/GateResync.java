package com.cutline.order;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 관문 토큰을 MySQL 남은 재고에 맞춘다 (ADR-009).
 *
 * <p>두 방향을 다르게 다룬다.
 * <ul>
 *   <li><b>토큰이 MySQL보다 많다</b> (더 팔 수 있는 쪽): 바로 낮춘다. MySQL 조건부 UPDATE가 초과판매는 막지만,
 *       불변 조건(토큰 ≤ 남은 재고)이 깨진 것이므로 기다릴 이유가 없다.</li>
 *   <li><b>토큰이 MySQL보다 적다</b> (덜 파는 쪽): 바로 올리지 않는다. 관문을 통과해 MySQL 트랜잭션을 진행 중인
 *       요청은 토큰은 썼지만 아직 재고를 줄이지 않았으므로, 정상 상태에서도 잠깐 이렇게 보인다.
 *       같은 차이가 두 번 연속(토큰과 남은 재고가 모두 그대로) 보일 때만, 토큰이 그 값 그대로일 때 올린다.
 *       반환에 실패한 토큰(서버가 죽음, Redis 순간 장애)이 이렇게 돌아온다.</li>
 * </ul>
 * 오픈 러시 동안에는 남은 재고가 계속 바뀌므로 올리기는 일어나지 않고, 판매가 멈춘 뒤에 맞춰진다.
 * 서버가 여러 대여도 compare-and-set이라 같은 차이를 두 번 채우지 않는다.
 */
@Component
public class GateResync {

    private static final Logger log = LoggerFactory.getLogger(GateResync.class);

    private final JdbcTemplate jdbc;
    private final StockGate gate;
    /** dropId → 지난번에 본 (토큰, 남은 재고). 모자란 상태가 이어지는지 보는 데만 쓴다. */
    private final Map<Long, int[]> lastShortfall = new HashMap<>();
    /** synchronized 대신 쓴다. Java 21 가상 스레드는 synchronized 안에서 I/O를 기다리면 캐리어 스레드를 붙잡는다(pinning). */
    private final ReentrantLock lock = new ReentrantLock();

    GateResync(JdbcTemplate jdbc, StockGate gate) {
        this.jdbc = jdbc;
        this.gate = gate;
    }

    public record Report(int lowered, int raised, int pending) {
    }

    public Report resync() {
        lock.lock();
        try {
            return resyncLocked();
        } finally {
            lock.unlock();
        }
    }

    private Report resyncLocked() {
        int lowered = 0;
        int raised = 0;
        int pending = 0;
        Map<Long, int[]> seen = new HashMap<>();
        List<long[]> drops = jdbc.query(
                "SELECT id, remaining_qty FROM drop_event WHERE status IN ('SCHEDULED', 'OPEN')",
                (rs, i) -> new long[] {rs.getLong(1), rs.getInt(2)});
        for (long[] drop : drops) {
            long dropId = drop[0];
            int remaining = (int) drop[1];
            Integer before = gate.clampTo(dropId, remaining);
            if (before != null) {
                lowered++;
                log.warn("gate tokens above MySQL stock, lowered drop={} tokens={} -> {}", dropId, before, remaining);
                continue;
            }
            Integer tokens = gate.tokens(dropId);
            if (tokens == null || tokens >= remaining) {
                continue;
            }
            int[] previous = lastShortfall.get(dropId);
            if (previous != null && previous[0] == tokens && previous[1] == remaining
                    && gate.compareAndSet(dropId, tokens, remaining)) {
                raised++;
                log.warn("gate tokens below MySQL stock twice in a row, raised drop={} tokens={} -> {}",
                        dropId, tokens, remaining);
                continue;
            }
            seen.put(dropId, new int[] {tokens, remaining});
            pending++;
        }
        lastShortfall.clear();
        lastShortfall.putAll(seen);
        return new Report(lowered, raised, pending);
    }

    /**
     * 한 드롭의 토큰을 MySQL 남은 재고 이하로 낮춘다. Redis 장애 동안 토큰 없이 MySQL 경로로 팔린 드롭을
     * Redis가 돌아온 직후 관문을 다시 쓰기 전에 맞추는 데 쓴다 (ADR-010). 남은 재고는 잠금 없이 읽는다.
     */
    public void clamp(long dropId) {
        List<Integer> remaining = jdbc.queryForList(
                "SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
        if (remaining.isEmpty()) {
            return;
        }
        Integer before = gate.clampTo(dropId, remaining.getFirst());
        if (before != null) {
            log.warn("gate tokens above MySQL stock after Redis recovery, lowered drop={} tokens={} -> {}",
                    dropId, before, remaining.getFirst());
        }
    }

    /** 정합성 체커용: 토큰이 MySQL 남은 재고보다 많은 드롭. "dropId: tokens=…, remaining=…" 형식. */
    public List<String> tokensAboveStock() {
        return jdbc.query("SELECT id, remaining_qty FROM drop_event WHERE status IN ('SCHEDULED', 'OPEN')",
                        (rs, i) -> new long[] {rs.getLong(1), rs.getInt(2)}).stream()
                .filter(d -> {
                    Integer tokens = gate.tokens(d[0]);
                    return tokens != null && tokens > d[1];
                })
                .map(d -> "drop " + d[0] + ": tokens=" + gate.tokens(d[0]) + " remaining=" + d[1])
                .toList();
    }
}
