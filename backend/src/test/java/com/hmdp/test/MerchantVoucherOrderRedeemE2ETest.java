package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家「订单查询 + 核销」端到端联调测试（Phase 3.4-B.4）
 * <p>
 * 目的：证明
 * {@code GET /voucher-orders/merchant} 与 {@code POST /voucher-orders/{orderId}/redeem}
 * 不是两个孤立接口，而是能通过<b>真实 HTTP + DB 状态变化</b>形成完整闭环：
 * </p>
 * <pre>
 * GET /voucher-orders/merchant?status=2   → 找到 PAID 订单（拿到 orderId）
 *           ↓
 * POST /voucher-orders/{orderId}/redeem   → PAID(2) → USED(3)，写 use_time
 *           ↓
 * GET /voucher-orders/merchant?status=3   → 同一 orderId，status=3，useTime != null
 * </pre>
 *
 * <h3>测试方式</h3>
 * <p>
 * 全部走真实 {@code @SpringBootTest @AutoConfigureMockMvc} + 真实登录态 + 真实
 * Controller → Guard → Service → DB。订单经真实业务 API（{@code /purchase-orders} + {@code /pay}）
 * 构造为 PAID，<b>不 Mock</b> 任何 Service / Mapper / Redis / Controller。
 * </p>
 *
 * <h3>用例清单</h3>
 * <ol>
 *   <li><b>核心闭环</b>：PAID 查询 → 核销 → USED 查询（含 DB 与 HTTP 双证据 + status=2 不再返回）；</li>
 *   <li><b>useTime 数据链</b>：DB {@code use_time} → DTO → JSON 三处一致（秒级）；</li>
 *   <li><b>幂等</b>：重复核销 → 30009，status 仍 3，use_time 不被重置；</li>
 *   <li><b>跨商家</b>：A 查不到 B 的订单、A 核销 B 的订单被拒 40004、B 订单不变；</li>
 *   <li><b>普通用户</b>：Query 与 Redeem 均 403 + 40003，订单不变；</li>
 *   <li><b>匿名</b>：Query 与 Redeem 均 401 + 10001，订单不变。</li>
 * </ol>
 *
 * <p>
 * <b>自建自清</b>：商家 / 买家 / 店铺 / 券 / 订单均由测试创建，{@code tearDown} 按
 * 「订单 → 秒杀行 → 券 → 店铺 → 用户」的 FK 顺序精确删除并清理 Redis，
 * 不触碰任何种子数据与真实登录会话。
 * </p>
 */
@DisplayName("商家订单查询+核销端到端联调（Query ⇄ Redeem）")
class MerchantVoucherOrderRedeemE2ETest extends WebIntegrationTestBase {

    private static final String QUERY_ENDPOINT = "/voucher-orders/merchant";
    private static final int SEED_TYPE_ID = 1;
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-RedeemE2E-";
    private static final String ADDR_A = "端到端联调店铺A";
    private static final String ADDR_B = "端到端联调店铺B";

    private Long merchantAId;
    private Long merchantBId;
    private Long buyerId;
    private Long plainUserId;
    private String merchantAToken;
    private String merchantBToken;
    private String buyerToken;
    private String plainUserToken;

    private Long shopAId;
    private Long shopBId;
    private Long voucherAId;
    private Long voucherBId;

    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantAId = newUser("TestE2EMerchantA_", "a");
        merchantBId = newUser("TestE2EMerchantB_", "b");
        buyerId = newUser("TestE2EBuyer_", "u");
        plainUserId = newUser("TestE2EPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestE2EMerchantA");
        merchantBToken = loginAsMerchant(merchantBId, "TestE2EMerchantB");
        buyerToken = loginAs(buyerId, "TestE2EBuyer");
        plainUserToken = loginAs(plainUserId, "TestE2EPlain");

        shopAId = createShop(merchantAToken, "端到端联调店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "端到端联调店铺B", ADDR_B);

        voucherAId = createPlainVoucher(merchantAToken, shopAId, "A的券");
        voucherBId = createPlainVoucher(merchantBToken, shopBId, "B的券");
    }

