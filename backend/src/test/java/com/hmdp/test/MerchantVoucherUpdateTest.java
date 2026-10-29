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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant 修改优惠券测试（Phase 3.2 · {@code PUT /vouchers/{voucherId}}）
 * <p>
 * 验证：
 * <ul>
 *   <li>归属隔离：商家只能改自己店铺下的券；跨商家 → 403 + 40004，DB 不变；</li>
 *   <li>券不存在 → 200 + 30000（与「无权限」严格区分）；</li>
 *   <li><b>白名单</b>：请求体夹带 {@code shopId}/{@code type} 被静默忽略，
 *       归属与类型均不可被客户端改写；</li>
 *   <li>参数契约：非法 {@code status} / 空 {@code title} → 200 + 10008，DB 不变；</li>
 *   <li>秒杀券只改展示字段时，{@code tb_seckill_voucher} 与 Redis 秒杀库存<b>零副作用</b>；</li>
 *   <li>角色：普通用户 403 + 40003、匿名 401 + 10001。</li>
 * </ul>
 * </p>
 *
 * <p>自建自清：商家 / 店铺 / 券由测试创建，tearDown 精确删除并清理 Redis，不触碰种子数据。</p>
 */
@DisplayName("Merchant 修改优惠券（PUT /vouchers/{voucherId}）")
class MerchantVoucherUpdateTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String ADDR_A = "集成测试改券店铺A";
    private static final String ADDR_B = "集成测试改券店铺B";
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-UpdateVoucher-";

    private Long merchantAId;
    private Long merchantBId;
    private Long plainUserId;
    private String merchantAToken;
    private String merchantBToken;
    private String plainToken;

    private Long shopAId;
    private Long shopBId;

    private Long voucherAId;
    private Long voucherBId;

    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantAId = createTestUser("TestUpdVoucherA_", "a");
        merchantBId = createTestUser("TestUpdVoucherB_", "b");
        plainUserId = createTestUser("TestUpdVoucherPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestUpdVoucherA_a");
        merchantBToken = loginAsMerchant(merchantBId, "TestUpdVoucherB_b");
        plainToken = loginAs(plainUserId, "TestUpdVoucherPlain_p"); // role=0

        shopAId = createShop(merchantAToken, "改券店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "改券店铺B", ADDR_B);

        voucherAId = createPlainVoucher(merchantAToken, shopAId, "A的券");
        voucherBId = createPlainVoucher(merchantBToken, shopBId, "B的券");
    }

    @AfterEach
    void tearDown() {
        for (Long vid : createdVoucherIds) {
            stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + vid);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", vid);
        }
        createdVoucherIds.clear();
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
    // Case 1：本人券可改
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 修改自己的券 -> 200，DB 标题/状态更新")
    void ownerCanUpdateOwnVoucher() throws Exception {
        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "A改后", "\"status\":2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbVoucherTitle(voucherAId)).isEqualTo(TITLE_PREFIX + "A改后");
        assertThat(dbVoucherStatus(voucherAId)).isEqualTo(2);
        // 归属与类型不变
        assertThat(dbVoucherShopId(voucherAId)).isEqualTo(shopAId);
        assertThat(dbVoucherType(voucherAId)).isEqualTo(0);
    }

    // ==================================================================
    // Case 2：跨商家修改 -> 403 + 40004，DB 不变
    // ==================================================================

    @Test
    @DisplayName("Case2 商家A 修改商家B 的券 -> 403 + 40004，目标券零改动")
    void cannotUpdateOthersVoucher() throws Exception {
        String titleBefore = dbVoucherTitle(voucherBId);

        mockMvc.perform(put("/vouchers/" + voucherBId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "越权改", "\"status\":2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        assertThat(dbVoucherTitle(voucherBId)).isEqualTo(titleBefore);
        assertThat(dbVoucherStatus(voucherBId)).isEqualTo(1);
    }

    // ==================================================================
    // Case 3：券不存在 -> 30000（业务错误，HTTP 200）
    // ==================================================================

    @Test
    @DisplayName("Case3 修改不存在的券 -> 200 + 30000（优惠券不存在）")
    void updateMissingVoucherReturnsNotFound() throws Exception {
        mockMvc.perform(put("/vouchers/999999999")
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "不存在", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30000"))
                .andExpect(jsonPath("$.errorMsg").value("优惠券不存在"));
    }

    // ==================================================================
    // Case 4 / 5：白名单 —— shopId / type 不可被请求体改写
    // ==================================================================

    @Test
    @DisplayName("Case4 请求体夹带 shopId -> 被忽略，归属仍为原店铺")
    void shopIdInBodyIsIgnored() throws Exception {
        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + TITLE_PREFIX + "偷换店铺\","
                                + "\"shopId\":" + shopBId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbVoucherShopId(voucherAId)).isEqualTo(shopAId);
        assertThat(dbVoucherTitle(voucherAId)).isEqualTo(TITLE_PREFIX + "偷换店铺");
    }

    @Test
    @DisplayName("Case5 请求体夹带 type=1 -> 被忽略，类型仍为 0（不产生结构不完整券）")
    void typeInBodyIsIgnored() throws Exception {
        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + TITLE_PREFIX + "偷换类型\",\"type\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbVoucherType(voucherAId)).isEqualTo(0);
        assertThat(countSeckillRow(voucherAId)).isEqualTo(0);
    }

    // ==================================================================
    // Case 6 / 7：参数契约
    // ==================================================================

    @Test
    @DisplayName("Case6 非法 status（99）-> 200 + 10008，DB 不变")
    void invalidStatusRejected() throws Exception {
        String titleBefore = dbVoucherTitle(voucherAId);

        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "非法状态", "\"status\":99")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));

        assertThat(dbVoucherTitle(voucherAId)).isEqualTo(titleBefore);
        assertThat(dbVoucherStatus(voucherAId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Case7 空 title -> 200 + 10008，DB 不变")
    void blankTitleRejected() throws Exception {
        String titleBefore = dbVoucherTitle(voucherAId);

        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"status\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));

        assertThat(dbVoucherTitle(voucherAId)).isEqualTo(titleBefore);
        assertThat(dbVoucherStatus(voucherAId)).isEqualTo(1);
    }

    // ==================================================================
    // Case 8：秒杀券只改展示字段 -> tb_seckill_voucher 与 Redis 零副作用
    // ==================================================================

    @Test
    @DisplayName("Case8 商家A 改自己的秒杀券展示字段 -> 200，秒杀库存(DB+Redis)零副作用")
    void updatingSeckillVoucherDoesNotTouchStock() throws Exception {
        long seckillId = createSeckillVoucher(merchantAToken, shopAId, "A的秒杀券", 66);
        Integer dbStockBefore = dbSeckillStock(seckillId);
        String redisStockBefore = redisStock(seckillId);

        mockMvc.perform(put("/vouchers/" + seckillId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "秒杀券改名", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbVoucherTitle(seckillId)).isEqualTo(TITLE_PREFIX + "秒杀券改名");
        assertThat(dbVoucherType(seckillId)).isEqualTo(1);
        assertThat(dbSeckillStock(seckillId)).isEqualTo(dbStockBefore);
        assertThat(redisStock(seckillId)).isEqualTo(redisStockBefore);
    }

    // ==================================================================
    // Case 9 / 10：角色与跨商家秒杀券攻击
    // ==================================================================

    @Test
    @DisplayName("Case9 角色语义：普通用户 403+40003；匿名 401+10001")
    void roleSemanticsUnchanged() throws Exception {
        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "普通用户改", "")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40003"));

        mockMvc.perform(put("/vouchers/" + voucherAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "匿名改", "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));

        assertThat(dbVoucherTitle(voucherAId)).isNotEqualTo(TITLE_PREFIX + "普通用户改");
        assertThat(dbVoucherTitle(voucherAId)).isNotEqualTo(TITLE_PREFIX + "匿名改");
    }

    @Test
    @DisplayName("Case10 商家A 改商家B 的秒杀券 -> 403 + 40004，DB/Redis 秒杀状态零副作用")
    void cannotUpdateOthersSeckillVoucher() throws Exception {
        long seckillBId = createSeckillVoucher(merchantBToken, shopBId, "B的秒杀券", 88);
        String titleBefore = dbVoucherTitle(seckillBId);
        Integer dbStockBefore = dbSeckillStock(seckillBId);
        String redisStockBefore = redisStock(seckillBId);

        mockMvc.perform(put("/vouchers/" + seckillBId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TITLE_PREFIX + "越权改秒杀", "\"status\":2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(dbVoucherTitle(seckillBId)).isEqualTo(titleBefore);
        assertThat(dbVoucherStatus(seckillBId)).isEqualTo(1);
        assertThat(dbSeckillStock(seckillBId)).isEqualTo(dbStockBefore);
        assertThat(redisStock(seckillBId)).isEqualTo(redisStockBefore);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 构造修改请求体：title 必填，extra 为附加的 JSON 片段（可为空串） */
    private String body(String title, String extra) {
        return "{\"title\":\"" + title + "\""
                + (extra == null || extra.isEmpty() ? "" : "," + extra)
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

    private long createPlainVoucher(String token, long shopId, String title) throws Exception {
        return createVoucher(token, "/vouchers", "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":0"
                + "}");
    }

    private long createSeckillVoucher(String token, long shopId, String title, int stock) throws Exception {
        return createVoucher(token, "/vouchers/seckill-vouchers", "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":1,"
                + "\"stock\":" + stock + ","
                + "\"beginTime\":\"2026-09-21T10:00:00\","
                + "\"endTime\":\"2026-12-31T23:59:59\""
                + "}");
    }

    private long createVoucher(String token, String path, String payload) throws Exception {
        String body = mockMvc.perform(post(path)
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdVoucherIds.add(id);
        return id;
    }

    private String dbVoucherTitle(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT title FROM tb_voucher WHERE id = ?", String.class, voucherId);
    }

    private Integer dbVoucherStatus(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tb_voucher WHERE id = ?", Integer.class, voucherId);
    }

    private Integer dbVoucherType(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT type FROM tb_voucher WHERE id = ?", Integer.class, voucherId);
    }

    private Long dbVoucherShopId(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT shop_id FROM tb_voucher WHERE id = ?", Long.class, voucherId);
    }

    private Integer dbSeckillStock(long voucherId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
    }

    private long countSeckillRow(long voucherId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Long.class, voucherId);
        return n == null ? 0 : n;
    }

    private String redisStock(long voucherId) {
        return stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
    }
}
