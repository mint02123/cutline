package com.cutline.order;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * 주문 접수 입구. Redis 관문을 먼저 거치고, 통과한 요청만 MySQL 주문 트랜잭션으로 보낸다 (ADR-009).
 *
 * <p>순서가 중요하다.
 * <ol>
 *   <li>관문은 트랜잭션 밖에서, DB보다 먼저 실행한다. 처음 온 품절자는 DB 커넥션을 잡지 않고 끝난다.
 *       (처음에는 멱등 키를 관문 앞에서 DB로 확인했다. 그러자 품절자도 커넥션 풀 줄에 서서 품절 p50이 6.3초였다.)</li>
 *   <li>관문이 거절해도 이 회원이 통과한 적이 있으면 멱등 재요청인지 DB에서 확인한다. 이미 성공한 주문의 재시도가
 *       "품절"이나 "한도 초과"로 보이면 안 된다.</li>
 *   <li>통과한 뒤 MySQL에서 주문이 만들어지지 않으면(실패, 동시 멱등 재요청) 토큰을 돌려준다.</li>
 * </ol>
 * Redis에 닿지 못하면 관문 없이 MySQL 경로(품절 선판정 + 조건부 UPDATE)로 처리한다. 느려지지만 정합성은 같다 (ADR-010).
 */
