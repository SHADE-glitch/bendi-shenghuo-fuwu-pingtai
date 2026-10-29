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
 * 店铺 Owner 归属校验测试（Phase 2.2.3.3 · 跨商家隔离）
 * <p>
 * 验证 {@code MerchantShopGuard} 接入 {@code PUT /shops/{id}} 后：
 * <b>商家只能修改自己拥有的店铺</b>，且越权请求<b>不会触达 updateShop</b>（数据库内容不变）。
 * </p>
 *
 * <h3>权限矩阵</h3>
 * <table border="1">
 *   <tr><th>Case</th><th>身份</th><th>目标</th><th>预期</th></tr>
 *   <tr><td>1</td><td>商家 A</td><td>Shop A（自己的）</td><td>200 成功</td></tr>
 *   <tr><td>2</td><td>商家 B</td><td>Shop B（自己的）</td><td>200 成功</td></tr>
 *   <tr><td>3</td><td>商家 A</td><td>Shop B（他人的）</td><td>403 + 40004，Shop B 数据不变</td></tr>
 *   <tr><td>4</td><td>商家 B</td><td>Shop A（他人的）</td><td>403 + 40004，Shop A 数据不变</td></tr>
 *   <tr><td>5</td><td>role=0</td><td>任意店铺</td><td>403 + 40003（WriteAuthInterceptor 阶段拒绝）</td></tr>
 *   <tr><td>6</td><td>匿名</td><td>任意店铺</td><td>401 + 10001</td></tr>
 *   <tr><td>7</td><td>商家 A</td><td>不存在的 shopId</td><td>40001（<b>而非</b> 40004）</td></tr>
 * </table>
 *
 * <p>自建自清：店铺由测试自己创建，tearDown 精确删除（含 Redis 缓存），不触碰种子店铺。</p>
 */
