package com.cutline.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;

/** 줄 서기와 순번 조회 (설계 문서 2-3, 6일차). */
@IntegrationTest
class QueueApiTest {

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

    Fixtures fx;
    boolean paused;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
    }

    @AfterEach
    void resumeRedis() {
        if (paused) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
            paused = false;
        }
    }

    @Test
    void 도착한_순서대로_순번을_받고_내_앞_인원과_줄_길이를_알려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(3);

        join(members.get(0), dropId).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.ahead").value(0))
                .andExpect(jsonPath("$.lineLength").value(1));
        join(members.get(1), dropId).andExpect(jsonPath("$.ahead").value(1));
        join(members.get(2), dropId).andExpect(jsonPath("$.ahead").value(2))
                .andExpect(jsonPath("$.lineLength").value(3));

        me(members.get(1), dropId).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.ahead").value(1))
                .andExpect(jsonPath("$.lineLength").value(3));
    }

    @Test
    void 이미_줄에_선_회원이_다시_누르면_새_자리를_만들지_않고_기존_자리를_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);
        join(members.get(0), dropId);
        join(members.get(1), dropId);

        join(members.get(0), dropId).andExpect(status().isOk())
                .andExpect(jsonPath("$.ahead").value(0))
                .andExpect(jsonPath("$.lineLength").value(2));
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isEqualTo(2);
    }

    @Test
    void 같은_회원이_여러_탭에서_100번_동시에_눌러도_자리는_하나다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        long member = fx.member();

        Set<Integer> aheads = runConcurrently(100, i -> member, dropId);

        assertThat(aheads).containsExactly(0);
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isEqualTo(1);
    }

    @Test
    void 구매자_1000명이_동시에_줄을_서면_순번이_겹치지_않고_0부터_999까지_하나씩_돌아간다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(1_000);

        Set<Integer> aheads = runConcurrently(members.size(), members::get, dropId);

        assertThat(aheads).hasSize(1_000);
        assertThat(aheads).allMatch(a -> a >= 0 && a < 1_000);
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isEqualTo(1_000);
        assertThat(redis.opsForHash().get(QueueStore.metaKey(dropId), "seq")).isEqualTo("1000");
    }

    @Test
    void 오픈_전에는_줄에_세우지_않고_오픈_시각이_되면_세운다() throws Exception {
        long dropId = fx.drop(fx.product(fx.seller(), 219_000), 100, 1, "SCHEDULED",
                TestClockConfig.BASE_TIME.plusSeconds(10));
        long member = fx.member();

        join(member, dropId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_NOT_OPEN"));
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isZero();

        // 드롭 상태(SCHEDULED)가 아니라 오픈 시각으로 판정한다. 오픈 스케줄러가 늦어도 줄은 제때 열린다.
        clock.advance(Duration.ofSeconds(10));
        join(member, dropId).andExpect(status().isOk()).andExpect(jsonPath("$.ahead").value(0));
    }

    @Test
    void 매진_표시가_된_드롭은_새로_줄에_세우지_않지만_이미_선_사람의_자리는_돌려준다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);
        join(members.get(0), dropId);

        // 7일차 입장 스케줄러가 매진 때 남길 표시
        redis.opsForHash().put(QueueStore.metaKey(dropId), "soldOut", "1");

        join(members.get(1), dropId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLD_OUT"));
        join(members.get(0), dropId).andExpect(status().isOk()).andExpect(jsonPath("$.ahead").value(0));
    }

    @Test
    void 없는_드롭은_404_닫힌_드롭은_409로_거절한다() throws Exception {
        long member = fx.member();
        long closed = fx.drop(fx.product(fx.seller(), 219_000), 100, 1, "CLOSED", TestClockConfig.BASE_TIME);

        join(member, 999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
        me(member, 999_999L).andExpect(status().isNotFound());
        join(member, closed).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_NOT_OPEN"));
    }

    @Test
    void 줄에_서지_않은_회원의_순번_조회는_200_NOT_IN_QUEUE다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);

        me(members.get(0), dropId).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_IN_QUEUE"))
                .andExpect(jsonPath("$.ahead").doesNotExist())
                .andExpect(jsonPath("$.lineLength").value(0));

        join(members.get(1), dropId);
        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("NOT_IN_QUEUE"))
                .andExpect(jsonPath("$.lineLength").value(1));
    }

    @Test
    void 조회_간격은_입장이_가까우면_짧고_멀면_길다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(3_000);
        // 앞의 2,999명은 Redis에 바로 넣는다 (API로 3,000번 부르면 테스트가 느려진다)
        join(members.getFirst(), dropId);
        for (int i = 1; i < members.size() - 1; i++) {
            redis.opsForZSet().add(QueueStore.queueKey(dropId), String.valueOf(members.get(i)), i + 1);
        }
        redis.opsForHash().put(QueueStore.metaKey(dropId), "seq", String.valueOf(members.size() - 1));

        // 입장 속도 200명/초 기본값: 맨 앞은 1초, 2,999명 뒤는 예상 15초 → 절반 7.5초를 5초로 자름
        me(members.getFirst(), dropId).andExpect(jsonPath("$.pollAfterMs").value(1000))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(0));
        join(members.getLast(), dropId).andExpect(jsonPath("$.ahead").value(2_999))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(15))
                .andExpect(jsonPath("$.pollAfterMs").value(5000));
    }

    @Test
    void Redis에_닿지_못하면_503_QUEUE_UNAVAILABLE로_돌려보낸다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        long member = fx.member();
        join(member, dropId);

        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;

        join(member, dropId).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUEUE_UNAVAILABLE"));
        me(member, dropId).andExpect(status().isServiceUnavailable());
    }

    private Set<Integer> runConcurrently(int requests, java.util.function.IntFunction<Long> memberOf,
                                         long dropId) throws Exception {
        Set<Integer> aheads = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < requests; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String body = join(memberOf.apply(index), dropId).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    aheads.add(Integer.valueOf(body.replaceAll(".*\"ahead\":(\\d+).*", "$1")));
                } catch (Throwable e) {
                    errors.add(e);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        return aheads;
    }

    private ResultActions join(long memberId, long dropId) throws Exception {
        return mvc.perform(post("/api/drops/{id}/queue", dropId).header("X-Member-Id", memberId));
    }

    private ResultActions me(long memberId, long dropId) throws Exception {
        return mvc.perform(get("/api/drops/{id}/queue/me", dropId).header("X-Member-Id", memberId));
    }
}
