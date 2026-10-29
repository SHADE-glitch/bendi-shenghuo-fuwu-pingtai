package com.hmdp.test;

import com.hmdp.dto.MerchantVoucherOrderItemDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IMerchantVoucherOrderQueryService;
import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家订单查询测试（Phase 3.4-B.2 · {@code IMerchantVoucherOrderQueryService.queryMerchantOrders}）
 * <p>
 * 直接对 <b>Service</b> 层做集成测试（Controller 端点在 3.4-B.3 落地，本阶段不涉及），
 * 使用真实 Spring Boot + MySQL 环境，验证：
 * </p>
 * <ul>
 *   <li><b>数据权限隔离</b>：商家 A 只能看到自己店铺的订单，看不到商家 B 的；</li>
 *   <li><b>数据链组装</b>：{@code order → voucher → shop} 正确映射到 DTO；</li>
 *   <li><b>status 过滤</b>：{@code null} 返回全部；指定值仅返回匹配项；无匹配返回空；</li>
 *   <li><b>分页</b>：{@code current/size} 与 {@code total} 正确，{@code create_time DESC} 排序；</li>
 *   <li><b>逻辑删除</b>：{@code is_deleted=1} 的订单不可见；</li>
 *   <li><b>空数据</b>：无店铺 / 有店铺无券 / 有券无订单 均返回空分页。</li>
 * </ul>
 *
 * <p>
 * <b>自建自清</b>：商家 / 买家 / 店铺 / 券 / 订单均由测试创建，{@code tearDown} 按
 * 「订单 → 券 → 店铺 → 用户」的 FK 顺序精确删除并清理 Redis 店铺缓存，
 * 不触碰任何种子数据与真实登录会话。
 * </p>
 */
