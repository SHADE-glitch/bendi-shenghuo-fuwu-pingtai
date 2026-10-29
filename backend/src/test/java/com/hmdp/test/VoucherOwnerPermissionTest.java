package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voucher Owner 归属校验测试（Phase 2.2.3.4）
 * <p>
 * 验证 {@code MerchantShopGuard} 接入 {@code POST /vouchers} 与
 * {@code POST /vouchers/seckill-vouchers} 后：<b>商家只能为自己拥有的店铺创建优惠券</b>，
 * 且越权请求<b>不会触达业务层</b>（无 Voucher / 无秒杀库存 / 无 Redis 秒杀数据）。
 * </p>
 *
 * <h3>权限矩阵（两个接口各自覆盖）</h3>
 * <table border="1">
 *   <tr><th>Case</th><th>身份</th><th>目标 shop</th><th>预期</th></tr>
 *   <tr><td>1</td><td>商家 A</td><td>Shop A（自己的）</td><td>200 成功，归属 Shop A</td></tr>
 *   <tr><td>2</td><td>商家 B</td><td>Shop B（自己的）</td><td>200 成功，归属 Shop B</td></tr>
 *   <tr><td>3</td><td>商家 A</td><td>Shop B（他人的）</td><td>403 + 40004，不落库</td></tr>
 *   <tr><td>4</td><td>商家 B</td><td>Shop A（他人的）</td><td>403 + 40004，不落库</td></tr>
 *   <tr><td>5</td><td>商家 A</td><td>Shop A（秒杀券）</td><td>200 成功，DB 秒杀行 + Redis 库存</td></tr>
 *   <tr><td>6</td><td>商家 B</td><td>Shop A（秒杀券，他人的）</td><td>403 + 40004，无 Voucher / 无秒杀库存 / 无 Redis 数据</td></tr>
 *   <tr><td>7</td><td>role=0</td><td>任意</td><td>403 + 40003（WriteAuthInterceptor 阶段）</td></tr>
 *   <tr><td>8</td><td>匿名</td><td>任意</td><td>401 + 10001</td></tr>
 *   <tr><td>9</td><td>商家 A</td><td>不存在的 shopId</td><td>200 + 40001（<b>而非</b> 40004）</td></tr>
 *   <tr><td>10</td><td>商家 A</td><td>历史店铺 owner_id=NULL</td><td>403 + 40004，不落库</td></tr>
 * </table>
 *
 * <p>自建自清：商家/店铺/券均由测试创建，tearDown 精确删除（含 Redis 缓存与秒杀库存），
 * 不触碰任何种子数据。</p>
 */
