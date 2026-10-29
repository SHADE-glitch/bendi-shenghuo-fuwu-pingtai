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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant「我的优惠券」查询测试（Phase 3.2 · {@code GET /vouchers/my}）
 * <p>
 * 验证商家私有查询接口：
 * <ul>
 *   <li>查询范围取<b>当前登录态</b>：商家 → 其名下所有 Shop → 这些 Shop 下的 Voucher，
 *       不接受客户端 ownerId/shopId；</li>
 *   <li>Merchant A / B 之间相互隔离，互不可见；</li>
 *   <li>匿名 401 + 10001、普通用户 403 + 40003；</li>
 *   <li>商家拥有多家店铺时返回全部店铺下的券；</li>
 *   <li>管理视图<b>不过滤 status</b>：已下架（status=2）的券仍可见；</li>
 *   <li>秒杀券补充 {@code stock}/{@code beginTime}/{@code endTime}；</li>
 *   <li>无店铺的商家返回空列表；</li>
 *   <li>返回结果与数据库 {@code JOIN tb_shop ON owner_id} 完全一致。</li>
 * </ul>
 * </p>
 *
 * <p>自建自清：商家 / 店铺 / 券由测试创建，tearDown 精确删除（券先于店铺，满足 FK RESTRICT），
 * 并清理 Redis 秒杀库存与店铺缓存，不触碰种子数据。</p>
 */