@DisplayName("商家订单查询（IMerchantVoucherOrderQueryService.queryMerchantOrders）")
class MerchantVoucherOrderQueryTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-MchOrder-";
    private static final String ADDR_A = "集成测试商家订单店铺A";
    private static final String ADDR_B = "集成测试商家订单店铺B";
    private static final String ADDR_C = "集成测试商家订单店铺C";

    /** 直接落库订单的主键序号（tb_voucher_order.id 无 AUTO_INCREMENT，须显式指定；远离真实雪花 id） */
    private static final AtomicLong ORDER_SEQ = new AtomicLong(8_300_000_000_000_000_000L);

    @Resource
    private IMerchantVoucherOrderQueryService merchantVoucherOrderQueryService;

    private Long merchantAId;
    private Long merchantBId;
    private String merchantAToken;
    private String merchantBToken;

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
        merchantAId = newUser("TestMchOrderA_", "a");
        merchantBId = newUser("TestMchOrderB_", "b");
        merchantAToken = loginAsMerchant(merchantAId, "TestMchOrderA");
        merchantBToken = loginAsMerchant(merchantBId, "TestMchOrderB");

        shopAId = createShop(merchantAToken, "商家订单店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "商家订单店铺B", ADDR_B);

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
    }

    // ==================================================================
    // 数据权限隔离（§十四 1 / 2）
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 只能查到本店订单，不含商家B 的订单")
    void merchantASeesOnlyOwnOrders() {
        Long buyer = newUser("TestMchOrderBuyer_", "b");
        long orderA = insertOrder(buyer, voucherAId, 2, "2026-09-21 10:00:00");
        long orderB = insertOrder(buyer, voucherBId, 2, "2026-09-21 10:01:00");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(r.getSuccess()).isTrue();
        assertThat(orderIds(r)).containsExactly(orderA);
        assertThat(orderIds(r)).doesNotContain(orderB);
        assertThat(r.getTotal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Case2 商家B 只能查到本店订单，不含商家A 的订单")
    void merchantBSeesOnlyOwnOrders() {
        Long buyer = newUser("TestMchOrderBuyer_", "b");
        long orderA = insertOrder(buyer, voucherAId, 2, "2026-09-21 10:00:00");
        long orderB = insertOrder(buyer, voucherBId, 2, "2026-09-21 10:01:00");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantBId, 1, 10, null);

        assertThat(orderIds(r)).containsExactly(orderB);
        assertThat(orderIds(r)).doesNotContain(orderA);
        assertThat(r.getTotal()).isEqualTo(1L);
    }

    // ==================================================================
    // 数据链组装 + DTO 字段（§十四 3 / 4）
    // ==================================================================

    @Test
    @DisplayName("Case3 order → voucher → shop 正确组装，DTO 全字段正确")
    void dtoFieldsAssembledFromOrderVoucherShop() {
        Long buyer = newUser("TestMchOrderBuyer_", "b");
        // status=3(USED)：pay_time 与 use_time 均有值
        long orderId = insertOrder(buyer, voucherAId, 3, "2026-09-21 09:30:00");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(r.getData()).hasSize(1);
        MerchantVoucherOrderItemDTO dto = r.getData().get(0);
        // 订单维度
        assertThat(dto.getOrderId()).isEqualTo(orderId);
        assertThat(dto.getStatus()).isEqualTo(3);
        assertThat(dto.getUserId()).isEqualTo(buyer);
        assertThat(dto.getCreateTime()).isNotNull();
        assertThat(dto.getPayTime()).as("已支付订单 pay_time 应写入").isNotNull();
        assertThat(dto.getUseTime()).as("已核销订单 use_time 应写入").isNotNull();
        // 券维度（createPlainVoucher: title=TITLE_PREFIX+名称, payValue=100, actualValue=200）
        assertThat(dto.getVoucherId()).isEqualTo(voucherAId);
        assertThat(dto.getVoucherTitle()).isEqualTo(TITLE_PREFIX + "A的券");
        assertThat(dto.getPayValue()).isEqualTo(100L);
        assertThat(dto.getActualValue()).isEqualTo(200L);
        // 店铺维度（归属链 order.voucherId → voucher.shopId → shop.name）
        assertThat(dto.getShopId()).isEqualTo(shopAId);
        assertThat(dto.getShopName()).isEqualTo("商家订单店铺A");
    }

    // ==================================================================
    // status 过滤（§十四 5–11）
    // ==================================================================

    @Test
    @DisplayName("Case4 status=null → 返回该商家全部状态的订单")
    void statusNullReturnsAllStatuses() {
        int[] statuses = {1, 2, 3, 4, 6};
        List<Long> expected = new ArrayList<>();
        for (int s : statuses) {
            Long buyer = newUser("TestMchOrderSt_", "s");
            expected.add(insertOrder(buyer, voucherAId, s, "2026-09-21 08:00:00"));
        }

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(r.getTotal()).isEqualTo(5L);
        assertThat(orderIds(r)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("Case5 status=1 → 仅返回 PENDING_PAYMENT")
    void statusPendingPaymentFilter() {
        assertOnlyStatusReturned(1);
    }

    @Test
    @DisplayName("Case6 status=2 → 仅返回 PAID")
    void statusPaidFilter() {
        assertOnlyStatusReturned(2);
    }

    @Test
    @DisplayName("Case7 status=3 → 仅返回 USED")
    void statusUsedFilter() {
        assertOnlyStatusReturned(3);
    }

    @Test
    @DisplayName("Case8 status=4 → 仅返回 CANCELLED")
    void statusCancelledFilter() {
        assertOnlyStatusReturned(4);
    }

    @Test
    @DisplayName("Case9 status=6 → 仅返回 REFUNDED")
    void statusRefundedFilter() {
        assertOnlyStatusReturned(6);
    }

    @Test
    @DisplayName("Case10 status=5（无匹配订单）→ 返回空列表，total=0")
    void statusNoMatchReturnsEmpty() {
        Long buyer = newUser("TestMchOrderSt_", "s");
        insertOrder(buyer, voucherAId, 2, "2026-09-21 08:00:00");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, 5);

        assertThat(r.getSuccess()).isTrue();
        assertThat(r.getData()).isEmpty();
        assertThat(r.getTotal()).isZero();
    }

    // ==================================================================
    // 分页（§十四 12–14）
    // ==================================================================

    @Test
    @DisplayName("Case11 分页 current=1,size=10 → 10 条，total=12，按 create_time DESC")
    void paginationFirstPage() {
        List<Long> ids = insertTwelveOrders();

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(r.getTotal()).isEqualTo(12L);
        assertThat(r.getData()).hasSize(10);
        // create_time 最新的是第 12 条（索引 11），最旧的 10 条中最后一个是索引 2
        assertThat(orderIds(r).get(0)).as("首页第一条应为 create_time 最新").isEqualTo(ids.get(11));
        assertThat(orderIds(r).get(9)).as("首页最后一条应为第 10 新").isEqualTo(ids.get(2));
    }

    @Test
    @DisplayName("Case12 分页 current=2,size=10 → 2 条，total=12，延续 DESC 顺序")
    void paginationSecondPage() {
        List<Long> ids = insertTwelveOrders();

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 2, 10, null);

        assertThat(r.getTotal()).isEqualTo(12L);
        assertThat(r.getData()).hasSize(2);
        assertThat(orderIds(r)).containsExactly(ids.get(1), ids.get(0));
    }

    // ==================================================================
    // 逻辑删除（数据完整性）
    // ==================================================================

    @Test
    @DisplayName("Case13 is_deleted=1 的订单不可见（@TableLogic 自动过滤）")
    void logicallyDeletedOrderExcluded() {
        Long buyer1 = newUser("TestMchOrderBuyer_", "b");
        Long buyer2 = newUser("TestMchOrderBuyer_", "b");
        long visible = insertOrder(buyer1, voucherAId, 2, "2026-09-21 08:00:00");
        long deleted = insertOrder(buyer2, voucherAId, 2, "2026-09-21 08:01:00");
        jdbcTemplate.update("UPDATE tb_voucher_order SET is_deleted = 1 WHERE id = ?", deleted);

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(orderIds(r)).containsExactly(visible);
        assertThat(r.getTotal()).isEqualTo(1L);
    }

    // ==================================================================
    // 空数据（§十四 15–17）
    // ==================================================================

    @Test
    @DisplayName("Case14 商家名下无店铺 → 空列表，total=0（不退化为全表）")
    void merchantWithoutShopsReturnsEmpty() {
        Long lonely = newUser("TestMchOrderLonely_", "l");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(lonely, 1, 10, null);

        assertThat(r.getSuccess()).isTrue();
        assertThat(r.getData()).isEmpty();
        assertThat(r.getTotal()).isZero();
    }

    @Test
    @DisplayName("Case15 有店铺但无券 → 空列表，total=0")
    void shopWithoutVoucherReturnsEmpty() throws Exception {
        Long merchantCId = newUser("TestMchOrderC_", "c");
        String tokenC = loginAsMerchant(merchantCId, "TestMchOrderC");
        try {
            createShop(tokenC, "商家订单店铺C", ADDR_C);

            Result<List<MerchantVoucherOrderItemDTO>> r =
                    merchantVoucherOrderQueryService.queryMerchantOrders(merchantCId, 1, 10, null);

            assertThat(r.getData()).isEmpty();
            assertThat(r.getTotal()).isZero();
        } finally {
            logout(tokenC);
        }
    }

    @Test
    @DisplayName("Case16 有券但无订单 → 空列表，total=0")
    void voucherWithoutOrderReturnsEmpty() {
        // merchantA 的 shopA 下有 voucherA，但未创建任何订单
        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, null);

        assertThat(r.getData()).isEmpty();
        assertThat(r.getTotal()).isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 断言「指定 status 只返回匹配项」：
     * 插入一条目标状态订单与一条不同状态订单，按目标状态查询应只命中前者。
     */
    private void assertOnlyStatusReturned(int status) {
        Long buyer1 = newUser("TestMchOrderSt_", "s");
        Long buyer2 = newUser("TestMchOrderSt_", "s");
        long match = insertOrder(buyer1, voucherAId, status, "2026-09-21 08:00:00");
        int otherStatus = (status == 2) ? 1 : 2;
        insertOrder(buyer2, voucherAId, otherStatus, "2026-09-21 08:01:00");

        Result<List<MerchantVoucherOrderItemDTO>> r =
                merchantVoucherOrderQueryService.queryMerchantOrders(merchantAId, 1, 10, status);

        assertThat(r.getTotal()).isEqualTo(1L);
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).getOrderId()).isEqualTo(match);
        assertThat(r.getData().get(0).getStatus()).isEqualTo(status);
    }

    /**
     * 为商家A 的 voucherA 造 12 条订单（12 个不同买家，满足 uk_user_voucher），
     * create_time 依次递增 1 分钟；返回按插入顺序（= create_time 升序）排列的 orderId。
     */
    private List<Long> insertTwelveOrders() {
        List<Long> ids = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) {
            Long buyer = newUser("TestMchOrderPg_", "p");
            ids.add(insertOrder(buyer, voucherAId, 2, String.format("2026-09-21 07:%02d:00", i)));
        }
        return ids;
    }

    /** 从成功响应提取 orderId 列表（保持返回顺序） */
    private List<Long> orderIds(Result<List<MerchantVoucherOrderItemDTO>> r) {
        return r.getData().stream()
                .map(MerchantVoucherOrderItemDTO::getOrderId)
                .collect(Collectors.toList());
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
     * 订单状态无「按任意状态创建」的 API，故此处直接落库（与既有核销测试同一做法）。
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
