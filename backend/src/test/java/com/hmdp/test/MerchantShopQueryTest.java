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
 * Merchant「我的店铺」查询测试（Phase 3.1 · {@code GET /shops/my}）
 * <p>
 * 验证商家私有查询接口：
 * <ul>
 *   <li>查询条件取<b>当前登录态</b>，不接受客户端 ownerId；</li>
 *   <li>Merchant A / B 之间相互隔离，互不可见；</li>
 *   <li>匿名 401 + 10001、普通用户 403 + 40003；</li>
 *   <li>{@code owner_id = NULL} 的历史店铺不泄漏给任何商家；</li>
 *   <li>返回结果与数据库 {@code WHERE owner_id = 当前用户} 完全一致。</li>
 * </ul>
 * </p>
 *
 * <h3>权限矩阵</h3>
 * <table border="1">
 *   <tr><th>Case</th><th>身份</th><th>预期</th></tr>
 *   <tr><td>1</td><td>商家 A</td><td>200，仅含 A 的店铺</td></tr>
 *   <tr><td>2</td><td>商家 B</td><td>200，仅含 B 的店铺</td></tr>
 *   <tr><td>3</td><td>商家 A（拥有多家）</td><td>200，返回 A 的全部店铺，仍不含 B 的</td></tr>
 *   <tr><td>4</td><td>role=0 普通用户</td><td>403 + 40003</td></tr>
 *   <tr><td>5</td><td>匿名</td><td>401 + 10001</td></tr>
 *   <tr><td>6</td><td>历史店铺 owner_id=NULL</td><td>不出现在任何商家的列表</td></tr>
 * </table>
 *
 * <p>自建自清：商家 / 店铺由测试创建，tearDown 精确删除（含 Redis 缓存），不触碰种子数据。</p>
 */
@DisplayName("Merchant 我的店铺查询（GET /shops/my）")
class MerchantShopQueryTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final long HISTORICAL_SHOP_ID = 1L; // 种子店铺，owner_id = NULL
    private static final String ADDR_A = "集成测试我的店铺A";
    private static final String ADDR_B = "集成测试我的店铺B";
    private static final String HOURS = "10:00-22:00";

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
        merchantAId = createTestUser("TestMyShopA_", "a");
        merchantBId = createTestUser("TestMyShopB_", "b");
        plainUserId = createTestUser("TestMyShopPlain_", "p");

        merchantAToken = loginAsMerchant(merchantAId, "TestMyShopA_a");
        merchantBToken = loginAsMerchant(merchantBId, "TestMyShopB_b");
        plainToken = loginAs(plainUserId, "TestMyShopPlain_p"); // role=0

        shopAId = createShop(merchantAToken, "我的店铺测试A", ADDR_A);
        shopBId = createShop(merchantBToken, "我的店铺测试B", ADDR_B);

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
    // Case 1 / 2：商家只能看到自己的店铺
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A 查询我的店铺 -> 200，仅含自己的 Shop A")
    void merchantASeesOnlyOwnShops() throws Exception {
        ResultActions actions = getMyShops(merchantAToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        List<String> ids = extractIds(actions);

        assertThat(ids).containsExactly(String.valueOf(shopAId));
        assertThat(ids).doesNotContain(String.valueOf(shopBId));
        // 与数据库 WHERE owner_id = A 完全一致
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbShopIdsByOwner(merchantAId));
    }

    @Test
    @DisplayName("Case2 商家B 查询我的店铺 -> 200，仅含自己的 Shop B")
    void merchantBSeesOnlyOwnShops() throws Exception {
        ResultActions actions = getMyShops(merchantBToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        List<String> ids = extractIds(actions);

        assertThat(ids).containsExactly(String.valueOf(shopBId));
        assertThat(ids).doesNotContain(String.valueOf(shopAId));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbShopIdsByOwner(merchantBId));
    }

    // ==================================================================
    // Case 3：商家拥有多家店铺时全部返回，且仍不含他人的
    // ==================================================================

    @Test
    @DisplayName("Case3 商家A 拥有两家店铺 -> 200，返回全部 A 的店铺，仍不含 B 的")
    void merchantSeesAllOwnShopsWhenMultiple() throws Exception {
        long shopA2Id = createShop(merchantAToken, "我的店铺测试A2", ADDR_A + "2");

        List<String> ids = extractIds(getMyShops(merchantAToken).andExpect(status().isOk()));

        assertThat(ids).containsExactlyInAnyOrder(String.valueOf(shopAId), String.valueOf(shopA2Id));
        assertThat(ids).doesNotContain(String.valueOf(shopBId));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(dbShopIdsByOwner(merchantAId));
    }

    // ==================================================================
    // Case 4 / 5：非商家 / 匿名 -> 在身份校验阶段被拒
    // ==================================================================

    @Test
    @DisplayName("Case4 role=0 查询我的店铺 -> 403 + 40003")
    void plainUserForbidden() throws Exception {
        mockMvc.perform(get("/shops/my").header(AUTH_HEADER, plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));
    }

    @Test
    @DisplayName("Case5 匿名查询我的店铺 -> 401 + 10001")
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(get("/shops/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    // ==================================================================
    // Case 6：owner_id=NULL 的历史店铺不得泄漏给任何商家
    // ==================================================================

    @Test
    @DisplayName("Case6 历史店铺(owner_id=NULL) 不出现在任何商家的我的店铺中")
    void historicalNullOwnerShopNotLeaked() throws Exception {
        assertThat(dbOwnerId(HISTORICAL_SHOP_ID)).isNull();

        List<String> idsA = extractIds(getMyShops(merchantAToken).andExpect(status().isOk()));
        List<String> idsB = extractIds(getMyShops(merchantBToken).andExpect(status().isOk()));

        assertThat(idsA).doesNotContain(String.valueOf(HISTORICAL_SHOP_ID));
        assertThat(idsB).doesNotContain(String.valueOf(HISTORICAL_SHOP_ID));
    }

    // ==================================================================
    // 补充：返回项全部归属当前商家（不泄漏他人 owner_id）
    // ==================================================================

    @Test
    @DisplayName("补充 返回列表中的每一项 ownerId 均为当前商家 id")
    void everyReturnedShopBelongsToCurrentMerchant() throws Exception {
        String body = getMyShops(merchantAToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Object> owners = JsonPath.read(body, "$.data[*].ownerId");
        assertThat(owners).isNotEmpty();
        for (Object owner : owners) {
            assertThat(String.valueOf(owner)).isEqualTo(String.valueOf(merchantAId));
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ResultActions getMyShops(String token) throws Exception {
        return mockMvc.perform(get("/shops/my").header(AUTH_HEADER, token));
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

    private Long dbOwnerId(long shopId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tb_shop WHERE id = ?", Long.class, shopId);
    }

    /** 数据库真值：该商家拥有的店铺 id 集合（字符串形式，与 API 的 Long->String 对齐） */
    private List<String> dbShopIdsByOwner(long ownerId) {
        return jdbcTemplate.queryForList(
                        "SELECT id FROM tb_shop WHERE owner_id = ?", Long.class, ownerId)
                .stream().map(String::valueOf).collect(Collectors.toList());
    }
}