@DisplayName("Merchant 我的优惠券查询（GET /vouchers/my）")
class MerchantVoucherQueryTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String ADDR_A = "集成测试我的券店铺A";
    private static final String ADDR_B = "集成测试我的券店铺B";
    private static final String ADDR_EMPTY = "集成测试我的券空店铺";
    private static final String HOURS = "10:00-22:00";
    private static final String TITLE_PREFIX = "IT-MyVoucher-";

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
        merchantAId = createTestUser("TestMyVoucherA_", "a");
        merchantBId = createTestUser("TestMyVoucherB_", "b");
        plainUserId = createTestUser("TestMyVoucherPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestMyVoucherA_a");
        merchantBToken = loginAsMerchant(merchantBId, "TestMyVoucherB_b");
        plainToken = loginAs(plainUserId, "TestMyVoucherPlain_p"); // role=0

        shopAId = createShop(merchantAToken, "我的券店铺A", ADDR_A);
        shopBId = createShop(merchantBToken, "我的券店铺B", ADDR_B);

        voucherAId = createPlainVoucher(merchantAToken, shopAId, "A的券");
        voucherBId = createPlainVoucher(merchantBToken, shopBId, "B的券");

        assertThat(dbVoucherShopId(voucherAId)).isEqualTo(shopAId);
        assertThat(dbVoucherShopId(voucherBId)).isEqualTo(shopBId);
    }

    @AfterEach
    void tearDown() {
        // 券先删（tb_voucher.shop_id 为 FK RESTRICT），tb_seckill_voucher 由 CASCADE 一并清除
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
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address IN (?, ?, ?)", ADDR_A, ADDR_B, ADDR_EMPTY);

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
    // Case 1 / 2：商家只能看到自己店铺下的券
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 查询我的优惠券 -> 200，仅含自己店铺的券，不含 B 的")
    void merchantASeesOnlyOwnVouchers() throws Exception {
        List<String> ids = extractIds(getMyVouchers(merchantAToken).andExpect(status().isOk()));

        assertThat(ids).contains(String.valueOf(voucherAId));
        assertThat(ids).doesNotContain(String.valueOf(voucherBId));
        // 与数据库 JOIN tb_shop ON owner_id 的真值完全一致
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbVoucherIdsByOwner(merchantAId));
    }

    @Test
    @DisplayName("Case2 商家B 查询我的优惠券 -> 200，仅含自己店铺的券，不含 A 的")
    void merchantBSeesOnlyOwnVouchers() throws Exception {
        List<String> ids = extractIds(getMyVouchers(merchantBToken).andExpect(status().isOk()));

        assertThat(ids).contains(String.valueOf(voucherBId));
        assertThat(ids).doesNotContain(String.valueOf(voucherAId));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbVoucherIdsByOwner(merchantBId));
    }

    // ==================================================================
    // Case 3：商家拥有多家店铺 -> 返回全部店铺下的券
    // ==================================================================

    @Test
    @DisplayName("Case3 商家A 拥有两家店铺 -> 200，返回两家店铺下的全部券，仍不含 B 的")
    void merchantSeesVouchersOfAllOwnShops() throws Exception {
        long shopA2Id = createShop(merchantAToken, "我的券店铺A2", ADDR_A + "2");
        long voucherA2Id = createPlainVoucher(merchantAToken, shopA2Id, "A的券2");

        List<String> ids = extractIds(getMyVouchers(merchantAToken).andExpect(status().isOk()));

        assertThat(ids).containsExactlyInAnyOrder(String.valueOf(voucherAId), String.valueOf(voucherA2Id));
        assertThat(ids).doesNotContain(String.valueOf(voucherBId));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbVoucherIdsByOwner(merchantAId));
    }

    // ==================================================================
    // Case 4 / 5：非商家 / 匿名 -> 在身份校验阶段被拒
    // ==================================================================

    @Test
    @DisplayName("Case4 role=0 查询我的优惠券 -> 403 + 40003")
    void plainUserForbidden() throws Exception {
        mockMvc.perform(get("/vouchers/my").header(AUTH_HEADER, plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));
    }

    @Test
    @DisplayName("Case5 匿名查询我的优惠券 -> 401 + 10001")
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(get("/vouchers/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    // ==================================================================
    // Case 6：管理视图不过滤 status —— 已下架（status=2）的券仍可见
    // ==================================================================

    @Test
    @DisplayName("Case6 已下架(status=2)的券仍出现在商家列表（消费者接口才过滤 status=1）")
    void offShelfVoucherStillVisibleToOwner() throws Exception {
        jdbcTemplate.update("UPDATE tb_voucher SET status = 2 WHERE id = ?", voucherAId);

        List<String> ids = extractIds(getMyVouchers(merchantAToken).andExpect(status().isOk()));
        assertThat(ids).contains(String.valueOf(voucherAId));

        // 消费者接口（status=1 过滤）此时不应再返回该券
        String consumerBody = mockMvc.perform(get("/vouchers").param("shopId", String.valueOf(shopAId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Object> consumerIds = JsonPath.read(consumerBody, "$.data[*].id");
        assertThat(consumerIds.stream().map(String::valueOf).collect(Collectors.toList()))
                .doesNotContain(String.valueOf(voucherAId));
    }

    // ==================================================================
    // Case 7：秒杀券补充 stock / beginTime / endTime
    // ==================================================================

    @Test
    @DisplayName("Case7 秒杀券 -> 返回 stock/beginTime/endTime（取自 tb_seckill_voucher）")
    void seckillVoucherIncludesStockAndTimes() throws Exception {
        long seckillId = createSeckillVoucher(merchantAToken, shopAId, "A的秒杀券", 66);

        String body = getMyVouchers(merchantAToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Object> stocks = JsonPath.read(body, "$.data[?(@.id == '" + seckillId + "')].stock");
        assertThat(stocks).containsExactly(66);

        List<Object> begins = JsonPath.read(body, "$.data[?(@.id == '" + seckillId + "')].beginTime");
        assertThat(begins).isNotEmpty();
        assertThat(String.valueOf(begins.get(0))).isNotBlank();
    }

    // ==================================================================
    // Case 8：无店铺的商家 -> 200 空列表（不得退化为「查询全部券」）
    // ==================================================================

    @Test
    @DisplayName("Case8 名下无店铺的商家 -> 200，空列表（不退化为全表）")
    void merchantWithoutShopsGetsEmptyList() throws Exception {
        Long lonelyMerchantId = createTestUser("TestMyVoucherLonely_", "l");
        String lonelyToken = loginAsMerchant(lonelyMerchantId, "TestMyVoucherLonely_l");
        try {
            String body = mockMvc.perform(get("/vouchers/my").header(AUTH_HEADER, lonelyToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Object> ids = JsonPath.read(body, "$.data");
            assertThat(ids).isEmpty();
        } finally {
            logout(lonelyToken);
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", lonelyMerchantId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", lonelyMerchantId);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ResultActions getMyVouchers(String token) throws Exception {
        return mockMvc.perform(get("/vouchers/my").header(AUTH_HEADER, token));
    }

    /** 从成功响应体提取 {@code $.data[*].id}（全局 Long->String，故为字符串集合） */
    private List<String> extractIds(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Object> raw = JsonPath.read(body, "$.data[*].id");
        return raw.stream().map(String::valueOf).collect(Collectors.toList());
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

    private Long dbVoucherShopId(long voucherId) {
        return jdbcTemplate.queryForObject("SELECT shop_id FROM tb_voucher WHERE id = ?", Long.class, voucherId);
    }

    /** 数据库真值：该商家名下所有店铺的券 id 集合（字符串形式，与 API 的 Long->String 对齐） */
    private List<String> dbVoucherIdsByOwner(long ownerId) {
        return jdbcTemplate.queryForList(
                        "SELECT v.id FROM tb_voucher v JOIN tb_shop s ON v.shop_id = s.id WHERE s.owner_id = ?",
                        Long.class, ownerId)
                .stream().map(String::valueOf).collect(Collectors.toList());
    }
}