@DisplayName("Voucher Owner 归属校验（普通券 + 秒杀券，跨商家隔离）")
class VoucherOwnerPermissionTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final long HISTORICAL_SHOP_ID = 1L;      // 种子店铺，owner_id = NULL
    private static final long UNKNOWN_SHOP_ID = 999999999L; // 不存在的店铺

    private static final String ADDR_A = "集成测试券归属A";
    private static final String ADDR_B = "集成测试券归属B";
    private static final String HOURS = "10:00-22:00";
    /** 券标题前缀：tearDown 兜底清理 + 便于人工排查残留 */
    private static final String TITLE_PREFIX = "IT-VoucherOwner-";

    private static final String BEGIN_TIME = "2026-09-21T10:00:00";
    private static final String END_TIME = "2026-12-31T23:59:59";
    private static final int SECKILL_STOCK = 77;

    private Long merchantAId;
    private Long merchantBId;
    private Long plainUserId;
    private String merchantAToken;
    private String merchantBToken;
    private String plainToken;

    private Long shopAId;
    private Long shopBId;

    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantAId = createTestUser("TestVoucherOwnerA_", "a");
        merchantBId = createTestUser("TestVoucherOwnerB_", "b");
        plainUserId = createTestUser("TestVoucherOwnerPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestVoucherOwnerA_a");
        merchantBToken = loginAsMerchant(merchantBId, "TestVoucherOwnerB_b");
        plainToken = loginAs(plainUserId, "TestVoucherOwnerPlain_p"); // role=0

        shopAId = createShop(merchantAToken, "券归属测试A店铺", ADDR_A);
        shopBId = createShop(merchantBToken, "券归属测试B店铺", ADDR_B);

        // 前置断言：归属写对了，后续越权断言才有意义
        assertThat(dbOwnerId(shopAId)).isEqualTo(merchantAId);
        assertThat(dbOwnerId(shopBId)).isEqualTo(merchantBId);
    }

    @AfterEach
    void tearDown() {
        // 1) 先删券（tb_seckill_voucher 随 FK CASCADE 一并删除），再删店铺（fk_voucher_shop RESTRICT）
        for (Long vid : createdVoucherIds) {
            stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + vid);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", vid);
        }
        createdVoucherIds.clear();
        // 兜底：若越权请求意外落库（实现有 bug），按标题前缀清理，避免污染后续测试
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE title LIKE ?", TITLE_PREFIX + "%");

        for (Long sid : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", sid);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + sid);
        }
        createdShopIds.clear();
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address IN (?, ?)", ADDR_A, ADDR_B);

        logout(merchantAToken);
        logout(merchantBToken);
        logout(plainToken);
        for (Long uid : new Long[]{merchantAId, merchantBId, plainUserId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
    }

    // ==================================================================
    // Case 1 / 2：普通券 —— 给自己店铺创建 -> 200
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 为自己 Shop A 建普通券 -> 200 且归属 Shop A")
    void merchantACreatesPlainVoucherForOwnShop() throws Exception {
        ResultActions actions = postVoucher(merchantAToken, shopAId, "A普通券")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());
        long voucherId = extractVoucherId(actions);

        assertThat(dbVoucherShopId(voucherId)).isEqualTo(shopAId);
    }

    @Test
    @DisplayName("Case2 商家B 为自己 Shop B 建普通券 -> 200 且归属 Shop B")
    void merchantBCreatesPlainVoucherForOwnShop() throws Exception {
        ResultActions actions = postVoucher(merchantBToken, shopBId, "B普通券")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());
        long voucherId = extractVoucherId(actions);

        assertThat(dbVoucherShopId(voucherId)).isEqualTo(shopBId);
    }

    // ==================================================================
    // Case 3 / 4：普通券 —— 给他人店铺创建 -> 403 + 40004，且不落库
    // ==================================================================

    @Test
    @DisplayName("Case3 商家A 为 B 的 Shop B 建普通券 -> 403 + 40004，无新券")
    void merchantACannotCreatePlainVoucherForShopB() throws Exception {
        long before = countVouchersForShop(shopBId);

        postVoucher(merchantAToken, shopBId, "A越权B普通券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        assertThat(countVouchersForShop(shopBId)).isEqualTo(before);
    }

    @Test
    @DisplayName("Case4 商家B 为 A 的 Shop A 建普通券 -> 403 + 40004，无新券")
    void merchantBCannotCreatePlainVoucherForShopA() throws Exception {
        long before = countVouchersForShop(shopAId);

        postVoucher(merchantBToken, shopAId, "B越权A普通券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(countVouchersForShop(shopAId)).isEqualTo(before);
    }

    // ==================================================================
    // Case 5：秒杀券 —— 给自己店铺创建 -> 200，DB 秒杀行 + Redis 库存
    // ==================================================================

    @Test
    @DisplayName("Case5 商家A 为自己 Shop A 建秒杀券 -> 200，秒杀行与 Redis 库存均写入")
    void merchantACreatesSeckillVoucherForOwnShop() throws Exception {
        ResultActions actions = postSeckillVoucher(merchantAToken, shopAId, "A秒杀券")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());
        long voucherId = extractVoucherId(actions);

        assertThat(dbVoucherShopId(voucherId)).isEqualTo(shopAId);
        assertThat(dbVoucherType(voucherId)).isEqualTo(1);
        assertThat(countSeckillRow(voucherId)).isEqualTo(1);
        assertThat(dbSeckillStock(voucherId)).isEqualTo(SECKILL_STOCK);
        assertThat(stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId))
                .isEqualTo(String.valueOf(SECKILL_STOCK));
    }

    // ==================================================================
    // Case 6：秒杀券 —— 给他人店铺创建 -> 403 + 40004，且无任何副作用
    // ==================================================================

    @Test
    @DisplayName("Case6 商家B 为 A 的 Shop A 建秒杀券 -> 403 + 40004，无券/无库存/无 Redis 数据")
    void merchantBCannotCreateSeckillVoucherForShopA() throws Exception {
        long vouchersBefore = countVouchersForShop(shopAId);
        long seckillRowsBefore = countAllSeckillRows();
        Set<String> stockKeysBefore = stockKeys();

        postSeckillVoucher(merchantBToken, shopAId, "B越权A秒杀券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        // 越权请求没有到达 addSeckillVoucher：券、秒杀行、Redis 库存均不变
        assertThat(countVouchersForShop(shopAId)).isEqualTo(vouchersBefore);
        assertThat(countAllSeckillRows()).isEqualTo(seckillRowsBefore);
        assertThat(stockKeys()).isEqualTo(stockKeysBefore);
    }

    // ==================================================================
    // Case 7：role=0 —— 两个接口均在 WriteAuthInterceptor 阶段被拒
    // ==================================================================

    @Test
    @DisplayName("Case7a role=0 POST /vouchers -> 403 + 40003")
    void plainUserCannotCreatePlainVoucher() throws Exception {
        long before = countVouchersForShop(shopAId);

        postVoucher(plainToken, shopAId, "普通用户越权普通券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        assertThat(countVouchersForShop(shopAId)).isEqualTo(before);
    }

    @Test
    @DisplayName("Case7b role=0 POST /vouchers/seckill-vouchers -> 403 + 40003")
    void plainUserCannotCreateSeckillVoucher() throws Exception {
        long before = countVouchersForShop(shopAId);

        postSeckillVoucher(plainToken, shopAId, "普通用户越权秒杀券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        assertThat(countVouchersForShop(shopAId)).isEqualTo(before);
    }

    // ==================================================================
    // Case 8：匿名 —— 两个接口均 401 + 10001
    // ==================================================================

    @Test
    @DisplayName("Case8a 匿名 POST /vouchers -> 401 + 10001")
    void anonymousCannotCreatePlainVoucher() throws Exception {
        long before = countVouchersForShop(shopAId);

        mockMvc.perform(post("/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainVoucherPayload(shopAId, "匿名普通券")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));

        assertThat(countVouchersForShop(shopAId)).isEqualTo(before);
    }

    @Test
    @DisplayName("Case8b 匿名 POST /vouchers/seckill-vouchers -> 401 + 10001")
    void anonymousCannotCreateSeckillVoucher() throws Exception {
        long before = countVouchersForShop(shopAId);

        mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillVoucherPayload(shopAId, "匿名秒杀券")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"));

        assertThat(countVouchersForShop(shopAId)).isEqualTo(before);
    }

    // ==================================================================
    // Case 9：不存在的 shopId -> 200 + 40001（区分「不存在」与「无权限」）
    // ==================================================================

    @Test
    @DisplayName("Case9a 商家A POST /vouchers 不存在店铺 -> 40001（而非 40004）")
    void unknownShopPlainVoucherReturnsNotFoundCode() throws Exception {
        postVoucher(merchantAToken, UNKNOWN_SHOP_ID, "不存在店铺普通券")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40001"))
                .andExpect(jsonPath("$.errorMsg").value("店铺不存在"));
    }

    @Test
    @DisplayName("Case9b 商家A POST /vouchers/seckill-vouchers 不存在店铺 -> 40001")
    void unknownShopSeckillVoucherReturnsNotFoundCode() throws Exception {
        postSeckillVoucher(merchantAToken, UNKNOWN_SHOP_ID, "不存在店铺秒杀券")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40001"))
                .andExpect(jsonPath("$.errorMsg").value("店铺不存在"));
    }

    // ==================================================================
    // Case 10：历史店铺 owner_id=NULL -> 403 + 40004，且不落库
    // ==================================================================

    @Test
    @DisplayName("Case10a 商家A 为历史店铺(owner_id=NULL) 建普通券 -> 403 + 40004")
    void historicalShopPlainVoucherRejected() throws Exception {
        assertThat(dbOwnerId(HISTORICAL_SHOP_ID)).isNull();
        long before = countVouchersForShop(HISTORICAL_SHOP_ID);

        postVoucher(merchantAToken, HISTORICAL_SHOP_ID, "历史店铺普通券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(countVouchersForShop(HISTORICAL_SHOP_ID)).isEqualTo(before);
    }

    @Test
    @DisplayName("Case10b 商家A 为历史店铺(owner_id=NULL) 建秒杀券 -> 403 + 40004")
    void historicalShopSeckillVoucherRejected() throws Exception {
        assertThat(dbOwnerId(HISTORICAL_SHOP_ID)).isNull();
        long before = countVouchersForShop(HISTORICAL_SHOP_ID);

        postSeckillVoucher(merchantAToken, HISTORICAL_SHOP_ID, "历史店铺秒杀券")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(countVouchersForShop(HISTORICAL_SHOP_ID)).isEqualTo(before);
    }

    // ==================================================================
    // 辅助：请求
    // ==================================================================

    private ResultActions postVoucher(String token, Long shopId, String title) throws Exception {
        return mockMvc.perform(post("/vouchers")
                .header(AUTH_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(plainVoucherPayload(shopId, title)));
    }

    private ResultActions postSeckillVoucher(String token, Long shopId, String title) throws Exception {
        return mockMvc.perform(post("/vouchers/seckill-vouchers")
                .header(AUTH_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(seckillVoucherPayload(shopId, title)));
    }

    private String plainVoucherPayload(Long shopId, String title) {
        return "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":0"
                + "}";
    }

    private String seckillVoucherPayload(Long shopId, String title) {
        return "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":1,"
                + "\"stock\":" + SECKILL_STOCK + ","
                + "\"beginTime\":\"" + BEGIN_TIME + "\","
                + "\"endTime\":\"" + END_TIME + "\""
                + "}";
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

    // ==================================================================
    // 辅助：DB / Redis 断言
    // ==================================================================

    /** 从成功响应体提取新建券 id 并登记，供 tearDown 精确清理（data 为全局 Long->String） */
    private long extractVoucherId(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdVoucherIds.add(id);
        return id;
    }

    private Long dbOwnerId(long shopId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tb_shop WHERE id = ?", Long.class, shopId);
    }

    private Long dbVoucherShopId(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT shop_id FROM tb_voucher WHERE id = ?", Long.class, voucherId);
    }

    private Integer dbVoucherType(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT type FROM tb_voucher WHERE id = ?", Integer.class, voucherId);
    }

    private long countVouchersForShop(long shopId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE shop_id = ?", Long.class, shopId);
        return n == null ? 0 : n;
    }

    private long countSeckillRow(long voucherId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Long.class, voucherId);
        return n == null ? 0 : n;
    }

    private long countAllSeckillRows() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher", Long.class);
        return n == null ? 0 : n;
    }

    private Integer dbSeckillStock(long voucherId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
    }

    private Set<String> stockKeys() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.SECKILL_STOCK_KEY + "*");
        return keys == null ? Set.of() : keys;
    }
}
