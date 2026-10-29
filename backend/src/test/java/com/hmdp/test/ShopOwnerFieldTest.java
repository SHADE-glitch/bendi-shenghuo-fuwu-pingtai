package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店铺归属字段 owner_id 测试（Phase 2.2.3.1 · 数据模型落地）
 * <p>
 * 本阶段只准备「店铺归属」数据模型，<b>不实现 owner 权限校验</b>，
 * 也不修改 {@code POST /shops} 创建逻辑。因此本测试只验证三件事：
 * </p>
 * <ol>
 *   <li>V16 迁移后 {@code tb_shop.owner_id} 存在，且类型与 {@code tb_user.id} <b>完全一致</b>、允许 NULL；</li>
 *   <li>存量历史店铺的 {@code owner_id} 全为 NULL（迁移未填充存量数据）；</li>
 *   <li>{@code Shop.ownerId} 能正确映射并序列化（写库后经 {@code GET /shops/{id}} 读出）。</li>
 * </ol>
 * <p>
 * 只读 + 自建自清：仅对测试自建的店铺行做写入，teardown 精确清理（含 Redis 店铺缓存）。
 * </p>
 */
@DisplayName("店铺归属字段 owner_id（列定义 / 存量 NULL / 实体映射）")
class ShopOwnerFieldTest extends WebIntegrationTestBase {

    /** 种子店铺，用于确认历史数据未被迁移改动 */
    private static final long SEED_SHOP_ID = 1L;
    /** V16 迁移时既有的历史店铺数量 */
    private static final int HISTORICAL_SHOP_COUNT = 1290;

    private Long ownerUserId;
    private final List<Long> createdShopIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long shopId : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", shopId);
            // 清理 queryById 写入的店铺缓存，避免残留影响后续测试
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        }
        createdShopIds.clear();
        if (ownerUserId != null) {
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", ownerUserId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", ownerUserId);
            ownerUserId = null;
        }
    }

    @Test
    @DisplayName("tb_shop.owner_id 存在，类型与 tb_user.id 完全一致，且允许 NULL、默认 NULL")
    void ownerColumnTypeMatchesUserId() {
        Map<String, Object> ownerCol = jdbcTemplate.queryForMap(
                "SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'tb_shop' AND column_name = 'owner_id'");
        Map<String, Object> userIdCol = jdbcTemplate.queryForMap(
                "SELECT DATA_TYPE, COLUMN_TYPE "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'tb_user' AND column_name = 'id'");

        // 最强的一致性断言：逐字段与 tb_user.id 对比
        assertThat(ownerCol.get("COLUMN_TYPE")).isEqualTo(userIdCol.get("COLUMN_TYPE"));
        assertThat(ownerCol.get("DATA_TYPE")).isEqualTo(userIdCol.get("DATA_TYPE"));
        assertThat(String.valueOf(ownerCol.get("DATA_TYPE")).toLowerCase()).isEqualTo("bigint");
        assertThat(String.valueOf(ownerCol.get("COLUMN_TYPE")).toLowerCase()).contains("unsigned");
        // 允许历史数据为 NULL
        assertThat(ownerCol.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(ownerCol.get("COLUMN_DEFAULT")).isNull();
    }

    @Test
    @DisplayName("存量历史店铺 owner_id 全为 NULL（迁移未填充任何归属）")
    void existingShopsHaveNullOwner() {
        // 历史店铺必须全部保持 NULL：迁移未做任何归属回填。
        // 注意：自 Phase 2.2.3.2 起 POST /shops 会写入 owner_id，
        // 故不能再断言「全库没有任何非 NULL owner」，只能断言历史店铺仍为 NULL。
        Integer nullOwnerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_shop WHERE owner_id IS NULL", Integer.class);
        assertThat(nullOwnerCount).isGreaterThanOrEqualTo(HISTORICAL_SHOP_COUNT);

        // 抽样确认种子店铺未被改动
        Object seedOwner = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM tb_shop WHERE id = ?", Object.class, SEED_SHOP_ID);
        assertThat(seedOwner).isNull();
    }

    @Test
    @DisplayName("Shop.ownerId 可映射并序列化：写入 owner_id 后 GET /shops/{id} 能读出")
    void shopEntityMapsAndSerializesOwnerId() throws Exception {
        ownerUserId = createTestUser("TestShopOwner_", "owner");

        jdbcTemplate.update(
                "INSERT INTO tb_shop (name, type_id, images, address, x, y, sold, comments, score, owner_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "集成测试归属店铺", 1L, "[\"https://example.com/owner-shop.jpg\"]", "集成测试地址",
                120.15, 30.31, 0, 0, 50, ownerUserId);
        Long shopId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_shop WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, "集成测试归属店铺");
        assertThat(shopId).isNotNull();
        createdShopIds.add(shopId);

        // 经读接口验证「数据库列 -> Shop.ownerId -> JSON」整条链路
        mockMvc.perform(get("/shops/" + shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("集成测试归属店铺"))
                .andExpect(jsonPath("$.data.ownerId").value(String.valueOf(ownerUserId))); // Long -> String（JacksonConfig）
    }
}
