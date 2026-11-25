package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.MutableClock;
import com.cutline.support.MySqlTestContainer;
import com.cutline.support.RedisTestContainer;
import com.cutline.support.TestClockConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 장애 중 MySQL 경로의 동시 진입 제한 (ADR-010). 자리가 없으면 커넥션과 스레드를 잡지 않고 503으로 돌려보낸다.
 */
@SpringBootTest(properties = {
        "cutline.scheduling.enabled=false",
        "cutline.gate.fallback.max-concurrent=1",
        "cutline.gate.fallback.wait=PT0.2S",
        "cutline.gate.fallback.skip-gate-for=PT30S"})
@Import({MySqlTestContainer.class, RedisTestContainer.class, TestClockConfig.class})
class GateFallbackLimitTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderAdmissionService admission;

    @Autowired
    GenericContainer<?> redisContainer;

    @Test
    void 자리가_없으면_기다리다_503_TOO_BUSY() throws Exception {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        Fixtures fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        long dropId = fx.openDrop(219_000, 10, 1);
        long first = fx.member();
        long second = fx.member();

        var docker = redisContainer.getDockerClient();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM drop_event WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, dropId);
                lock.executeQuery().close();
            }
            // 첫 요청: MySQL 경로의 유일한 자리를 잡고 재고 행 잠금을 기다린다
            CompletableFuture<OrderReceipt> blocked = CompletableFuture.supplyAsync(
                    () -> admission.place(first, dropId, 1, "key-1"));
            Thread.sleep(1_000);

            long started = System.nanoTime();
            CutlineException busy = catchThrowableOfType(CutlineException.class,
                    () -> admission.place(second, dropId, 1, "key-1"));
            long waitedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(busy.code()).isEqualTo(ErrorCode.TOO_BUSY);
            assertThat(waitedMs).as("자리를 0.2초 기다린 뒤 포기").isBetween(150L, 1_000L);

            holder.rollback();
            assertThat(blocked.get().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        assertThat(admission.place(second, dropId, 1, "key-1").status())
                .as("자리가 비면 다시 받는다").isEqualTo(OrderStatus.PENDING_PAYMENT);
    }
}
