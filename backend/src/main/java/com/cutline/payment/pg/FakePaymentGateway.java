package com.cutline.payment.pg;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 장애를 마음대로 주입할 수 있는 가짜 PG.
 *
 * <p>운영 흉내(로컬, 부하 테스트)는 설정값의 확률로, 테스트는 {@link #script}로 호출마다 결과를 정한다.
 * <ul>
 *   <li>{@code DECLINE}: 거절 (카드 한도 초과 등)</li>
 *   <li>{@code LOSE_RESPONSE}: 승인은 했는데 응답이 사라짐. 돈은 나갔지만 우리 서버는 모른다</li>
 *   <li>{@code TIMEOUT}: 요청이 PG에 닿지 않음. 돈은 안 나갔지만 우리 서버는 역시 모른다</li>
 * </ul>
 * 승인은 주문 ID 기준으로 멱등이다. 실제로 돈이 나간 횟수는 {@link #chargeCount}로 확인한다.
 */
@Component
@ConditionalOnProperty(name = "cutline.pg.provider", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    public enum Fault {
        NONE,
        DECLINE,
        LOSE_RESPONSE,
        TIMEOUT
    }

    private record Charge(String paymentKey, long amount, boolean canceled) {
    }

    private final Map<Long, Charge> charges = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> chargeCounts = new ConcurrentHashMap<>();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final Queue<Fault> scripted = new ConcurrentLinkedQueue<>();

    private final double declineRate;
    private final double lostResponseRate;
    private final double timeoutRate;
    private volatile Duration delay;
    private volatile LongConsumer beforeApprove = orderId -> { };
    private volatile boolean cancelFails;
    private volatile boolean inquiryFails;

    public FakePaymentGateway(@Value("${cutline.pg.fake.delay-ms:0}") long delayMs,
                              @Value("${cutline.pg.fake.decline-rate:0}") double declineRate,
                              @Value("${cutline.pg.fake.lost-response-rate:0}") double lostResponseRate,
                              @Value("${cutline.pg.fake.timeout-rate:0}") double timeoutRate) {
        this.delay = Duration.ofMillis(delayMs);
        this.declineRate = declineRate;
        this.lostResponseRate = lostResponseRate;
        this.timeoutRate = timeoutRate;
    }

    @Override
    public PgResult approve(String paymentKey, long orderId, long amount) {
        pause();
        beforeApprove.accept(orderId);
        Fault fault = nextFault();
        return switch (fault) {
            case DECLINE -> PgResult.of(PgResult.Status.DECLINED, paymentKey, amount);
            case TIMEOUT -> throw new PgTimeoutException("fake pg: request lost before approval, order " + orderId);
            case LOSE_RESPONSE -> {
                charge(paymentKey, orderId, amount);
                throw new PgTimeoutException("fake pg: approved but response lost, order " + orderId);
            }
            case NONE -> charge(paymentKey, orderId, amount);
        };
    }

    @Override
    public PgResult inquire(long orderId) {
        pause();
        if (inquiryFails) {
            throw new PgTimeoutException("fake pg: inquiry timed out, order " + orderId);
        }
        Charge charge = charges.get(orderId);
        if (charge == null) {
            return PgResult.of(PgResult.Status.NOT_FOUND, null, 0);
        }
        return PgResult.of(charge.canceled() ? PgResult.Status.CANCELED : PgResult.Status.APPROVED,
                charge.paymentKey(), charge.amount());
    }

    @Override
    public void cancel(String paymentKey, long orderId, String reason) {
        pause();
        cancelCalls.incrementAndGet();
        if (cancelFails) {
            throw new PgTimeoutException("fake pg: cancel timed out, order " + orderId);
        }
        charges.computeIfPresent(orderId, (id, c) -> new Charge(c.paymentKey(), c.amount(), true));
    }

    /** 같은 주문의 두 번째 승인은 새로 돈을 빼지 않고 처음 결과를 돌려준다. 취소된 건은 거절한다. */
    private PgResult charge(String paymentKey, long orderId, long amount) {
        Charge charge = charges.computeIfAbsent(orderId, id -> {
            chargeCounts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            return new Charge(paymentKey, amount, false);
        });
        if (charge.canceled()) {
            return PgResult.of(PgResult.Status.DECLINED, charge.paymentKey(), charge.amount());
        }
        return PgResult.of(PgResult.Status.APPROVED, charge.paymentKey(), charge.amount());
    }

    private Fault nextFault() {
        Fault next = scripted.poll();
        if (next != null) {
            return next;
        }
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < declineRate) {
            return Fault.DECLINE;
        }
        if (r < declineRate + lostResponseRate) {
            return Fault.LOSE_RESPONSE;
        }
        if (r < declineRate + lostResponseRate + timeoutRate) {
            return Fault.TIMEOUT;
        }
        return Fault.NONE;
    }

    private void pause() {
        Duration d = delay;
        if (!d.isZero()) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PgTimeoutException("fake pg: interrupted");
            }
        }
    }

    // ---- 테스트·장애 주입용 조작 ----

    /** 다음 승인 호출들의 결과를 순서대로 정한다. 다 쓰면 설정 확률로 돌아간다. */
    public void script(Fault... faults) {
        scripted.addAll(java.util.List.of(faults));
    }

    public void setDelay(Duration delay) {
        this.delay = delay;
    }

    /** 승인 처리 직전에 끼워 넣을 동작. "PG 호출 도중 만료" 같은 경합을 순서대로 재현할 때 쓴다. */
    public void beforeApprove(LongConsumer hook) {
        this.beforeApprove = hook;
    }

    public void failCancels(boolean fail) {
        this.cancelFails = fail;
    }

    public void failInquiries(boolean fail) {
        this.inquiryFails = fail;
    }

    /** 이 주문으로 실제 돈이 빠져나간 횟수. 멱등성이 지켜지면 0 또는 1이다. */
    public int chargeCount(long orderId) {
        AtomicInteger count = chargeCounts.get(orderId);
        return count == null ? 0 : count.get();
    }

    /** 취소되지 않고 남아 있는 승인 건수. */
    public long activeCharges() {
        return charges.values().stream().filter(c -> !c.canceled()).count();
    }

    public boolean isCanceled(long orderId) {
        Charge charge = charges.get(orderId);
        return charge != null && charge.canceled();
    }

    public int cancelCalls() {
        return cancelCalls.get();
    }

    public void reset() {
        charges.clear();
        chargeCounts.clear();
        scripted.clear();
        cancelCalls.set(0);
        delay = Duration.ZERO;
        beforeApprove = orderId -> { };
        cancelFails = false;
        inquiryFails = false;
    }
}
