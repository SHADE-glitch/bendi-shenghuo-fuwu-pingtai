package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家订单查询 <b>HTTP 接口</b>测试（Phase 3.4-B.3 · {@code GET /voucher-orders/merchant}）
 * <p>
 * 与 3.4-B.2 的 Service 层测试互补：本类走完整拦截器链 + 真实 HTTP 语义，验证
 * Controller 是否把「登录 → 角色 → 参数 → 分页 → status → 数据隔离」正确地
 * 接到了已验证的 {@code IMerchantVoucherOrderQueryService} 上。
 * </p>
 * <ul>
 *   <li><b>匿名</b> → 401 + {@code 10001}（{@code LoginInterceptor}）；</li>
 *   <li><b>普通用户 role=0</b> → 403 + {@code 40003}（Controller 显式 {@code assertMerchant()}）；</li>
 *   <li><b>商家 role=1</b> → 200，仅返回自己名下店铺的订单；</li>
 *   <li><b>status</b>：缺省返回全部，指定值原样透传为过滤条件；</li>
 *   <li><b>分页</b>：缺省 {@code current=1/size=10}，{@code total} 正确，{@code create_time DESC}；</li>
 *   <li><b>数据隔离</b>：商家 A 看不到商家 B 的订单；</li>
 *   <li><b>DTO 序列化</b>：{@code Long} 字段（orderId/userId/voucherId/shopId/金额）以字符串返回。</li>
 * </ul>
 *
 * <p>
 * <b>自建自清</b>：商家 / 买家 / 店铺 / 券 / 订单均由测试创建，{@code tearDown} 按
 * 「订单 → 券 → 店铺 → 用户」的 FK 顺序精确删除并清理 Redis 店铺缓存，
 * 不触碰任何种子数据与真实登录会话。
 * </p>
 */
@DisplayName("商家订单查询接口（GET /voucher-orders/merchant）")
class MerchantVoucherOrderControllerTest extends WebIntegrationTestBase {

    private static final String ENDPOINT = "/voucher-orders/merchant";

    private static final int SEED_TYPE_ID = 1;
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-MchOrderApi-";
    private static final String ADDR_A = "接口测试商家订单店铺A";
    private static final String ADDR_B = "接口测试商家订单店铺B";
    private static final String ADDR_C = "接口测试商家订单店铺C";

    /** 直接落库订单的主键序号（tb_voucher_order.id 无 AUTO_INCREMENT，须显式指定；远离真实雪花 id） */
    private static final AtomicLong ORDER_SEQ = new AtomicLong(8_400_000_000_000_000_000L);

    private Long merchantAId;
    private Long merchantBId;
    private Long buyerId;
    private String merchantAToken;
    private String merchantBToken;
    private String buyerToken;

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
        merchantAId = newUser("TestMchOrderApiA_", "a");
        merchantBId = newUser("TestMchOrderApiB_", "b");
        buyerId = newUser("TestMchOrderApiBuyer_", "u");
        merchantAToken = loginAsMerchant(merchantAId, "TestMchOrderApiA");
        merchantBToken = loginAsMerchant(merchantBId, "TestMchOrderApiB");
        buyerToken = loginAs(buyerId, "TestMchOrderApiBuyer");

        shopAId = createShop(merchantAToken, "接口测试商家订单店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "接口测试商家订单店铺B", ADDR_B);

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

        // 2) 券 -> 店铺（按 FK 依赖逆序）
        for (Long vid : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", vid);
            stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + vid);
        }
        createdVoucherIds.clear();
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE title LIKE ?", TITLE_PREFIX + "%");

