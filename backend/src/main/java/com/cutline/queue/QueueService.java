package com.cutline.queue;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.drop.DropEvent;
import com.cutline.drop.DropRepository;
import com.cutline.drop.DropStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 대기열 (설계 문서 2-3). 줄 서기, 순번 조회, 입장.
 *
 * <p>줄 서기와 순번 조회는 MySQL에 닿지 않는다. 드롭마다 처음 한 번만 오픈 시각을 MySQL에서 읽어 Redis에 적재하고,
 * 그 뒤로는 오픈 전 거절과 매진 거절까지 Redis 안에서 끝난다. 오픈 순간 몰리는 요청이 커넥션 풀 줄에 서지 않게 하기 위해서다.
 *
 * <p>입장은 스케줄러가 정해진 속도로 줄 앞에서 꺼내는 방식이다 ({@link #admitTick()}). 입장한 회원은 순번 조회에서
 * 서명된 입장 토큰을 받고, 주문 API는 그 토큰을 확인한다 ({@link AdmissionGuard}).
 *
 * <p>Redis에 닿지 못하면 대기열을 건너뛴다 (ADR-012). 줄 자체가 Redis에 있어 MySQL로 대신 세울 수 없으므로,
 * 바로 입장 토큰을 주고 주문은 2-2의 MySQL 폴백 경로(서버당 동시 처리 제한)로 처리된다. 느리고 순서가 공정하지 않지만
 * 판매는 이어지고 정합성은 같다.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    public enum State {
        /** 줄에서 기다리는 중 */
        WAITING,
        /** 입장함. admissionToken으로 주문한다 */
        ADMITTED,
        /** 줄에 서 있지만 매진. 만료·취소로 재고가 돌아오면 다시 WAITING이 된다 */
        SOLD_OUT,
        /** 입장했지만 주문하지 않고 입장 시간이 지남. 다시 줄을 서야 한다 */
        EXPIRED,
        NOT_IN_QUEUE
    }

    /**
     * @param ahead                내 앞에 선 사람 수. 줄에 없으면 null
     * @param lineLength           지금 줄에 선 전체 인원
     * @param estimatedWaitSeconds 입장 속도 설정값으로 계산한 예상 대기 시간. 기다리는 중이 아니면 null
     * @param pollAfterMs          다음 순번 조회까지 기다릴 시간. 더 물을 필요가 없으면 0
     * @param admissionToken       입장했으면 주문 API의 X-Admission-Token 헤더에 넣을 토큰
     * @param bypassed             Redis 장애로 줄을 건너뛰고 입장했으면 true
     */
    public record QueueView(long dropId, State state, Long ahead, long lineLength, Long estimatedWaitSeconds,
                            long pollAfterMs, String admissionToken, Instant admissionExpiresAt, boolean bypassed) {
    }

    private final QueueStore store;
    private final DropRepository drops;
    private final AdmissionTokens tokens;
    private final Clock clock;
    private final int admitPerSecond;
    private final long maxBurstMs;
    private final long admissionTtlMs;
    private final long minPollMs;
    private final long maxPollMs;
    private final boolean bypassOnOutage;

    QueueService(QueueStore store, DropRepository drops, AdmissionTokens tokens, Clock clock,
                 @Value("${cutline.queue.admit-per-second:200}") int admitPerSecond,
                 @Value("${cutline.queue.admitter.max-burst:PT1S}") Duration maxBurst,
                 @Value("${cutline.queue.admission.ttl:PT2M}") Duration admissionTtl,
                 @Value("${cutline.queue.poll.min-ms:1000}") long minPollMs,
                 @Value("${cutline.queue.poll.max-ms:5000}") long maxPollMs,
                 @Value("${cutline.queue.bypass-on-outage:true}") boolean bypassOnOutage) {
        this.store = store;
        this.drops = drops;
        this.tokens = tokens;
        this.clock = clock;
        this.admitPerSecond = admitPerSecond;
        this.maxBurstMs = maxBurst.toMillis();
        this.admissionTtlMs = admissionTtl.toMillis();
        this.minPollMs = minPollMs;
        this.maxPollMs = maxPollMs;
        this.bypassOnOutage = bypassOnOutage;
    }

    /** 줄에 선다. 이미 서 있거나 입장했으면 지금 상태를 돌려준다 (멱등). */
    public QueueView join(long memberId, long dropId) {
        return orBypass(memberId, dropId, () -> {
            QueueStore.Join join = store.join(dropId, memberId, clock.millis());
            if (join.outcome() == QueueStore.JoinOutcome.NOT_LOADED) {
                load(dropId);
                join = store.join(dropId, memberId, clock.millis());
            }
            return switch (join.outcome()) {
                // 방금 선 사람은 줄의 맨 뒤이므로 줄 길이 = 내 앞 인원 + 1
                case IN_LINE -> join.joinedNow()
                        ? view(dropId, memberId, new QueueStore.Status(true, join.rank(), join.rank() + 1, 0, false))
                        : view(dropId, memberId, store.status(dropId, memberId));
                case ADMITTED -> admitted(dropId, memberId, join.admittedUntil(), false);
                case SOLD_OUT -> throw new CutlineException(ErrorCode.SOLD_OUT);
                case NOT_OPEN -> throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
                case NOT_LOADED -> throw new IllegalStateException("queue meta not loaded for drop " + dropId);
            };
        });
    }

    /** 내 순번. 순번 조회는 폴링되므로 줄에 없어도 200으로 답한다. */
    public QueueView status(long memberId, long dropId) {
        return orBypass(memberId, dropId, () -> {
            QueueStore.Status status = store.status(dropId, memberId);
            if (!status.loaded()) {
                // 아무도 줄을 서지 않은 드롭이거나 없는 드롭이다. 없는 드롭이면 여기서 404가 난다.
                load(dropId);
            }
            return view(dropId, memberId, status);
        });
    }

    /**
     * 진행 중인 드롭마다 MySQL 남은 재고로 매진 표시를 맞추고, 줄 앞에서 입장시킨다. 스케줄러가 100ms마다 부른다.
     * 매진 표시는 MySQL 기준이다. 만료·취소로 재고가 돌아오면 표시를 지우고 입장을 다시 연다.
     *
     * @return 이번에 입장한 인원
     */
    public long admitTick() {
        Set<String> active = store.activeDrops();
        long now = clock.millis();
        long total = 0;
        for (String id : active) {
            long dropId = Long.parseLong(id);
            Optional<DropEvent> drop = drops.findById(dropId);
            if (drop.isEmpty() || drop.get().getStatus() == DropStatus.CLOSED) {
                store.markSoldOut(dropId, true);
                store.deactivate(dropId);
                continue;
            }
            store.markSoldOut(dropId, drop.get().getRemainingQty() == 0);
            long admitted = store.admit(dropId, now, admitPerSecond, maxBurstMs, admissionTtlMs);
            if (admitted < 0) {
                // 키 보관 기간이 지나 meta가 사라진 드롭
                store.deactivate(dropId);
            } else {
                total += admitted;
            }
        }
        return total;
    }

    private QueueView view(long dropId, long memberId, QueueStore.Status status) {
        long now = clock.millis();
        if (status.admittedUntil() > now) {
            return admitted(dropId, memberId, status.admittedUntil(), false);
        }
        if (status.rank() < 0) {
            State state = status.admittedUntil() > 0 ? State.EXPIRED : State.NOT_IN_QUEUE;
            return new QueueView(dropId, state, null, status.size(), null, 0, null, null, false);
        }
        long ahead = status.rank();
        if (status.soldOut()) {
            // 줄에서 빼지 않는다. 재고가 돌아오면 이 자리에서 다시 입장한다.
            return new QueueView(dropId, State.SOLD_OUT, ahead, status.size(), null, maxPollMs, null, null, false);
        }
        long waitMs = ahead * 1000 / admitPerSecond;
        // 입장이 가까울수록 자주, 멀수록 드물게 묻는다. 예상 대기의 절반마다 묻되 [min, max] 안으로 자른다.
        long pollAfter = Math.clamp(waitMs / 2, minPollMs, maxPollMs);
        return new QueueView(dropId, State.WAITING, ahead, status.size(), (waitMs + 999) / 1000, pollAfter,
                null, null, false);
    }

    private QueueView admitted(long dropId, long memberId, long until, boolean bypassed) {
        return new QueueView(dropId, State.ADMITTED, null, 0, null, 0, tokens.issue(dropId, memberId, until),
                Instant.ofEpochMilli(until), bypassed);
    }

    private void load(long dropId) {
        DropEvent drop = drops.findById(dropId)
                .orElseThrow(() -> new CutlineException(ErrorCode.DROP_NOT_FOUND));
        if (drop.getStatus() == DropStatus.CLOSED) {
            throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
        }
        store.load(dropId, drop.getOpensAt().toEpochMilli());
    }

    /**
     * Redis에 닿지 못하면 줄을 건너뛰고 바로 입장 토큰을 준다 (ADR-012). 드롭이 열렸는지, 있는지는 확인하지 않는다.
     * 확인하려면 요청마다 MySQL을 읽어야 하는데, 어차피 주문 경로(MySQL 폴백)가 오픈 시각과 재고로 다시 판정한다.
     */
    private QueueView orBypass(long memberId, long dropId, Supplier<QueueView> call) {
        try {
            return call.get();
        } catch (QueueUnavailableException e) {
            if (!bypassOnOutage) {
                log.warn("queue unavailable: {}", e.getMessage());
                throw new CutlineException(ErrorCode.QUEUE_UNAVAILABLE);
            }
            log.warn("queue unavailable, admitting member={} drop={} without queue: {}", memberId, dropId,
                    e.getMessage());
            return admitted(dropId, memberId, clock.millis() + admissionTtlMs, true);
        }
    }
}
