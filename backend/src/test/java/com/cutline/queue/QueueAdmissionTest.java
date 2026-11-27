package com.cutline.queue;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 입장 스케줄러, 입장 토큰, 매진 표시, Redis 장애 시 우회 (설계 문서 2-3, 7일차).
 * 이 클래스만 주문 API의 입장 토큰 확인을 켠다.
 */
@IntegrationTest
@TestPropertySource(properties = "cutline.queue.enforce-admission=true")
class QueueAdmissionTest {

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
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
            paused = false;
            Thread.sleep(400); // 주문 관문 건너뛰기(테스트 설정 0.3초)가 끝난 뒤 다음 테스트를 시작한다
        }
    }

    @Test
    void 입장은_흐른_시간만큼_줄_앞에서부터_이루어지고_입장한_회원은_토큰을_받는다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(30);
        for (Long m : members) {
            join(m, dropId);
        }

        assertThat(queue.admitTick()).isZero(); // 첫 주기는 기준 시각만 잡는다
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(20); // 초당 200명 × 0.1초

        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("ADMITTED"))
                .andExpect(jsonPath("$.admissionToken").isString())
                .andExpect(jsonPath("$.pollAfterMs").value(0));
        me(members.get(19), dropId).andExpect(jsonPath("$.state").value("ADMITTED"));
        me(members.get(20), dropId).andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.ahead").value(0))
                .andExpect(jsonPath("$.lineLength").value(10));
        // 입장한 회원이 다시 줄 서기를 눌러도 줄 뒤로 가지 않고 입장 상태 그대로다
        join(members.get(0), dropId).andExpect(jsonPath("$.state").value("ADMITTED"));
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isEqualTo(10);
    }

    @Test
    void 오픈_전에는_입장시키지_않는다() throws Exception {
        long dropId = fx.drop(fx.product(fx.seller(), 219_000), 100, 1, "SCHEDULED",
                TestClockConfig.BASE_TIME.plusSeconds(10));
        long member = fx.member();
        me(member, dropId); // meta 적재
        redis.opsForZSet().add(QueueStore.queueKey(dropId), String.valueOf(member), 1);

        clock.advance(Duration.ofSeconds(5));
        assertThat(queue.admitTick()).isZero();
        clock.advance(Duration.ofSeconds(5));
        assertThat(queue.admitTick()).isZero();
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(1);
    }

    @Test
    void 스케줄러가_오래_멈췄다_돌아와도_최대_1초치만_입장시킨다() throws Exception {
        long dropId = fx.openDrop(219_000, 1_000, 1);
        lineUp(dropId, 1_000);
        queue.admitTick();

        clock.advance(Duration.ofSeconds(10));
        assertThat(queue.admitTick()).isEqualTo(200);
    }

    @Test
    void 서버_2대의_스케줄러가_함께_돌아도_전체_입장_속도는_그대로이고_같은_사람을_두_번_꺼내지_않는다() throws Exception {
        long dropId = fx.openDrop(219_000, 1_000, 1);
        lineUp(dropId, 1_000);
        queue.admitTick();

        long total = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int tick = 0; tick < 20; tick++) {
            clock.advance(Duration.ofMillis(50));
            CountDownLatch start = new CountDownLatch(1);
            Future<Long> a = pool.submit(() -> { start.await(); return queue.admitTick(); });
            Future<Long> b = pool.submit(() -> { start.await(); return queue.admitTick(); });
            start.countDown();
            total += a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 1초 동안 초당 200명
        assertThat(total).isEqualTo(200);
        assertThat(redis.opsForHash().size(QueueStore.admittedKey(dropId))).isEqualTo(200);
        assertThat(redis.opsForZSet().size(QueueStore.queueKey(dropId))).isEqualTo(800);
    }

    @Test
    void 주문하지_않은_입장자와_새_입장_인원의_합은_남은_재고를_넘지_않는다() throws Exception {
        long dropId = fx.openDrop(219_000, 5, 1);
        List<Long> members = fx.members(30);
        for (Long m : members) {
            join(m, dropId);
        }
        queue.admitTick();
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isEqualTo(5); // 속도로는 20명이지만 재고가 5개
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isZero();

        // 입장한 사람이 주문하면 재고와 주문하지 않은 입장자가 함께 줄어 더 들이지 않는다
        String body = me(members.get(0), dropId).andReturn().getResponse().getContentAsString();
        order(members.get(0), dropId, JsonPath.read(body, "$.admissionToken")).andExpect(status().isCreated());
        awaitConsumed(dropId, members.get(0));
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isZero();

        // 주문하지 않은 4명의 입장 시간이 지나면 그 몫을 다음 사람에게 연다
        clock.advance(Duration.ofMinutes(2));
        assertThat(queue.admitTick()).isEqualTo(4);
        me(members.get(5), dropId).andExpect(jsonPath("$.state").value("ADMITTED"));
    }

    @Test
    void 입장_토큰이_있어야_주문할_수_있다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);
        String token = admitted(members.get(0), dropId);

        order(members.get(0), dropId, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
        // 다른 회원의 토큰
        order(members.get(1), dropId, token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
        // 다른 드롭의 토큰
        long otherDrop = fx.openDrop(219_000, 100, 1);
        order(members.get(0), otherDrop, token).andExpect(status().isForbidden());
        // 위조
        order(members.get(0), dropId, token.substring(0, token.length() - 2) + "AA").andExpect(status().isForbidden());

        order(members.get(0), dropId, token).andExpect(status().isCreated());
        // 같은 토큰을 다시 써도 1인 한도는 MySQL이 막는다
        order(members.get(0), dropId, token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    void 입장_시간이_지나면_주문할_수_없고_다시_줄을_서면_맨_뒤에_선다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(2);
        String token = admitted(members.get(0), dropId);
        join(members.get(1), dropId);

        clock.advance(Duration.ofMinutes(2));
        order(members.get(0), dropId, token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMISSION_EXPIRED"));
        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("EXPIRED"));

        join(members.get(0), dropId).andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.ahead").value(1));
    }

    @Test
    void 매진되면_줄에_남은_사람은_품절을_보고_재고가_돌아오면_그_자리에서_다시_입장한다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        List<Long> members = fx.members(3);
        join(members.get(0), dropId);
        join(members.get(1), dropId);
        queue.admitTick();

        jdbc.update("UPDATE drop_event SET remaining_qty = 0 WHERE id = ?", dropId);
        clock.advance(Duration.ofMillis(100));
        assertThat(queue.admitTick()).isZero();

        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("SOLD_OUT"))
                .andExpect(jsonPath("$.ahead").value(0));
        me(members.get(1), dropId).andExpect(jsonPath("$.state").value("SOLD_OUT"));
        join(members.get(2), dropId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLD_OUT"));

        // 매진이 30초 이어진 뒤 만료·취소로 재고가 돌아옴. 매진 동안 흐른 시간만큼 한꺼번에 들이지 않는다.
        clock.advance(Duration.ofSeconds(30));
        assertThat(queue.admitTick()).isZero();
        jdbc.update("UPDATE drop_event SET remaining_qty = 1 WHERE id = ?", dropId);
        assertThat(queue.admitTick()).isZero();
        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("WAITING"));
        clock.advance(Duration.ofMillis(5));
        assertThat(queue.admitTick()).isEqualTo(1);
        me(members.get(0), dropId).andExpect(jsonPath("$.state").value("ADMITTED"));
    }

    @Test
    void 닫힌_드롭은_입장_대상에서_빠지고_줄에_남은_사람은_품절을_본다() throws Exception {
        long dropId = fx.openDrop(219_000, 100, 1);
        long member = fx.member();
        join(member, dropId);

        jdbc.update("UPDATE drop_event SET status = 'CLOSED' WHERE id = ?", dropId);
        queue.admitTick();

        assertThat(redis.opsForSet().isMember(QueueStore.ACTIVE_KEY, String.valueOf(dropId))).isFalse();
        me(member, dropId).andExpect(jsonPath("$.state").value("SOLD_OUT"));
    }

    @Test
    void Redis가_멈추면_줄을_건너뛴_토큰으로_MySQL_경로에서_주문하고_정합성은_그대로다() throws Exception {
        long dropId = fx.openDrop(219_000, 5, 1);
        List<Long> members = fx.members(8);

        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;

        int created = 0;
        int soldOut = 0;
        for (Long m : members) {
            String body = join(m, dropId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.bypassed").value(true))
                    .andReturn().getResponse().getContentAsString();
            int code = order(m, dropId, JsonPath.read(body, "$.admissionToken")).andReturn().getResponse().getStatus();
            if (code == 201) {
                created++;
            } else if (code == 409) {
                soldOut++;
            }
        }

        assertThat(created).isEqualTo(5);
        assertThat(soldOut).isEqualTo(3);
        resumeRedis();
        assertThat(checker.check()).isEmpty();
    }

    private void awaitConsumed(long dropId, long member) throws InterruptedException {
        for (int i = 0; i < 100 && redis.opsForZSet().score(QueueStore.pendingKey(dropId), String.valueOf(member)) != null; i++) {
            Thread.sleep(20);
        }
        assertThat(redis.opsForZSet().score(QueueStore.pendingKey(dropId), String.valueOf(member))).isNull();
    }

    /** API로 1,000번 부르지 않고 줄을 바로 채운다. meta는 API로 적재한다. */
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
        var request = post("/api/drops/{id}/orders", dropId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1}");
        if (token != null) {
            request.header(AdmissionGuard.HEADER, token);
        }
        return mvc.perform(request);
    }

    private ResultActions join(long memberId, long dropId) throws Exception {
        return mvc.perform(post("/api/drops/{id}/queue", dropId).header("X-Member-Id", memberId));
    }

    private ResultActions me(long memberId, long dropId) throws Exception {
        return mvc.perform(get("/api/drops/{id}/queue/me", dropId).header("X-Member-Id", memberId));
    }
}