        for (Long sid : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", sid);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + sid);
        }
        createdShopIds.clear();
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address IN (?, ?, ?)", ADDR_A, ADDR_B, ADDR_C);

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
    }

    // ==================================================================
    // 权限：匿名 / 非商家 / 商家（§十八 1–3）
    // ==================================================================

    @Test
    @DisplayName("匿名访问 -> 401 + 10001（LoginInterceptor）")
    void anonymousReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    @Test
    @DisplayName("普通用户(role=0)访问 -> 403 + 40003（Controller 显式 assertMerchant）")
    void plainUserReturnsForbidden() throws Exception {
        mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, buyerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));
    }

    // ==================================================================
    // 商家成功路径：数据隔离 + DTO 序列化（§十八 4–5）
    // ==================================================================

    @Test
    @DisplayName("商家A 查询 -> 200，仅本店订单，DTO 全字段（Long 序列化为字符串）")
    void merchantSeesOnlyOwnOrdersWithDtoFields() throws Exception {
        long orderA = insertOrder(buyerId, voucherAId, 2, "2026-09-21 10:00:00");
        // 同一买家可在另一张券上下单（唯一键为 user_id + voucher_id），用于构造商家B 的订单
        long orderB = insertOrder(buyerId, voucherBId, 2, "2026-09-21 10:01:00");

        String body = mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value("1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                // 订单维度
                .andExpect(jsonPath("$.data[0].orderId").value(String.valueOf(orderA)))
                .andExpect(jsonPath("$.data[0].status").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(String.valueOf(buyerId)))
                .andExpect(jsonPath("$.data[0].createTime").exists())
                .andExpect(jsonPath("$.data[0].payTime").exists())
                // 券维度
                .andExpect(jsonPath("$.data[0].voucherId").value(String.valueOf(voucherAId)))
                .andExpect(jsonPath("$.data[0].voucherTitle").value(TITLE_PREFIX + "A的券"))
                .andExpect(jsonPath("$.data[0].payValue").value("100"))
                .andExpect(jsonPath("$.data[0].actualValue").value("200"))
                // 店铺维度
                .andExpect(jsonPath("$.data[0].shopId").value(String.valueOf(shopAId)))
                .andExpect(jsonPath("$.data[0].shopName").value("接口测试商家订单店铺A"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(dataOrderIds(body)).doesNotContain(String.valueOf(orderB));
    }

    @Test
    @DisplayName("商家B 查询 -> 200，仅本店订单，不含商家A 的订单")
    void merchantBSeesOnlyOwnOrders() throws Exception {
        long orderA = insertOrder(buyerId, voucherAId, 2, "2026-09-21 10:00:00");
        long orderB = insertOrder(buyerId, voucherBId, 2, "2026-09-21 10:01:00");

        String body = mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, merchantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("1"))
                .andExpect(jsonPath("$.data[0].orderId").value(String.valueOf(orderB)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(dataOrderIds(body)).doesNotContain(String.valueOf(orderA));
    }

    // ==================================================================
    // status 参数透传（§十八 6–7）
    // ==================================================================

    @Test
    @DisplayName("status=3 透传 -> 仅返回已核销订单")
    void statusParamFiltersOrders() throws Exception {
        Long buyer2 = newUser("TestMchOrderApiBuyer_", "u");
        insertOrder(buyerId, voucherAId, 2, "2026-09-21 08:00:00");
        long used = insertOrder(buyer2, voucherAId, 3, "2026-09-21 08:01:00");

        mockMvc.perform(get(ENDPOINT).param("status", "3").header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderId").value(String.valueOf(used)))
                .andExpect(jsonPath("$.data[0].status").value(3));
    }

    @Test
    @DisplayName("缺省 status -> 返回全部状态订单")
    void absentStatusReturnsAll() throws Exception {
        Long buyer2 = newUser("TestMchOrderApiBuyer_", "u");
        insertOrder(buyerId, voucherAId, 2, "2026-09-21 08:00:00");
        insertOrder(buyer2, voucherAId, 4, "2026-09-21 08:01:00");

        mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("2"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================================================================
    // 分页参数（§十八 8–10）
    // ==================================================================

    @Test
    @DisplayName("缺省分页 -> current=1,size=10，10 条，total=12，create_time DESC")
    void defaultPaginationFirstPage() throws Exception {
        List<Long> ids = insertTwelveOrders();

        String body = mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("12"))
                .andExpect(jsonPath("$.data.length()").value(10))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // create_time 最新的是第 12 条（索引 11）
        assertThat(dataOrderIds(body).get(0)).isEqualTo(String.valueOf(ids.get(11)));
        assertThat(dataOrderIds(body).get(9)).isEqualTo(String.valueOf(ids.get(2)));
    }

    @Test
    @DisplayName("current=2,size=10 -> 2 条，total=12，延续 DESC 顺序")
    void paginationSecondPage() throws Exception {
        List<Long> ids = insertTwelveOrders();

        String body = mockMvc.perform(get(ENDPOINT)
                        .param("current", "2")
                        .param("size", "10")
                        .header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("12"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(dataOrderIds(body))
                .containsExactly(String.valueOf(ids.get(1)), String.valueOf(ids.get(0)));
    }

    @Test
    @DisplayName("current 超出末页 -> 空列表，total 仍为 12")
    void paginationBeyondLastPageReturnsEmpty() throws Exception {
        insertTwelveOrders();

        mockMvc.perform(get(ENDPOINT).param("current", "99").header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value("12"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("size 大于总数 -> 返回全部 3 条（参数原样透传，不报错）")
    void sizeLargerThanTotalReturnsAll() throws Exception {
        Long buyer2 = newUser("TestMchOrderApiBuyer_", "u");
        Long buyer3 = newUser("TestMchOrderApiBuyer_", "u");
        insertOrder(buyerId, voucherAId, 2, "2026-09-21 07:00:00");
        insertOrder(buyer2, voucherAId, 2, "2026-09-21 07:01:00");
        insertOrder(buyer3, voucherAId, 2, "2026-09-21 07:02:00");

        mockMvc.perform(get(ENDPOINT).param("size", "50").header(AUTH_HEADER, merchantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("3"))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ==================================================================
    // 空数据：无店铺商家（§十八 11）
    // ==================================================================

    @Test
    @DisplayName("无店铺商家 -> 200，空列表，total=0（不退化为全表）")
    void merchantWithoutShopReturnsEmpty() throws Exception {
        Long merchantCId = newUser("TestMchOrderApiC_", "c");
        String tokenC = loginAsMerchant(merchantCId, "TestMchOrderApiC");
        try {
            // 造一条别人店铺的订单，确保「无店铺」不是「恰好无订单」
            insertOrder(buyerId, voucherAId, 2, "2026-09-21 09:00:00");

            mockMvc.perform(get(ENDPOINT).header(AUTH_HEADER, tokenC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.total").value("0"))
                    .andExpect(jsonPath("$.data.length()").value(0));
        } finally {
            logout(tokenC);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 从成功响应体提取 data 中的 orderId 列表（保持返回顺序；Long 已序列化为字符串） */
    @SuppressWarnings("unchecked")
    private List<String> dataOrderIds(String json) {
        return JsonPath.read(json, "$.data[*].orderId");
    }

    /** 创建测试用户并登记清理 */
    private Long newUser(String prefix, String suffix) {
        Long id = createTestUser(prefix, suffix);
        createdUserIds.add(id);
        return id;
    }

    /**
     * 直接落库一条订单。
     * <p>
     * {@code tb_voucher_order.id} 无 AUTO_INCREMENT（实体为 {@code IdType.INPUT}），
     * 故显式指定主键；{@code pay_time} 在 status>=2 时写入，{@code use_time} 在 status=3 时写入。
     * 订单状态无「按任意状态创建」的 API，故此处直接落库（与既有核销/查询测试同一做法）。
     * </p>
     */
    private long insertOrder(long userId, long voucherId, int status, String createTime) {
        long orderId = ORDER_SEQ.incrementAndGet();
        String payTime = status >= 2 ? "'" + createTime + "'" : "NULL";
        String useTime = status == 3 ? "'" + createTime + "'" : "NULL";
        jdbcTemplate.update(
                "INSERT INTO tb_voucher_order "
                        + "(id, user_id, voucher_id, pay_type, status, create_time, pay_time, use_time, is_deleted) "
                        + "VALUES (" + orderId + ", " + userId + ", " + voucherId + ", 1, " + status
                        + ", '" + createTime + "', " + payTime + ", " + useTime + ", 0)");
        createdOrderIds.add(orderId);
        return orderId;
    }

    /**
     * 为商家A 的 voucherA 造 12 条订单（12 个不同买家，满足 uk_user_voucher），
     * create_time 依次递增 1 分钟；返回按插入顺序（= create_time 升序）排列的 orderId。
     */
    private List<Long> insertTwelveOrders() {
        List<Long> ids = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            Long buyer = newUser("TestMchOrderApiPg_", "p");
            ids.add(insertOrder(buyer, voucherAId, 2, String.format("2026-09-21 06:%02d:00", i)));
        }
        return ids;
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
}
