package com.cutline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.support.DatabaseCleaner;
import com.cutline.support.Fixtures;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MyOrdersTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderPlacementService placement;

    long member;
    List<Long> placed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        Fixtures fx = new Fixtures(jdbc, TestClockConfig.BASE_TIME);
        long dropId = fx.openDrop(219_000, 100, 100);
        member = fx.member();
        long other = fx.member();
        placement.place(other, dropId, 1, "other");
        for (int i = 0; i < 25; i++) {
            // 같은 시각에 여러 건이 생기는 경우도 섞는다 (created_at 동률은 id로 정렬)
            if (i % 3 == 0) {
                clock.advance(Duration.ofSeconds(1));
            }
            placed.add(placement.place(member, dropId, 1, "k-" + i).orderId());
        }
    }

    @Test
    void 커서로_넘기면_최신순으로_빠짐없이_중복없이_내_주문만_나온다() throws Exception {
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        List<Integer> pageSizes = new ArrayList<>();
        do {
            var request = get("/api/me/orders").header("X-Member-Id", member).param("size", "10");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Number> ids = JsonPath.read(body, "$.items[*].orderId");
            ids.forEach(id -> seen.add(id.longValue()));
            pageSizes.add(ids.size());
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(10, 10, 5);
        assertThat(seen).isEqualTo(placed.reversed());
    }

    @Test
    void 항목에는_상품명과_상태가_들어간다() throws Exception {
        mvc.perform(get("/api/me/orders").header("X-Member-Id", member).param("size", "1"))
                .andExpect(jsonPath("$.items[0].productName").value("Jordan 1 Retro"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void 잘못된_커서나_크기는_400() throws Exception {
        mvc.perform(get("/api/me/orders").header("X-Member-Id", member).param("cursor", "!!not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/me/orders").header("X-Member-Id", member).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
