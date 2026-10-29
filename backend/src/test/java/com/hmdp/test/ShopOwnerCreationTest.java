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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家创建店铺写入 owner_id 测试（Phase 2.2.3.2）
 * <p>
 * 验证核心不变量：<b>店铺归属由服务端从登录态注入，前端无法决定也无法伪造</b>。
 * 所有断言最终都落到数据库 {@code tb_shop.owner_id}，而非仅校验内存实体。
 * </p>
 *
 * <h3>覆盖矩阵</h3>
 * <table border="1">
 *   <tr><th>Case</th><th>身份</th><th>请求</th><th>数据库预期</th></tr>
 *   <tr><td>1</td><td>商家 A(role=1)</td><td>POST /shops</td><td>owner_id = A.id</td></tr>
 *   <tr><td>2</td><td>商家 A</td><td>body 不含 ownerId</td><td>owner_id = A.id（后端注入）</td></tr>
 *   <tr><td>3</td><td>商家 A</td><td>body 伪造 ownerId=其他用户</td><td>owner_id = A.id（伪造被覆盖）</td></tr>
 *   <tr><td>4</td><td>role=0</td><td>POST /shops</td><td>403 + 40003，不落库</td></tr>
 *   <tr><td>5</td><td>匿名</td><td>POST /shops</td><td>401 + 10001，不落库</td></tr>
 *   <tr><td>6</td><td>—</td><td>—</td><td>历史 1290 家店铺 owner_id 仍为 NULL</td></tr>
 * </table>
 *
 * <p>自建自清：只写测试自建店铺，teardown 精确删除（含 Redis 店铺缓存），不触碰种子店铺。</p>
 */