    @AfterEach
    void tearDown() {
        // 1) 订单（子表）先删：tb_voucher_order -> tb_voucher 为 FK RESTRICT
        for (Long oid : createdOrderIds) {
            jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id = ?", oid);
        }
        createdOrderIds.clear();
        jdbcTemplate.update(
                "DELETE FROM tb_voucher_order WHERE voucher_id IN (SELECT id FROM tb_voucher WHERE title LIKE ?)",
                TITLE_PREFIX + "%");

        // 2) 秒杀行 -> 券 -> 店铺（按 FK 依赖逆序）
        for (Long vid : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", vid);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", vid);
            stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + vid);
            stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + vid);
        }
        createdVoucherIds.clear();
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE title LIKE ?", TITLE_PREFIX + "%");

        for (Long sid : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", sid);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + sid);
        }
        createdShopIds.clear();
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address IN (?, ?)", ADDR_A, ADDR_B);

        // 3) 用户
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
        createdUserIds.clear();

        // 4) 登录会话
        logout(merchantAToken);
        logout(merchantBToken);
        logout(buyerToken);
        logout(plainUserToken);
    }

    // ==================================================================
    // Case 1：核心闭环 PAID → Redeem → USED
    // ==================================================================

    @Test
    @DisplayName("核心闭环：Query(PAID) → Redeem → Query(USED)，同一 orderId，useTime 由 null 变非 null")
    void fullClosedLoopPaidRedeemUsed() throws Exception {
        long orderA = createPaidOrder(buyerToken, voucherAId);
        assertThat(dbOrderStatus(orderA)).as("前置：订单应为 PAID(2)").isEqualTo(2);
        assertThat(dbUseTime(orderA)).as("前置：未核销 use_time 应为空").isNull();

        // ---- 步骤 1：Query status=2 找到 Order A（PAID）----
        String paidBody = queryMerchantOrders(merchantAToken, "2");
        assertThat(dataOrderIds(paidBody)).containsExactly(String.valueOf(orderA));
        assertThat(JsonPath.<String>read(paidBody, "$.data[0].orderId")).isEqualTo(String.valueOf(orderA));
        assertThat(JsonPath.<Integer>read(paidBody, "$.data[0].status")).isEqualTo(2);
        assertThat(JsonPath.<String>read(paidBody, "$.data[0].shopId")).isEqualTo(String.valueOf(shopAId));
        assertThat(JsonPath.<String>read(paidBody, "$.data[0].voucherId")).isEqualTo(String.valueOf(voucherAId));
        assertThat((Object) JsonPath.read(paidBody, "$.data[0].useTime"))
                .as("PAID 订单 useTime 应为 null").isNull();

        // ---- 步骤 2：Redeem Order A ----
        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // ---- 步骤 3：DB 证据：2 → 3，use_time 写入 ----
        assertThat(dbOrderStatus(orderA)).as("核销后 DB status 应为 USED(3)").isEqualTo(3);
        assertThat(dbUseTime(orderA)).as("核销后 DB use_time 必须写入").isNotNull();

        // ---- 步骤 4：Query status=3 找到同一 orderId（USED）----
        String usedBody = queryMerchantOrders(merchantAToken, "3");
        assertThat(dataOrderIds(usedBody)).containsExactly(String.valueOf(orderA));
        assertThat(JsonPath.<String>read(usedBody, "$.data[0].orderId")).isEqualTo(String.valueOf(orderA));
        assertThat(JsonPath.<Integer>read(usedBody, "$.data[0].status")).isEqualTo(3);
        assertThat((Object) JsonPath.read(usedBody, "$.data[0].useTime"))
                .as("USED 订单 useTime 必须非 null").isNotNull();

        // ---- 步骤 5：Query status=2 不再返回该订单 ----
        assertThat(dataOrderIds(queryMerchantOrders(merchantAToken, "2")))
                .as("核销后不应再出现在 PAID 列表")
                .doesNotContain(String.valueOf(orderA));
    }

    // ==================================================================
    // Case 2：useTime 数据链 DB → DTO → JSON
    // ==================================================================

    @Test
    @DisplayName("useTime 数据链：DB use_time → DTO → JSON 一致（秒级），非 null")
    void useTimeDataChainDbToJson() throws Exception {
        long orderA = createPaidOrder(buyerToken, voucherAId);

        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Timestamp dbUseTime = dbUseTime(orderA);
        assertThat(dbUseTime).as("Redeem 后 DB use_time 必须有值").isNotNull();

        String body = queryMerchantOrders(merchantAToken, "3");
        assertThat(JsonPath.<String>read(body, "$.data[0].orderId")).isEqualTo(String.valueOf(orderA));

        String jsonUseTime = JsonPath.read(body, "$.data[0].useTime");
        assertThat(jsonUseTime).as("DTO.useTime 不得为 null（否则为 Query/DTO 回填问题）").isNotNull();

        LocalDateTime fromDb = dbUseTime.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime fromJson = LocalDateTime.parse(jsonUseTime).truncatedTo(ChronoUnit.SECONDS);
        assertThat(fromJson).as("JSON useTime 必须与 DB use_time 一致").isEqualTo(fromDb);
    }

    // ==================================================================
    // Case 3：幂等 —— 重复核销
    // ==================================================================

    @Test
    @DisplayName("重复核销：第二次 30009，status 仍 3，use_time 不被重置")
    void duplicateRedeemRejectedAndStateStable() throws Exception {
        long orderA = createPaidOrder(buyerToken, voucherAId);

        // 第一次核销成功
        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(dbOrderStatus(orderA)).isEqualTo(3);
        assertThat(dbUseTime(orderA)).isNotNull();

        // 第二次被拒（CAS 未命中）
        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30009"))
                .andExpect(jsonPath("$.errorMsg").value("当前订单状态不允许该操作"));

        assertThat(dbOrderStatus(orderA)).as("重复核销后状态必须仍为 3").isEqualTo(3);
        // 非脆弱断言：只验证 use_time 仍有值（不比较精确时间）
        assertThat(dbUseTime(orderA)).as("use_time 不得被清空").isNotNull();
    }

    // ==================================================================
    // Case 4：跨商家 —— 查询隔离 + 核销越权
    // ==================================================================

    @Test
    @DisplayName("跨商家：A 查不到 B 的订单，A 核销 B 的订单 403+40004，B 订单不变")
    void crossMerchantIsolationAndRedeemForbidden() throws Exception {
        long orderB = createPaidOrder(buyerToken, voucherBId);

        // 查询隔离：商家A 看不到 Order B；商家B 能看到自己的
        assertThat(dataOrderIds(queryMerchantOrders(merchantAToken, "2")))
                .doesNotContain(String.valueOf(orderB));
        assertThat(dataOrderIds(queryMerchantOrders(merchantBToken, "2")))
                .contains(String.valueOf(orderB));

        // 核销越权：商家A 核销 Order B → 403 + 40004
        mockMvc.perform(post("/voucher-orders/" + orderB + "/redeem")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        // Order B 未被任何改动
        assertThat(dbOrderStatus(orderB)).as("越权核销后 B 的订单状态必须保持 PAID(2)").isEqualTo(2);
        assertThat(dbUseTime(orderB)).as("越权核销不得写入 use_time").isNull();
    }

    // ==================================================================
    // Case 5：普通用户（role=0）—— 两个端点行为一致
    // ==================================================================

    @Test
    @DisplayName("普通用户(role=0)：Query 与 Redeem 均 403+40003，订单不变")
    void plainUserForbiddenOnBothEndpoints() throws Exception {
        long orderA = createPaidOrder(buyerToken, voucherAId);

        // Query
        mockMvc.perform(get(QUERY_ENDPOINT).header(AUTH_HEADER, plainUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        // Redeem
        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem")
                        .header(AUTH_HEADER, plainUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        assertThat(dbOrderStatus(orderA)).isEqualTo(2);
        assertThat(dbUseTime(orderA)).isNull();
    }

    // ==================================================================
    // Case 6：匿名 —— 两个端点均被 LoginInterceptor 拦截
    // ==================================================================

    @Test
    @DisplayName("匿名：Query 与 Redeem 均 401+10001，订单不变")
    void anonymousUnauthorizedOnBothEndpoints() throws Exception {
        long orderA = createPaidOrder(buyerToken, voucherAId);

        // Query
        mockMvc.perform(get(QUERY_ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));

        // Redeem
        mockMvc.perform(post("/voucher-orders/" + orderA + "/redeem"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));

        assertThat(dbOrderStatus(orderA)).isEqualTo(2);
        assertThat(dbUseTime(orderA)).isNull();
    }

    // ==================================================================
    // 辅助：HTTP 调用
    // ==================================================================

    /** 以商家身份调用 Query 端点（可选 status），断言 200 + success，返回响应体 JSON 字符串 */
    private String queryMerchantOrders(String token, String status) throws Exception {
        MockHttpServletRequestBuilder req = get(QUERY_ENDPOINT).header(AUTH_HEADER, token);
        if (status != null) {
            req = req.param("status", status);
        }
        return mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 从响应体提取 data 中的 orderId 列表（Long 已序列化为字符串） */
    @SuppressWarnings("unchecked")
    private List<String> dataOrderIds(String json) {
        return JsonPath.read(json, "$.data[*].orderId");
    }

    // ==================================================================
    // 辅助：构造数据
    // ==================================================================

    /** 下单（不支付）→ 订单状态 1 */
    private long createPendingOrder(String buyerToken, long voucherId) throws Exception {
        String body = mockMvc.perform(post("/voucher-orders/purchase-orders")
                        .header(AUTH_HEADER, buyerToken)
                        .param("voucherId", String.valueOf(voucherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long orderId = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data.orderId")));
        createdOrderIds.add(orderId);
        return orderId;
    }

    /** 下单并支付 → 订单状态 2（经真实业务 API，非直接落库） */
    private long createPaidOrder(String buyerToken, long voucherId) throws Exception {
        long orderId = createPendingOrder(buyerToken, voucherId);
        mockMvc.perform(post("/voucher-orders/" + orderId + "/pay")
                        .header(AUTH_HEADER, buyerToken)
                        .param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(dbOrderStatus(orderId)).isEqualTo(2);
        return orderId;
    }

    /** 创建测试用户并登记清理 */
    private Long newUser(String prefix, String suffix) {
        Long id = createTestUser(prefix, suffix);
        createdUserIds.add(id);
        return id;
    }

    private long createShop(String token, String name, String address) throws Exception {
        String body = mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"" + name + "\","
                                + "\"typeId\":" + SEED_TYPE_ID + ","
                                + "\"images\":\"[\\\"https://example.com/test-shop.jpg\\\"]\","
                                + "\"address\":\"" + address + "\","
                                + "\"x\":120.1500,\"y\":30.3100,"
                                + "\"sold\":0,\"comments\":0,\"score\":50,"
                                + "\"openHours\":\"" + HOURS + "\""
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdShopIds.add(id);
        return id;
    }

    private long createPlainVoucher(String token, long shopId, String title) throws Exception {
        String body = mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"shopId\":" + shopId + ","
                                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                                + "\"payValue\":100,"
                                + "\"actualValue\":200,"
                                + "\"type\":0"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdVoucherIds.add(id);
        return id;
    }

    // ==================================================================
    // 辅助：DB 断言取值
    // ==================================================================

    private Integer dbOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
    }

    private Timestamp dbUseTime(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT use_time FROM tb_voucher_order WHERE id = ?", Timestamp.class, orderId);
    }
}
