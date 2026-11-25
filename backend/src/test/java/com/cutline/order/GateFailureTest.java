package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.consistency.ConsistencyChecker;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 관문 장애 (ADR-010). Redis가 멈추거나 데이터를 잃어도 초과판매·한도 초과는 0이어야 하고,
 * 복구 뒤에는 토큰이 MySQL 남은 재고에 맞춰져야 한다.
 *
 * <p>Redis 멈춤은 컨테이너 일시 정지(docker pause)로 만든다. 연결은 살아 있지만 응답이 없는 상태라,
 * 명령마다 시간 초과(500ms)가 난다. 프로세스가 죽는 것보다 앱에 더 불리한 장애다.
 */
@IntegrationTest
class GateFailureTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderAdmissionService admission;

    @Autowired
    StockGate gate;

    @Autowired
    GateResync resync;

    @Autowired
    ConsistencyChecker checker;

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    GenericContainer<?> redisContainer;

    Fixtures fx;
    boolean paused;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
    }

    @AfterEach
    void resumeRedis() throws InterruptedException {
        if (paused) {
            unpauseRedis();
        }
        Thread.sleep(400); // 관문 건너뛰기(테스트 설정 0.3초)가 끝난 뒤 다음 테스트를 시작한다
    }

    @Test
    void Redis가_멈춘_동안_1000명이_재고_100개에_몰려도_정확히_100건만_팔리고_복구_뒤_토큰이_맞춰진다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> buyers = fx.members(1_000);
        admission.place(buyers.getFirst(), dropId, 1, "key-" + buyers.getFirst()); // 관문 키 적재, 토큰 99
        long fallbacksBefore = admission.fallbackCount();

        pauseRedis();
        Outcome outcome = rush(dropId, buyers.subList(1, buyers.size()));

        assertThat(outcome.created).hasValue(99);
        assertThat(outcome.failures).containsOnlyKeys(ErrorCode.SOLD_OUT);
        assertThat(remaining(dropId)).isZero();
        assertThat(admission.fallbackCount() - fallbacksBefore).as("모두 MySQL 경로로 처리됐다").isEqualTo(999);

        unpauseRedis();
        // 시간 초과로 포기한 Lua 호출은 취소되지 않는다. 이미 Redis 소켓에 들어가 있어서, 멈춤이 풀리면 그대로 실행된다.
        // 그 요청들은 이미 MySQL 경로로 처리됐으므로 토큰만 줄어든다(덜 파는 쪽). 이 실행에서는 0까지 줄었다.
        // 어느 쪽이든 재동기화 뒤에는 MySQL 남은 재고(0)와 같아야 한다.
        assertThat(gate.tokens(dropId)).isBetween(0, 99);
        resync.resync();
        assertThat(gate.tokens(dropId)).isZero();
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void Redis가_멈추면_요청마다_시간_초과를_기다리지_않고_잠시_관문을_건너뛴다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> buyers = fx.members(20);
        admission.place(buyers.getFirst(), dropId, 1, "key-1");

        pauseRedis();
        long started = System.nanoTime();
        for (Long buyer : buyers.subList(1, buyers.size())) {
            admission.place(buyer, dropId, 1, "key-1");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // 첫 요청만 Redis 시간 초과(500ms)를 겪고, 이후 0.3초 동안은 Redis를 부르지 않는다.
        // 건너뛰기가 없으면 19 × 500ms ≈ 9.5초가 걸린다.
        assertThat(elapsedMs).isLessThan(19 * 500 / 2);
        assertThat(remaining(dropId)).isEqualTo(80);
    }

    @Test
    void Redis가_돌아오면_관문을_다시_쓰기_전에_장애_동안_MySQL로_팔린_만큼_토큰을_낮춘다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);
        admission.place(fx.member(), dropId, 1, "key-1");
        resync.resync();

        pauseRedis();
        for (int i = 0; i < 3; i++) {
            admission.place(fx.member(), dropId, 1, "key-1"); // 토큰을 쓰지 않고 MySQL에서 팔린다
        }
        unpauseRedis();
        Thread.sleep(400); // 관문 건너뛰기(0.3초)가 끝나기를 기다린다
        assertThat(remaining(dropId)).isEqualTo(6);
        // 토큰은 9에서 시간 초과로 포기한 Lua 1건이 늦게 실행된 만큼만 줄어 있다. 남은 재고(6)보다 많다.
        assertThat(gate.tokens(dropId)).isGreaterThan(6);

        admission.place(fx.member(), dropId, 1, "key-1");

        // 재동기화(5초)를 기다리지 않고, 관문을 쓰기 전에 6으로 낮춘 뒤 1개 통과
        assertThat(remaining(dropId)).isEqualTo(5);
        assertThat(gate.tokens(dropId)).isEqualTo(5);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void Redis가_데이터를_잃으면_MySQL에서_다시_적재하고_이미_산_회원은_MySQL_한도가_막는다() {
        long dropId = fx.openDrop(219_000, 10, 1);
        long buyer = fx.member();
        admission.place(buyer, dropId, 1, "key-1");

        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();

        assertThatCode(() -> admission.place(buyer, dropId, 1, "key-2"), ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        assertThat(gate.tokens(dropId)).as("다시 적재된 9개, MySQL에서 막힌 요청의 토큰은 반환").isEqualTo(9);
        admission.place(fx.member(), dropId, 1, "key-1");
        assertThat(gate.tokens(dropId)).isEqualTo(8);
        assertThat(remaining(dropId)).isEqualTo(8);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 관문_통과_뒤_서버가_죽어_토큰이_사라지면_재동기화가_두_번째_확인에서_돌려준다() {
        long dropId = fx.openDrop(219_000, 10, 1);
        admission.place(fx.member(), dropId, 1, "key-1");
        resync.resync();

        // 관문은 통과했지만 MySQL 트랜잭션 전에 서버가 죽은 상황: 토큰만 줄고 주문은 없다
        assertThat(gate.tryAcquire(dropId, fx.member(), 1).outcome()).isEqualTo(StockGate.Outcome.ADMIT);
        assertThat(gate.tokens(dropId)).isEqualTo(8);

        assertThat(resync.resync().raised()).isZero();
        assertThat(resync.resync().raised()).isEqualTo(1);
        assertThat(gate.tokens(dropId)).isEqualTo(9);
    }

    @Test
    void 만료_중_Redis가_멈춰_토큰을_돌려주지_못해도_만료는_커밋되고_복구_뒤_재동기화가_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 10, 1);
        admission.place(fx.member(), dropId, 1, "key-1");
        resync.resync();

        pauseRedis();
        clock.advance(Duration.ofHours(1));
        assertThat(expiry.expireDue()).isEqualTo(1);
        assertThat(remaining(dropId)).isEqualTo(10);
        unpauseRedis();

        // 반환은 시간 초과로 실패 처리됐다. 소켓에 남은 반환 명령이 복구 직후 실행되면 이미 10, 아니면 9(덜 파는 쪽)이다.
        assertThat(gate.tokens(dropId)).isBetween(9, 10);
        resync.resync();
        resync.resync();
        assertThat(gate.tokens(dropId)).isEqualTo(10);
        assertThat(checker.check()).isEmpty();
    }

    private record Outcome(AtomicInteger created, Map<ErrorCode, AtomicInteger> failures) {
    }

    private Outcome rush(long dropId, List<Long> buyers) throws InterruptedException {
        Outcome outcome = new Outcome(new AtomicInteger(), new ConcurrentHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        for (Long buyer : buyers) {
            pool.submit(() -> {
                start.await();
                try {
                    admission.place(buyer, dropId, 1, "key-" + buyer);
                    outcome.created().incrementAndGet();
                } catch (CutlineException e) {
                    outcome.failures().computeIfAbsent(e.code(), k -> new AtomicInteger()).incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        return outcome;
    }

    private static void assertThatCode(Runnable call, ErrorCode expected) {
        try {
            call.run();
        } catch (CutlineException e) {
            assertThat(e.code()).isEqualTo(expected);
            return;
        }
        throw new AssertionError("expected " + expected);
    }

    private void pauseRedis() {
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;
    }

    private void unpauseRedis() {
        redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = false;
    }

    private int remaining(long dropId) {
        return jdbc.queryForObject("SELECT remaining_qty FROM drop_event WHERE id = ?", Integer.class, dropId);
    }
}
