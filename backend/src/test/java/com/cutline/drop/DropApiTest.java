package com.cutline.drop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cutline.support.DatabaseCleaner;
import com.cutline.support.IntegrationTest;
import com.cutline.support.MutableClock;
import com.cutline.support.TestClockConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class DropApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    DropService dropService;

    long sellerId;
    long productId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        clock.set(TestClockConfig.BASE_TIME);
        sellerId = createSeller("cutline shop");
        productId = createProduct(sellerId, "Jordan 1 Retro", 219_000);
    }

    @Test
    void 판매자가_드롭을_등록하면_오픈_전_상태로_조회된다() throws Exception {
        String location = mvc.perform(post("/api/sellers/{id}/drops", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 100, 1, "2026-12-01T01:00:00Z", 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingQty").value(100))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Jordan 1 Retro"))
                .andExpect(jsonPath("$.price").value(219_000))
                .andExpect(jsonPath("$.opensAt").value("2026-12-01T01:00:00Z"));
    }

    @Test
    void 오픈_시각이_지나면_드롭이_열린다() throws Exception {
        long dropId = createDrop(productId, "2026-12-01T00:10:00Z");

        clock.advance(Duration.ofMinutes(9));
        assertThat(dropService.openDueDrops()).isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(dropService.openDueDrops()).isEqualTo(1);
        assertThat(dropService.openDueDrops()).as("이미 열린 드롭은 다시 바뀌지 않는다").isZero();

        mvc.perform(get("/api/drops/{id}", dropId))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void 다른_판매자의_상품으로는_드롭을_만들_수_없다() throws Exception {
        long otherSeller = createSeller("other shop");

        mvc.perform(post("/api/sellers/{id}/drops", otherSeller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 100, 1, "2026-12-01T01:00:00Z", 10)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_OWNED"));
    }

    @Test
    void 과거_오픈_시각이나_총수량보다_큰_한도는_거절된다() throws Exception {
        mvc.perform(post("/api/sellers/{id}/drops", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 100, 1, "2026-11-30T23:59:59Z", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/sellers/{id}/drops", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 2, 3, "2026-12-01T01:00:00Z", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 입력값_검증_실패는_필드별로_알려준다() throws Exception {
        mvc.perform(post("/api/sellers/{id}/drops", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 0, 1, "2026-12-01T01:00:00Z", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("totalQty"));
    }

    @Test
    void 없는_드롭은_404() throws Exception {
        mvc.perform(get("/api/drops/{id}", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
    }

    @Test
    void 없는_판매자에게는_상품을_등록할_수_없다() throws Exception {
        mvc.perform(post("/api/sellers/{id}/products", 999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"price\":1000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SELLER_NOT_FOUND"));
    }

    private long createSeller(String name) throws Exception {
        String body = mvc.perform(post("/api/sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"feeRateBps\":1000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createProduct(long sellerId, String name, long price) throws Exception {
        String body = mvc.perform(post("/api/sellers/{id}/products", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":" + price + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createDrop(long productId, String opensAt) throws Exception {
        String body = mvc.perform(post("/api/sellers/{id}/drops", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dropJson(productId, 100, 1, opensAt, 10)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static String dropJson(long productId, int totalQty, int limit, String opensAt, int holdMinutes) {
        return """
                {"productId":%d,"totalQty":%d,"perMemberLimit":%d,"opensAt":"%s","holdMinutes":%d}
                """.formatted(productId, totalQty, limit, opensAt, holdMinutes);
    }
}
