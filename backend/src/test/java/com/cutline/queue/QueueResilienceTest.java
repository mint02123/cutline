package com.cutline.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.consistency.ConsistencyChecker;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;

/**
 * 대기열 장애·중복 시나리오 (설계 문서 2-3, 8일차). 끝나면 정합성 체커로 위반 0을 확인한다.
 * 여러 탭, 다른 회원 토큰, 스케줄러 2개는 {@link QueueApiTest}, {@link QueueAdmissionTest}에 있다.
 */
@IntegrationTest
@TestPropertySource(properties = "cutline.queue.enforce-admission=true")
class QueueResilienceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    GenericContainer<?> redisContainer;

    @Autowired
    QueueService queue;

    @Autowired
    QueueAdmitter admitter;

    @Autowired
    ConsistencyChecker checker;

    Fixtures fx;
    boolean paused;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        redis.delete(QueueStore.ACTIVE_KEY);
    }

    @AfterEach
    void resumeRedis() throws InterruptedException {
        if (paused) {
            unpauseRedis();
            Thread.sleep(400); // 주문 관문 건너뛰기(테스트 설정 0.3초)가 끝난 뒤 다음 테스트를 시작한다
        }
    }

    @Test
    void 입장은_도착한_순서대로_이루어진다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(50);
        for (Long m : members) {
            join(m, dropId);
        }
        queue.admitTick();
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(20);

        Set<String> admitted = redis.<String, String>opsForHash().keys(QueueStore.admittedKey(dropId));
        assertThat(admitted).isEqualTo(members.subList(0, 20).stream().map(String::valueOf).collect(Collectors.toSet()));
    }

    @Test
    void 줄_서기와_입장이_동시에_일어나도_모두_정확히_한_번씩_입장한다() throws Exception {
        long dropId = fx.openDrop(219_000, 1_000, 1);
        List<Long> members = fx.members(300);
        join(members.getFirst(), dropId); // meta 적재
        queue.admitTick();

        AtomicBoolean joining = new AtomicBoolean(true);
        AtomicLong admittedTotal = new AtomicLong(queue.admitTick());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        Thread ticker = Thread.ofPlatform().start(() -> {
            while (joining.get()) {
                clock.advance(Duration.ofMillis(10));
                admittedTotal.addAndGet(queue.admitTick());
            }
        });
        CountDownLatch start = new CountDownLatch(1);
        for (Long m : members.subList(1, members.size())) {
            pool.submit(() -> {
                try {
                    start.await();
                    join(m, dropId).andExpect(status().isOk());
                } catch (Throwable e) {
                    errors.add(e);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        joining.set(false);
        ticker.join();
        while (redis.opsForZSet().size(QueueStore.queueKey(dropId)) > 0) {
            clock.advance(Duration.ofMillis(100));
            admittedTotal.addAndGet(queue.admitTick());
        }

        assertThat(errors).isEmpty();
        // 같은 사람을 두 번 꺼냈다면 입장 합계가 입장 기록 수보다 크다
        assertThat(admittedTotal.get()).isEqualTo(300);
        assertThat(redis.opsForHash().size(QueueStore.admittedKey(dropId))).isEqualTo(300);
    }

    @Test
    void 같은_입장_토큰으로_동시에_50번_주문해도_1인_한도만큼만_팔린다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 2);
        long member = fx.member();
        String token = admitted(member, dropId);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                start.await();
                int code = order(member, dropId, token).andReturn().getResponse().getStatus();
                (code == 201 ? created : limited).incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(created.get()).isEqualTo(2);
        assertThat(limited.get()).isEqualTo(48);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void 다른_서버의_시계가_늦어도_입장이_몰리거나_멈추지_않는다() throws Exception {
        long dropId = fx.openDrop(219_000, 1_000, 1);
        lineUp(dropId, 500);
        queue.admitTick(); // 기준 시각 T

        clock.set(TestClockConfig.BASE_TIME.minusMillis(500)); // 0.5초 늦은 서버
        assertThat(queue.admitTick()).isZero();
        clock.set(TestClockConfig.BASE_TIME.plusMillis(100)); // 맞는 서버
        assertThat(queue.admitTick()).isEqualTo(20);
    }

    @Test
    void Redis가_데이터를_잃어도_이미_받은_입장_토큰으로_주문하고_나머지는_다시_줄을_선다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(3);
        String token = admitted(members.get(0), dropId);
        join(members.get(1), dropId);
        join(members.get(2), dropId);

        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 서명 토큰이라 Redis 기록 없이 검증된다. 관문 토큰은 MySQL에서 다시 적재된다.
        order(members.get(0), dropId, token).andExpect(status().isCreated());
        // 줄은 사라졌다. 다시 서면 처음부터 순번을 받는다.
        me(members.get(2), dropId).andExpect(jsonPath("$.state").value("NOT_IN_QUEUE"));
        join(members.get(2), dropId).andExpect(jsonPath("$.ahead").value(0));
        join(members.get(1), dropId).andExpect(jsonPath("$.ahead").value(1));
        // meta를 다시 적재하면서 스케줄러 대상에도 다시 들어간다
        queue.admitTick();
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(2);
        assertThat(checker.check()).isEmpty();
    }

    @Test
    void Redis가_멈춘_동안_스케줄러는_예외_없이_넘어가고_복구_뒤_다시_입장시킨다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);
        join(members.get(0), dropId);
        queue.admitTick();

        pauseRedis();
        assertThatCode(admitter::run).doesNotThrowAnyException();
        // 장애 중에 줄을 건너뛰어 받은 토큰
        String bypassToken = JsonPath.read(join(members.get(1), dropId)
                .andExpect(jsonPath("$.bypassed").value(true))
                .andReturn().getResponse().getContentAsString(), "$.admissionToken");
        unpauseRedis();
        Thread.sleep(400);

        // 시간 초과로 포기한 줄 서기 명령도 Redis가 풀리면 뒤늦게 실행된다. 우회 토큰을 받은 회원이 줄에도 서 있게 된다.
        // 이 회원은 나중에 한 번 더 입장할 수 있지만, 살 수 있는 양은 MySQL 1인 한도가 정한다 (입장 몫 하나를 쓸 뿐이다).
        assertThat(redis.opsForZSet().rank(QueueStore.queueKey(dropId), String.valueOf(members.get(1)))).isNotNull();

        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(2);
        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("ADMITTED"));
        // 우회 토큰은 Redis가 돌아온 뒤에도 만료 전까지 유효하다. 주문은 다시 관문을 거친다.
        order(members.get(1), dropId, bypassToken).andExpect(status().isCreated());
        assertThat(checker.check()).isEmpty();
    }

    private void pauseRedis() {
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;
    }

    private void unpauseRedis() {
        redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = false;
    }

    /** API로 수백 번 부르지 않고 줄을 바로 채운다. meta는 API로 적재한다. */
    private void lineUp(long dropId, int count) throws Exception {
        List<Long> members = fx.members(count);
        join(members.getFirst(), dropId);
        for (int i = 1; i < count; i++) {
            redis.opsForZSet().add(QueueStore.queueKey(dropId), String.valueOf(members.get(i)), i + 1);
        }
        redis.opsForHash().put(QueueStore.metaKey(dropId), "seq", String.valueOf(count));
    }

    /** 혼자 줄을 서서 입장하고 토큰을 받는다. */
    private String admitted(long member, long dropId) throws Exception {
        join(member, dropId);
        queue.admitTick();
        clock.advance(Duration.ofMillis(100));
        queue.admitTick();
        String body = me(member, dropId).andExpect(jsonPath("$.state").value("ADMITTED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.admissionToken");
    }

    private ResultActions order(long memberId, long dropId, String token) throws Exception {
        return mvc.perform(post("/api/drops/{id}/orders", dropId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(AdmissionGuard.HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1}"));
    }

    private ResultActions join(long memberId, long dropId) throws Exception {
        return mvc.perform(post("/api/drops/{id}/queue", dropId).header("X-Member-Id", memberId));
    }

    private ResultActions me(long memberId, long dropId) throws Exception {
        return mvc.perform(get("/api/drops/{id}/queue/me", dropId).header("X-Member-Id", memberId));
    }
}