@DisplayName("店铺 Owner 归属校验（跨商家隔离 / 越权不落库）")
class ShopOwnerPermissionTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final String ADDR_A = "集成测试归属权限A";
    private static final String ADDR_B = "集成测试归属权限B";
    /** 初始营业时间，用于比对越权请求是否真的没改库 */
    private static final String HOURS_INIT = "10:00-22:00";
    private static final String HOURS_HACKED = "00:00-00:01";

    private Long merchantAId;
    private Long merchantBId;
    private Long plainUserId;
    private String merchantAToken;
    private String merchantBToken;
    private String plainToken;

    private Long shopAId;
    private Long shopBId;

    private final List<Long> createdShopIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        merchantAId = createTestUser("TestShopPermA_", "a");
        merchantBId = createTestUser("TestShopPermB_", "b");
        plainUserId = createTestUser("TestShopPermPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestShopPermA_a");
        merchantBToken = loginAsMerchant(merchantBId, "TestShopPermB_b");
        plainToken = loginAs(plainUserId, "TestShopPermPlain_p"); // role=0

        // A、B 各自创建一家店铺（owner_id 由服务端注入）
        shopAId = createShop(merchantAToken, "商家A店铺", ADDR_A);
        shopBId = createShop(merchantBToken, "商家B店铺", ADDR_B);

        // 前置断言：归属确实写对了，后续越权断言才有意义
        assertThat(dbOwnerId(shopAId)).isEqualTo(merchantAId);
        assertThat(dbOwnerId(shopBId)).isEqualTo(merchantBId);
    }

    @AfterEach
    void tearDown() {
        for (Long shopId : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", shopId);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        }
        createdShopIds.clear();
        // 兜底：断言中途失败时按专用地址清理
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
    // Case 1 / 2：改自己的店铺 -> 成功
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 修改自己的 Shop A -> 200 成功且已落库，owner 不变")
    void merchantACanUpdateOwnShop() throws Exception {
        putShop(merchantAToken, shopAId, HOURS_HACKED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbHours(shopAId)).isEqualTo(HOURS_HACKED);
        // Phase 2.2.3.5.1：请求体未携带 ownerId（等价 ownerId=null），归属字段必须保持原值
        assertThat(dbOwnerId(shopAId)).isEqualTo(merchantAId);
    }

    @Test
    @DisplayName("Case2 商家B 修改自己的 Shop B -> 200 成功且已落库，owner 不变")
    void merchantBCanUpdateOwnShop() throws Exception {
        putShop(merchantBToken, shopBId, HOURS_HACKED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(dbHours(shopBId)).isEqualTo(HOURS_HACKED);
        assertThat(dbOwnerId(shopBId)).isEqualTo(merchantBId);
    }

    // ==================================================================
    // Case 3 / 4：改别人的店铺 -> 403 + 40004，且数据库不变
    // ==================================================================

    @Test
    @DisplayName("Case3 商家A 修改 B 的 Shop B -> 403 + 40004，且 Shop B 数据不变")
    void merchantACannotUpdateShopB() throws Exception {
        assertThat(dbHours(shopBId)).isEqualTo(HOURS_INIT); // 前置：初始值

        putShop(merchantAToken, shopBId, HOURS_HACKED)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        // 关键：越权请求没有到达 updateShop
        assertThat(dbHours(shopBId)).isEqualTo(HOURS_INIT);
    }

    @Test
    @DisplayName("Case4 商家B 修改 A 的 Shop A -> 403 + 40004，且 Shop A 数据不变")
    void merchantBCannotUpdateShopA() throws Exception {
        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);

        putShop(merchantBToken, shopAId, HOURS_HACKED)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"))
                .andExpect(jsonPath("$.errorMsg").value("无权操作该店铺"));

        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);
    }

    // ==================================================================
    // Case 5 / 6：非商家 / 匿名 -> 在拦截器阶段被拒（不进入 owner 查询）
    // ==================================================================

    @Test
    @DisplayName("Case5 role=0 修改商家店铺 -> 403 + 40003（WriteAuthInterceptor 阶段拒绝）")
    void plainUserCannotUpdateShop() throws Exception {
        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);

        putShop(plainToken, shopAId, HOURS_HACKED)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);
    }

    @Test
    @DisplayName("Case6 匿名修改店铺 -> 401 + 10001")
    void anonymousCannotUpdateShop() throws Exception {
        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);

        mockMvc.perform(put("/shops/" + shopAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openHours\":\"" + HOURS_HACKED + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));

        assertThat(dbHours(shopAId)).isEqualTo(HOURS_INIT);
    }

    // ==================================================================
    // Case 7：店铺不存在 -> 40001，而不是 40004
    // ==================================================================

    @Test
    @DisplayName("Case7 商家A 修改不存在的店铺 -> 40001（区分「不存在」与「无权限」）")
    void merchantUpdateUnknownShopReturnsNotFoundCode() throws Exception {
        putShop(merchantAToken, 999999999L, HOURS_HACKED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40001"))
                .andExpect(jsonPath("$.errorMsg").value("店铺不存在"));
    }

    // ==================================================================
    // Case 8：归属字段不可经普通 update 请求改写（Phase 2.2.3.5.1）
    //   注意：这不是「跨 owner」用例（已由 Case3/4 覆盖）。
    //   本用例验证「即便是店铺 owner 本人，也不能通过普通 update 改写 owner_id」。
    // ==================================================================

    @Test
    @DisplayName("Case8 商家A 携带 ownerId=B 更新自己的 Shop A -> 200，owner_id 仍为 A，普通字段正常更新")
    void ownerCannotReassignOwnerIdViaUpdate() throws Exception {
        assertThat(dbOwnerId(shopAId)).isEqualTo(merchantAId); // 前置：归属确为 A

        String body = "{"
                + "\"name\":\"篡改归属测试店铺A\","
                + "\"openHours\":\"" + HOURS_HACKED + "\","
                + "\"ownerId\":" + merchantBId
                + "}";

        mockMvc.perform(put("/shops/" + shopAId)
                        .header(AUTH_HEADER, merchantAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 关键：owner_id 未被请求体改写（仍为 A，而不是 B）
        assertThat(dbOwnerId(shopAId)).isEqualTo(merchantAId);
        // 普通字段仍按请求正常更新 —— 证明「保护 owner_id」没有破坏整个 update
        assertThat(dbName(shopAId)).isEqualTo("篡改归属测试店铺A");
        assertThat(dbHours(shopAId)).isEqualTo(HOURS_HACKED);
    }

    // ==================================================================
    // 补充：历史店铺（owner_id = NULL）无人拥有 -> 40004
    // ==================================================================

    @Test
    @DisplayName("补充 商家A 修改历史店铺(owner_id=NULL) -> 403 + 40004")
    void merchantCannotUpdateUnownedHistoricalShop() throws Exception {
        // 种子店铺 1 的 owner_id 为 NULL，任何商家都不应能改
        Object seedOwner = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM tb_shop WHERE id = 1", Object.class);
        assertThat(seedOwner).isNull();
        String before = dbHours(1L);

        putShop(merchantAToken, 1L, HOURS_HACKED)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40004"));

        assertThat(dbHours(1L)).isEqualTo(before);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private org.springframework.test.web.servlet.ResultActions putShop(String token, Long shopId, String hours)
            throws Exception {
        return mockMvc.perform(put("/shops/" + shopId)
                .header(AUTH_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"openHours\":\"" + hours + "\"}"));
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
                                + "\"openHours\":\"" + HOURS_INIT + "\""
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdShopIds.add(id);
        return id;
    }

    private Long dbOwnerId(long shopId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tb_shop WHERE id = ?", Long.class, shopId);
    }

    private String dbHours(long shopId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(open_hours, '') FROM tb_shop WHERE id = ?", String.class, shopId);
    }

    private String dbName(long shopId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(name, '') FROM tb_shop WHERE id = ?", String.class, shopId);
    }
}
