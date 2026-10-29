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
 * Voucher 写入口「参数契约 + 类型契约」测试（Phase 2 收口）
 * <p>
 * 覆盖两项收口：
 * <ol>
 *   <li><b>参数契约</b>：{@code shopId} 缺失时由 {@code @Valid} 在进入业务层前拦截
 *       （项目参数错误语义 = HTTP 200 + 10008，见 {@code WebExceptionAdvice}），
 *       不进入 owner 查询、不产生任何写入。</li>
 *   <li><b>类型契约</b>：{@code POST /vouchers}（普通券入口）只接受 {@code type=0}；
 *       {@code type=1} 必须走 {@code /vouchers/seckill-vouchers}，否则会写入
 *       「{@code tb_voucher.type=1} 但无 {@code tb_seckill_voucher} / 无 Redis 库存」的
 *       结构不完整数据。越界请求返回 30007 且<b>零副作用</b>。</li>
 * </ol>
 * </p>
 *
 * <h3>说明</h3>
 * <p>
 * 项目统一的「业务错误 = HTTP 200 + errorCode」惯例同样适用于参数校验失败
 * （{@code WebExceptionAdvice.handleMethodArgumentNotValidException} 未标注
 * {@code @ResponseStatus}），因此本测试断言 HTTP 200 + 10008，与既有
 * {@code MerchantPermissionTest} 的既有断言保持一致。
 * </p>
 */
@DisplayName("Voucher 写入口契约（shopId 必填 / type 一致性）")
class VoucherContractTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String ADDR = "集成测试券契约店铺";
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-VoucherContract-";

    private Long merchantId;
    private String merchantToken;
    private Long shopId;

    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantId = createTestUser("TestVoucherContract_", "m");
        merchantToken = loginAsMerchant(merchantId, "TestVoucherContract_m");
        shopId = createShop(merchantToken, "券契约测试店铺", ADDR);
        assertThat(dbOwnerId(shopId)).isEqualTo(merchantId);
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
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address = ?", ADDR);

        logout(merchantToken);
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", merchantId);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", merchantId);
    }

    // ==================================================================
    // A. 参数契约：shopId 缺失
    // ==================================================================

    @Test
    @DisplayName("A. shopId 缺失 -> 200 + 10008（参数校验失败），且不产生任何券")
    void missingShopIdRejectedByParamValidation() throws Exception {
        long before = countVouchersForShop(shopId);

        // 注意：body 完全不含 shopId
        mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"title\":\"" + TITLE_PREFIX + "缺shopId\","
                                + "\"payValue\":100,"
                                + "\"actualValue\":200,"
                                + "\"type\":0"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));

        // 未进入业务层：无任何券写入
        assertThat(countVouchersForShop(shopId)).isEqualTo(before);
    }

    // ==================================================================
    // B. 正常：普通券 type=0
    // ==================================================================

    @Test
    @DisplayName("B. type=0 普通券 -> 200 成功，落库 type=0 且归属该店铺")
    void plainVoucherTypeZeroCreated() throws Exception {
        ResultActions actions = mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainPayload(shopId, 0, "普通券")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());
        long voucherId = extractVoucherId(actions);

        assertThat(dbVoucherType(voucherId)).isEqualTo(0);
        assertThat(dbVoucherShopId(voucherId)).isEqualTo(shopId);
    }

    // ==================================================================
    // C. 类型契约：POST /vouchers + type=1 -> 拒绝且零副作用
    // ==================================================================

    @Test
    @DisplayName("C. POST /vouchers type=1 -> 200 + 30007，且无 tb_voucher / 无 seckill 行 / 无 Redis 库存")
    void plainEndpointRejectsSeckillType() throws Exception {
        long vouchersBefore = countVouchersForShop(shopId);
        long seckillRowsBefore = countAllSeckillRows();
        Set<String> stockKeysBefore = stockKeys();

        mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainPayload(shopId, 1, "越界秒杀券")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30007"))
                .andExpect(jsonPath("$.errorMsg").value("优惠券类型不匹配"));

        // 零副作用：券 / 秒杀行 / Redis 秒杀库存 key 集合均不变
        assertThat(countVouchersForShop(shopId)).isEqualTo(vouchersBefore);
        assertThat(countAllSeckillRows()).isEqualTo(seckillRowsBefore);
        assertThat(stockKeys()).isEqualTo(stockKeysBefore);
    }

    @Test
    @DisplayName("C2. POST /vouchers 非 0 类型（type=2）同样被拒 -> 30007，无写入")
    void plainEndpointRejectsUnknownType() throws Exception {
        long before = countVouchersForShop(shopId);

        mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainPayload(shopId, 2, "未知类型券")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("30007"));

        assertThat(countVouchersForShop(shopId)).isEqualTo(before);
    }

    // ==================================================================
    // D. 秒杀入口仍然正常（回归：type=1 -> 券 + 秒杀行 + Redis 库存）
    // ==================================================================

    @Test
    @DisplayName("D. POST /vouchers/seckill-vouchers type=1 -> 200，券 + 秒杀行 + Redis 库存均写入")
    void seckillEndpointStillWorks() throws Exception {
        ResultActions actions = mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillPayload(shopId, 1, "正常秒杀券", 66)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());
        long voucherId = extractVoucherId(actions);

        assertThat(dbVoucherType(voucherId)).isEqualTo(1);
        assertThat(countSeckillRow(voucherId)).isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId)).isEqualTo("66");
    }

    // ==================================================================
    // E. 秒杀券不得走同步购买入口（P0 回归：该入口会绕过时间窗与库存预扣）
    // ==================================================================

    @Test
    @DisplayName("E. POST /voucher-orders/purchase-orders 买秒杀券 -> 30007，零订单、库存不动")
    void purchaseEndpointRejectsSeckillVoucher() throws Exception {
        long voucherId = extractVoucherId(mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillPayload(shopId, 1, "秒杀券不可同步购买", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        mockMvc.perform(post("/voucher-orders/purchase-orders")
                        .header(AUTH_HEADER, merchantToken)
                        .param("voucherId", String.valueOf(voucherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("30007"));

        assertThat(countOrders(voucherId)).isZero();
        assertThat(dbSeckillStock(voucherId)).isEqualTo(5);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String plainPayload(Long shopId, int type, String title) {
        return "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":" + type
                + "}";
    }

    private String seckillPayload(Long shopId, int type, String title, int stock) {
        return "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + TITLE_PREFIX + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":" + type + ","
                + "\"stock\":" + stock + ","
                + "\"beginTime\":\"2026-09-21T10:00:00\","
                + "\"endTime\":\"2026-12-31T23:59:59\""
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

    private long extractVoucherId(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdVoucherIds.add(id);
        return id;
    }

    private Long dbOwnerId(long shopId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tb_shop WHERE id = ?", Long.class, shopId);
    }

    private Integer dbVoucherType(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT type FROM tb_voucher WHERE id = ?", Integer.class, voucherId);
    }

    private Long dbVoucherShopId(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT shop_id FROM tb_voucher WHERE id = ?", Long.class, voucherId);
    }

    private long countVouchersForShop(long shopId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE shop_id = ?", Long.class, shopId);
        return n == null ? 0 : n;
    }

    private long countOrders(long voucherId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?", Long.class, voucherId);
        return n == null ? 0 : n;
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

    private long countAllSeckillRows() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher", Long.class);
        return n == null ? 0 : n;
    }

    private Set<String> stockKeys() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.SECKILL_STOCK_KEY + "*");
        return keys == null ? Set.of() : keys;
    }
}
