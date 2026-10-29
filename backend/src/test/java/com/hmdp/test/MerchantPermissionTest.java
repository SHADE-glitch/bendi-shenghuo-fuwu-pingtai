package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家写权限测试（Phase 2.2.2 · role 校验）
 * <p>
 * 覆盖 {@code /shops/**}、{@code /vouchers/**} 写接口的角色校验，以及
 * <b>读接口</b>与<b>消费者写接口</b>不受影响的双向回归。
 * </p>
 *
 * <h3>权限矩阵</h3>
 * <table border="1">
 *   <tr><th>身份</th><th>请求</th><th>预期</th></tr>
 *   <tr><td>未登录</td><td>POST/PUT /shops、/vouchers</td><td>401 + 10001（原语义不变）</td></tr>
 *   <tr><td>role=0</td><td>POST/PUT /shops、/vouchers</td><td>403 + 40003「仅商家可操作」</td></tr>
 *   <tr><td>role=1</td><td>POST/PUT /shops、/vouchers</td><td>不被角色拦截（进入业务层）</td></tr>
 *   <tr><td>匿名</td><td>GET /shops、/shops/{id}、/vouchers</td><td>200（读接口不受影响）</td></tr>
 *   <tr><td>role=0</td><td>POST /shop-comments、PUT /users/info</td><td>200（消费者写接口不受影响）</td></tr>
 * </table>
 *
 * <h3>关于「权限层放行」与「业务层成功」的区分</h3>
 * {@code POST /vouchers/seckill-vouchers} 刻意使用<b>缺必填字段</b>的请求体，且不写任何秒杀数据：
 * <ul>
 *   <li>role=0 → 拦截器先于参数绑定执行 → 40003（证明被权限层拒绝）；</li>
 *   <li>role=1 → 拦截器放行 → 参数校验失败 → 10008（证明<b>权限层已放行</b>，业务失败原因与权限无关）。</li>
 * </ul>
 * 同一份请求体、不同角色得到不同错误码，是「权限层」与「业务层」分离的直接证据。
 *
 * <h3>本阶段边界</h3>
 * 本类聚焦 role 校验。Phase 2.2.3.4 起 {@code POST /vouchers}、
 * {@code POST /vouchers/seckill-vouchers} 在 role 校验之后还会经过
 * {@code MerchantShopGuard} 归属校验，因此「role=1 业务层成功」用例
 * 必须使用该商家自己拥有的店铺（见 {@code merchantPostVoucherAllowed}）。
 * 归属校验的完整矩阵由 {@code VoucherOwnerPermissionTest} 覆盖。
 */
@DisplayName("商家写权限（role 校验 + 读/消费者写回归）")
class MerchantPermissionTest extends WebIntegrationTestBase {

    private static final int SEED_TYPE_ID = 1;
    private static final long SEED_SHOP_ID = 1L;

    private Long plainUserId;
    private Long merchantUserId;
    private String plainToken;
    private String merchantToken;

    /** 本测试创建的数据，teardown 精确清理；绝不触碰种子数据 */
    private final List<Long> createdShopIds = new ArrayList<>();
    private final List<Long> createdVoucherIds = new ArrayList<>();
    private final List<Long> createdCommentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        plainUserId = createTestUser("TestMerchantPerm_", "plain");
        merchantUserId = createTestUser("TestMerchantPerm_", "merchant");
        plainToken = loginAs(plainUserId, "TestMerchantPerm_plain");            // role=0
        merchantToken = loginAsMerchant(merchantUserId, "TestMerchantPerm_merchant"); // role=1
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdCommentIds) {
            jdbcTemplate.update("DELETE FROM tb_shop_comments WHERE id = ?", id);
        }
        createdCommentIds.clear();
        for (Long id : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", id);
        }
        createdVoucherIds.clear();
        for (Long id : createdShopIds) {
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", id);
        }
        createdShopIds.clear();

        logout(plainToken);
        logout(merchantToken);
        for (Long uid : new Long[]{plainUserId, merchantUserId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
    }

    // ==================================================================
    // A. 未登录：写接口仍为 401 + 10001（语义不变）
    // ==================================================================

    @Test
    @DisplayName("[未登录] POST /shops -> 401 + 10001")
    void anonymousPostShopUnauthorized() throws Exception {
        mockMvc.perform(post("/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShopPayload("匿名店铺")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    @Test
    @DisplayName("[未登录] PUT /shops/1 -> 401 + 10001")
    void anonymousPutShopUnauthorized() throws Exception {
        mockMvc.perform(put("/shops/" + SEED_SHOP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openHours\":\"10:00-22:00\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    @Test
    @DisplayName("[未登录] POST /vouchers -> 401 + 10001")
    void anonymousPostVoucherUnauthorized() throws Exception {
        mockMvc.perform(post("/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVoucherPayload("匿名券")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    @Test
    @DisplayName("[未登录] POST /vouchers/seckill-vouchers -> 401 + 10001")
    void anonymousPostSeckillVoucherUnauthorized() throws Exception {
        mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidVoucherPayload("匿名秒杀券")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    // ==================================================================
    // B. role=0：商家写接口被拒 -> 403 + 40003
    // ==================================================================

    @Test
    @DisplayName("[role=0] POST /shops -> 403 + 40003 仅商家可操作")
    void plainUserPostShopForbidden() throws Exception {
        mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShopPayload("普通用户越权店铺")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));
    }

    @Test
    @DisplayName("[role=0] PUT /shops/1 -> 403 + 40003（且在业务层之前被拒，未改动店铺）")
    void plainUserPutShopForbidden() throws Exception {
        String before = jdbcTemplate.queryForObject(
                "SELECT COALESCE(open_hours, '') FROM tb_shop WHERE id = ?", String.class, SEED_SHOP_ID);

        mockMvc.perform(put("/shops/" + SEED_SHOP_ID)
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openHours\":\"越权改营业时间\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40003"));

        String after = jdbcTemplate.queryForObject(
                "SELECT COALESCE(open_hours, '') FROM tb_shop WHERE id = ?", String.class, SEED_SHOP_ID);
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("[role=0] POST /vouchers -> 403 + 40003")
    void plainUserPostVoucherForbidden() throws Exception {
        mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVoucherPayload("普通用户越权券")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40003"))
                .andExpect(jsonPath("$.errorMsg").value("仅商家可操作"));
    }

    @Test
    @DisplayName("[role=0] POST /vouchers/seckill-vouchers -> 403 + 40003（拦截器先于参数校验）")
    void plainUserPostSeckillVoucherForbidden() throws Exception {
        mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidVoucherPayload("普通用户越权秒杀券")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("40003"));
    }

    // ==================================================================
    // C. role=1：商家写接口不被角色拦截
    // ==================================================================

    @Test
    @DisplayName("[role=1] POST /shops -> 200（权限层放行，业务层成功）")
    void merchantPostShopAllowed() throws Exception {
        String body = mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShopPayload("商家创建店铺")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString()) // 全局 Long -> String（JacksonConfig）
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        track(createdShopIds, body);
    }

    @Test
    @DisplayName("[role=1] PUT /shops/{id} -> 200（权限层放行，业务层成功）")
    void merchantPutShopAllowed() throws Exception {
        long shopId = createShopAsMerchant();

        mockMvc.perform(put("/shops/" + shopId)
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openHours\":\"08:00-20:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String hours = jdbcTemplate.queryForObject(
                "SELECT open_hours FROM tb_shop WHERE id = ?", String.class, shopId);
        org.assertj.core.api.Assertions.assertThat(hours).isEqualTo("08:00-20:00");
    }

    @Test
    @DisplayName("[role=1] POST /vouchers -> 200（权限层放行，业务层成功）")
    void merchantPostVoucherAllowed() throws Exception {
        // Phase 2.2.3.4 起新增券需通过 MerchantShopGuard 归属校验：
        // 必须使用「该商家自己拥有的店铺」，否则会在业务层之前被 40004 拒绝。
        long ownedShopId = createShopAsMerchant();

        String body = mockMvc.perform(post("/vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validVoucherPayload("商家创建券", ownedShopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        track(createdVoucherIds, body);
    }

    @Test
    @DisplayName("[role=1] POST /vouchers/seckill-vouchers -> 权限层放行（业务层 10008，非 40003）")
    void merchantPostSeckillVoucherPassesPermissionLayer() throws Exception {
        // 刻意使用缺必填字段的请求体：role=1 时拦截器放行，失败原因来自参数校验而非权限
        mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidVoucherPayload("商家秒杀券")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008")) // 参数校验失败，而非 40003
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.not("40003")));
    }

    // ==================================================================
    // D. 读接口回归：匿名 GET 不受影响
    // ==================================================================

    @Test
    @DisplayName("[匿名读] GET /shops/1 -> 200")
    void anonymousGetShopByIdAllowed() throws Exception {
        mockMvc.perform(get("/shops/" + SEED_SHOP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("[匿名读] GET /shops?typeId=1 -> 200")
    void anonymousGetShopListAllowed() throws Exception {
        mockMvc.perform(get("/shops").param("typeId", String.valueOf(SEED_TYPE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("[匿名读] GET /vouchers?shopId=1 -> 200")
    void anonymousGetVouchersAllowed() throws Exception {
        mockMvc.perform(get("/vouchers").param("shopId", String.valueOf(SEED_SHOP_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("[role=1 读] GET /shops/1 -> 200（商家读同样不受影响）")
    void merchantGetShopAllowed() throws Exception {
        mockMvc.perform(get("/shops/" + SEED_SHOP_ID).header(AUTH_HEADER, merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ==================================================================
    // E. 消费者写接口回归：role=0 仍可正常写（不被 merchant 校验波及）
    // ==================================================================

    @Test
    @DisplayName("[role=0 消费者写] POST /shop-comments -> 200（未被 40003 波及）")
    void plainUserConsumerShopCommentStillAllowed() throws Exception {
        String body = mockMvc.perform(post("/shop-comments")
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + SEED_SHOP_ID + ",\"content\":\"集成测试评价-权限回归\",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        track(createdCommentIds, body);
    }

    @Test
    @DisplayName("[role=0 消费者写] PUT /users/info -> 200（未被 40003 波及）")
    void plainUserConsumerUpdateUserInfoStillAllowed() throws Exception {
        mockMvc.perform(put("/users/info")
                        .header(AUTH_HEADER, plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"杭州\",\"introduce\":\"集成测试简介\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private long createShopAsMerchant() throws Exception {
        String body = mockMvc.perform(post("/shops")
                        .header(AUTH_HEADER, merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShopPayload("商家创建店铺")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return track(createdShopIds, body);
    }

    private long track(List<Long> bucket, String responseBody) {
        Object data = JsonPath.read(responseBody, "$.data");
        long id = Long.parseLong(String.valueOf(data));
        bucket.add(id);
        return id;
    }

    /** tb_shop 的 NOT NULL 列为 name/type_id/images/address/x/y/sold/comments/score，必须齐全 */
    private String validShopPayload(String name) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"typeId\":" + SEED_TYPE_ID + ","
                + "\"images\":\"[\\\"https://example.com/test-shop.jpg\\\"]\","
                + "\"address\":\"集成测试地址\","
                + "\"x\":120.1500,"
                + "\"y\":30.3100,"
                + "\"sold\":0,"
                + "\"comments\":0,"
                + "\"score\":50"
                + "}";
    }

    /**
     * tb_voucher 必填：title / pay_value / actual_value / type
     * <p>默认挂到种子店铺 1；仅用于「在拦截器阶段即被拒」的匿名/role=0 用例
     * （这些请求不会到达 Controller，shopId 取值不影响断言）。</p>
     */
    private String validVoucherPayload(String title) {
        return validVoucherPayload(title, SEED_SHOP_ID);
    }

    /** 指定 shopId 的券请求体（role=1 成功用例需传该商家自己拥有的店铺） */
    private String validVoucherPayload(String title, long shopId) {
        return "{"
                + "\"shopId\":" + shopId + ","
                + "\"title\":\"" + title + "\","
                + "\"payValue\":100,"
                + "\"actualValue\":200,"
                + "\"type\":0"
                + "}";
    }

    /** 缺 payValue/actualValue/type，用于证明「权限层放行后由参数校验拦截」 */
    private String invalidVoucherPayload(String title) {
        return "{\"shopId\":" + SEED_SHOP_ID + ",\"title\":\"" + title + "\"}";
    }
}