@DisplayName("商家创建店铺写入 owner_id（服务端注入 / 不可伪造）")
class ShopOwnerCreationTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final long SEED_SHOP_ID = 1L;
    /** 迁移时既有的历史店铺数量 */
    private static final int HISTORICAL_SHOP_COUNT = 1290;
    /** 本类专用地址标记：tearDown 兜底清理，避免断言中途失败导致脏数据残留 */
    private static final String TEST_ADDRESS = "集成测试归属创建地址";

    private Long merchantAId;
    private Long otherUserId;
    private String merchantAToken;

    private final List<Long> createdShopIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        merchantAId = createTestUser("TestShopOwnerCreate_", "merchantA");
        otherUserId = createTestUser("TestShopOwnerCreate_", "other");
        merchantAToken = loginAsMerchant(merchantAId, "TestShopOwnerCreate_merchantA");
    }

    @AfterEach
    void tearDown() {
        for (Long shopId : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", shopId);
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        }
        createdShopIds.clear();
        // 兜底：若断言在拿到 id 之前失败，按本类专用地址标记清理，防止脏数据残留
        jdbcTemplate.update("DELETE FROM tb_shop WHERE address = ?", TEST_ADDRESS);
        logout(merchantAToken);
        for (Long uid : new Long[]{merchantAId, otherUserId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
    }

    // ==================================================================
    // Case 1 / 2 / 3：商家创建，owner 由服务端注入
    // ==================================================================

    @Test
    @DisplayName("Case1 商家A创建店铺 -> 数据库 owner_id = 商家A.id")
    void merchantCreateWritesOwnOwnerId() throws Exception {
        long shopId = createShop(merchantAToken, shopPayload("商家A店铺", null));

        assertThat(dbOwnerId(shopId)).isEqualTo(merchantAId);
    }

    @Test
    @DisplayName("Case2 请求 body 不含 ownerId -> 仍成功，owner_id = 商家A.id（后端注入）")
    void createWithoutOwnerIdInBodyStillInjectsOwner() throws Exception {
        String payload = shopPayload("商家A店铺-无owner字段", null);
        assertThat(payload).doesNotContain("ownerId"); // 证明请求体确实没带该字段

        long shopId = createShop(merchantAToken, payload);

        assertThat(dbOwnerId(shopId)).isEqualTo(merchantAId);
    }

    @Test
    @DisplayName("Case3 请求 body 伪造 ownerId=其他用户 -> 数据库仍为商家A.id（伪造被覆盖）")
    void forgedOwnerIdIsOverwritten() throws Exception {
        // @RequestBody 直接绑定 Shop 实体，Jackson 会把 ownerId 绑进实体 —— 必须被服务端覆盖
        long shopId = createShop(merchantAToken, shopPayload("商家A店铺-伪造owner", otherUserId));

        Long actualOwner = dbOwnerId(shopId);
        assertThat(actualOwner).isEqualTo(merchantAId);
        assertThat(actualOwner).isNotEqualTo(otherUserId);
    }

    // ==================================================================
    // Case 4 / 5：非商家不得进入业务层（保持 2.2.2 行为）
    // ==================================================================

    @Test
    @DisplayName("Case4 role=0 创建 -> 403 + 40003，且不落库")
    void plainUserCreateStillForbidden() throws Exception {
        String plainToken = loginAs(otherUserId, "TestShopOwnerCreate_other");
        int before = shopCount();
        try {
            mockMvc.perform(post("/shops")
                            .header(AUTH_HEADER, plainToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(shopPayload("普通用户越权店铺", null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("40003"))
                    .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));

            assertThat(shopCount()).isEqualTo(before); // 未进入业务层，无落库
        } finally {
            logout(plainToken);
        }
    }

    @Test
    @DisplayName("Case5 匿名创建 -> 401 + 10001，且不落库")
    void anonymousCreateStillUnauthorized() throws Exception {
        int before = shopCount();

        mockMvc.perform(post("/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shopPayload("匿名越权店铺", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));

        assertThat(shopCount()).isEqualTo(before);
    }

    // ==================================================================
    // Case 6：历史店铺不受影响
    // ==================================================================

    @Test
    @DisplayName("Case6 历史店铺 owner_id 仍为 NULL，未被批量补 owner")
    void historicalShopsKeepNullOwner() {
        Integer nullOwnerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_shop WHERE owner_id IS NULL", Integer.class);
        assertThat(nullOwnerCount).isGreaterThanOrEqualTo(HISTORICAL_SHOP_COUNT);

        Object seedOwner = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM tb_shop WHERE id = ?", Object.class, SEED_SHOP_ID);
        assertThat(seedOwner).isNull();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private long createShop(String token, String payload) throws Exception {
        String body = mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        long id = Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data")));
        createdShopIds.add(id);
        return id;
    }

    /** 以数据库为唯一事实来源读取 owner_id */
    private Long dbOwnerId(long shopId) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM tb_shop WHERE id = ?", Long.class, shopId);
    }

    private int shopCount() {
        Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_shop", Integer.class);
        return c == null ? 0 : c;
    }

    /**
     * tb_shop NOT NULL 列：name/type_id/images/address/x/y/sold/comments/score
     *
     * @param forgedOwnerId 非 null 时写入 ownerId 字段，用于验证伪造被覆盖
     */
    private String shopPayload(String name, Long forgedOwnerId) {
        StringBuilder sb = new StringBuilder("{")
                .append("\"name\":\"").append(name).append("\",")
                .append("\"typeId\":").append(SEED_TYPE_ID).append(",")
                .append("\"images\":\"[\\\"https://example.com/test-shop.jpg\\\"]\",")
                .append("\"address\":\"").append(TEST_ADDRESS).append("\",")
                .append("\"x\":120.1500,")
                .append("\"y\":30.3100,")
                .append("\"sold\":0,")
                .append("\"comments\":0,")
                .append("\"score\":50");
        if (forgedOwnerId != null) {
            sb.append(",\"ownerId\":").append(forgedOwnerId);
        }
        return sb.append("}").toString();
    }
}