@Service
public class OrderAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdmissionService.class);

    private final OrderPlacementService placement;
    private final StockGate gate;
    private final boolean gateEnabled;
    private final long skipGateNanos;
    private final Semaphore fallbackPermits;
    private final long fallbackWaitMillis;
    private final AtomicLong fallbackCount = new AtomicLong();
    /** System.nanoTime 기준. 이 시각 전까지는 Redis를 부르지 않고 바로 MySQL 경로로 간다. 0이면 건너뛰지 않음. */
    private volatile long skipGateUntil;

    public OrderAdmissionService(OrderPlacementService placement, StockGate gate,
                                 @Value("${cutline.gate.enabled:true}") boolean gateEnabled,
                                 @Value("${cutline.gate.fallback.skip-gate-for:PT2S}") Duration skipGateFor,
                                 @Value("${cutline.gate.fallback.max-concurrent:20}") int maxConcurrent,
                                 @Value("${cutline.gate.fallback.wait:PT2S}") Duration fallbackWait) {
        this.placement = placement;
        this.gate = gate;
        this.gateEnabled = gateEnabled;
        this.skipGateNanos = skipGateFor.toNanos();
        this.fallbackPermits = new Semaphore(maxConcurrent, true);
        this.fallbackWaitMillis = fallbackWait.toMillis();
    }

    /** Redis 장애로 MySQL 경로에서 처리한 주문 요청 수 (테스트, 지표용). */
    public long fallbackCount() {
        return fallbackCount.get();
    }

    public OrderReceipt place(Long memberId, Long dropId, int qty, String idempotencyKey) {
        OrderPlacementService.requireKey(idempotencyKey);
        if (!gateEnabled) {
            return placement.place(memberId, dropId, qty, idempotencyKey);
        }

        if (skipGateUntil != 0 && System.nanoTime() - skipGateUntil < 0) {
            return fallback(memberId, dropId, qty, idempotencyKey);
        }

        StockGate.Decision decision;
        try {
            decision = acquire(dropId, memberId, qty);
        } catch (GateUnavailableException e) {
            // 요청마다 Redis 시간 초과(500ms)를 기다리지 않도록 잠시 관문을 건너뛴다. 지나면 다시 Redis를 시도한다.
            skipGateUntil = System.nanoTime() + skipGateNanos;
            log.warn("stock gate unavailable, using MySQL path for {} ms: {}", skipGateNanos / 1_000_000, e.getMessage());
            return fallback(memberId, dropId, qty, idempotencyKey);
        }

        return switch (decision.outcome()) {
            case ADMIT -> placeAdmitted(memberId, dropId, qty, idempotencyKey);
            case SOLD_OUT -> rejectUnlessReplay(decision, memberId, dropId, qty, idempotencyKey, ErrorCode.SOLD_OUT);
            case LIMIT_EXCEEDED ->
                    rejectUnlessReplay(decision, memberId, dropId, qty, idempotencyKey, ErrorCode.PURCHASE_LIMIT_EXCEEDED);
            case NOT_OPEN -> throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
            case NOT_LOADED -> throw new IllegalStateException("gate not loaded for drop " + dropId);
        };
    }

    /**
     * 관문은 DB를 거치지 않고 거절한다. 다만 이 회원이 관문을 통과한 적이 있으면, 이미 성공한 주문의 재시도일 수 있으므로
     * 그때만 멱등 키를 DB에서 확인한다. 처음 온 품절자는 DB 커넥션을 잡지 않고 끝난다.
     */
    private OrderReceipt rejectUnlessReplay(StockGate.Decision decision, Long memberId, Long dropId, int qty,
                                            String idempotencyKey, ErrorCode rejection) {
        if (decision.boughtBefore() && placement.isKnownKey(memberId, idempotencyKey)) {
            return placement.place(memberId, dropId, qty, idempotencyKey);
        }
        throw new CutlineException(rejection);
    }

    /**
     * Redis 없이 MySQL 경로(품절 선판정 + 조건부 UPDATE)로 처리한다 (ADR-010). 정합성은 같고 느리다.
     * 한 서버에서 동시에 이 경로로 들어가는 요청 수를 제한한다. 관문이 걸러 주던 요청이 한꺼번에 DB 커넥션과
     * 요청 스레드를 차지하지 않게 하기 위해서다. 자리를 얻지 못하면 503으로 바로 돌려보낸다.
     */
    private OrderReceipt fallback(Long memberId, Long dropId, int qty, String idempotencyKey) {
        boolean permitted;
        try {
            permitted = fallbackPermits.tryAcquire(fallbackWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CutlineException(ErrorCode.TOO_BUSY);
        }
        if (!permitted) {
            throw new CutlineException(ErrorCode.TOO_BUSY);
        }
        try {
            fallbackCount.incrementAndGet();
            return placement.place(memberId, dropId, qty, idempotencyKey);
        } finally {
            fallbackPermits.release();
        }
    }

    private StockGate.Decision acquire(long dropId, long memberId, int qty) {
        StockGate.Decision decision = gate.tryAcquire(dropId, memberId, qty);
        if (decision.outcome() != StockGate.Outcome.NOT_LOADED) {
            return decision;
        }
        switch (gate.load(dropId)) {
            case NOT_FOUND -> throw new CutlineException(ErrorCode.DROP_NOT_FOUND);
            // 닫힌 드롭은 MySQL 조건부 UPDATE가 DROP_NOT_OPEN으로 거절한다. 토큰 없이 팔릴 일이 없다.
            case CLOSED -> throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
            case LOADED -> {
                return gate.tryAcquire(dropId, memberId, qty);
            }
        }
        throw new IllegalStateException();
    }

    private OrderReceipt placeAdmitted(Long memberId, Long dropId, int qty, String idempotencyKey) {
        OrderPlacementService.Placement placed;
        try {
            placed = placement.placeAndReport(memberId, dropId, qty, idempotencyKey);
        } catch (RuntimeException e) {
            releaseQuietly(dropId, memberId, qty);
            throw e;
        }
        if (!placed.created()) {
            // 같은 키로 동시에 들어온 요청이 둘 다 관문을 통과한 경우. 주문은 하나뿐이므로 이쪽 토큰은 돌려준다.
            releaseQuietly(dropId, memberId, qty);
        }
        return placed.receipt();
    }

    private void releaseQuietly(long dropId, long memberId, int qty) {
        try {
            gate.release(dropId, memberId, qty);
        } catch (GateUnavailableException e) {
            // 돌려주지 못한 토큰은 Redis가 MySQL보다 적은 상태(덜 파는 쪽)로 남는다. 재동기화가 맞춘다.
            log.warn("failed to release gate token drop={} member={} qty={}: {}", dropId, memberId, qty, e.getMessage());
        }
    }
}
