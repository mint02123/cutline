package com.cutline.queue;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.drop.DropEvent;
import com.cutline.drop.DropRepository;
import com.cutline.drop.DropStatus;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 줄 서기와 순번 조회 (설계 문서 2-3, 6일차). 입장(앞에서 N명 꺼내 입장 토큰 발급)은 7일차.
 *
 * <p>두 요청 모두 MySQL에 닿지 않는다. 드롭마다 처음 한 번만 오픈 시각을 MySQL에서 읽어 Redis에 적재하고,
 * 그 뒤로는 오픈 전 거절과 매진 거절까지 Redis 안에서 끝난다. 오픈 순간 몰리는 요청이 커넥션 풀 줄에 서지 않게 하기 위해서다.
 *
 * <p>Redis에 닿지 못하면 503 {@code QUEUE_UNAVAILABLE}로 돌려보낸다. 줄 자체가 Redis에 있으므로 MySQL로 대신할 수 없다.
 * Redis 장애 중에 판매를 어떻게 이어 갈지(입장 토큰 없이 주문을 받을지)는 입장 토큰 검증과 함께 7일차에 정한다.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    public enum State { WAITING, NOT_IN_QUEUE }

    /**
     * @param ahead                내 앞에 선 사람 수. 줄에 없으면 null
     * @param lineLength           지금 줄에 선 전체 인원
     * @param estimatedWaitSeconds 입장 속도 설정값으로 계산한 예상 대기 시간. 줄에 없으면 null
     * @param pollAfterMs          다음 순번 조회까지 기다릴 시간. 클라이언트는 이 값을 따른다
     */
    public record QueueView(long dropId, State state, Long ahead, long lineLength, Long estimatedWaitSeconds,
                            long pollAfterMs) {
    }

    private final QueueStore store;
    private final DropRepository drops;
    private final Clock clock;
    private final int admitPerSecond;
    private final long minPollMs;
    private final long maxPollMs;

    QueueService(QueueStore store, DropRepository drops, Clock clock,
                 @Value("${cutline.queue.admit-per-second:200}") int admitPerSecond,
                 @Value("${cutline.queue.poll.min-ms:1000}") long minPollMs,
                 @Value("${cutline.queue.poll.max-ms:5000}") long maxPollMs) {
        this.store = store;
        this.drops = drops;
        this.clock = clock;
        this.admitPerSecond = admitPerSecond;
        this.minPollMs = minPollMs;
        this.maxPollMs = maxPollMs;
    }

    /** 줄에 선다. 이미 서 있으면 기존 자리를 돌려준다 (멱등). */
    public QueueView join(long memberId, long dropId) {
        QueueStore.Join join = guard(() -> store.join(dropId, memberId, clock.millis()));
        if (join.outcome() == QueueStore.JoinOutcome.NOT_LOADED) {
            load(dropId);
            join = guard(() -> store.join(dropId, memberId, clock.millis()));
        }
        return switch (join.outcome()) {
            case IN_LINE -> {
                // 방금 선 사람은 줄의 맨 뒤이므로 줄 길이 = 내 앞 인원 + 1
                QueueStore.Status status = join.joinedNow()
                        ? new QueueStore.Status(true, join.rank(), join.rank() + 1)
                        : guard(() -> store.status(dropId, memberId));
                yield view(dropId, status);
            }
            case SOLD_OUT -> throw new CutlineException(ErrorCode.SOLD_OUT);
            case NOT_OPEN -> throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
            case NOT_LOADED -> throw new IllegalStateException("queue meta not loaded for drop " + dropId);
        };
    }

    /** 내 순번. 순번 조회는 폴링되므로 줄에 없어도 200으로 답한다. */
    public QueueView status(long memberId, long dropId) {
        QueueStore.Status status = guard(() -> store.status(dropId, memberId));
        if (!status.loaded()) {
            // 아무도 줄을 서지 않은 드롭이거나 없는 드롭이다. 없는 드롭이면 여기서 404가 난다.
            load(dropId);
        }
        return view(dropId, status);
    }

    private QueueView view(long dropId, QueueStore.Status status) {
        if (status.rank() < 0) {
            return new QueueView(dropId, State.NOT_IN_QUEUE, null, status.size(), null, maxPollMs);
        }
        long ahead = status.rank();
        long waitMs = ahead * 1000 / admitPerSecond;
        // 입장이 가까울수록 자주, 멀수록 드물게 묻는다. 예상 대기의 절반마다 묻되 [min, max] 안으로 자른다.
        long pollAfter = Math.clamp(waitMs / 2, minPollMs, maxPollMs);
        return new QueueView(dropId, State.WAITING, ahead, status.size(), (waitMs + 999) / 1000, pollAfter);
    }

    private void load(long dropId) {
        DropEvent drop = drops.findById(dropId)
                .orElseThrow(() -> new CutlineException(ErrorCode.DROP_NOT_FOUND));
        if (drop.getStatus() == DropStatus.CLOSED) {
            throw new CutlineException(ErrorCode.DROP_NOT_OPEN);
        }
        guard(() -> {
            store.load(dropId, drop.getOpensAt().toEpochMilli());
            return null;
        });
    }

    private static <T> T guard(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (QueueUnavailableException e) {
            log.warn("queue unavailable: {}", e.getMessage());
            throw new CutlineException(ErrorCode.QUEUE_UNAVAILABLE);
        }
    }
}
