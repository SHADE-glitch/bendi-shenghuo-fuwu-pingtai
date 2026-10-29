package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单状态查询归属校验测试（破冻修复项）
 * <p>
 * 口径：不存在或非本人订单统一返回 -1（NOT_FOUND），不泄露订单存在性，
 * 与订单写操作「越权按订单不存在应答」保持一致。
 * </p>
 */
@DisplayName("订单状态查询归属校验")
class OrderStatusAuthTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestOrderStatus_";
    /** 占位订单 id，使用远离业务 id 的固定值便于识别 */
    private static final long TEST_ORDER_ID = 8_888_888_888_888_001L;
    private static final long TEST_VOUCHER_ID = 1L;

    private Long ownerId;
    private Long otherId;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        ownerId = createTestUser(NICK_PREFIX, "owner");
        otherId = createTestUser(NICK_PREFIX, "other");
        ownerToken = loginAs(ownerId, NICK_PREFIX + "owner");
        otherToken = loginAs(otherId, NICK_PREFIX + "other");
        jdbcTemplate.update(
                "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) VALUES (?, ?, ?, 1, 1)",
                TEST_ORDER_ID, ownerId, TEST_VOUCHER_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id = ?", TEST_ORDER_ID);
        logout(ownerToken);
        logout(otherToken);
        for (Long uid : new Long[]{ownerId, otherId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
    }

    @Test
    @DisplayName("本人查询返回真实状态")
    void ownerSeesRealStatus() throws Exception {
        mockMvc.perform(get("/voucher-orders/" + TEST_ORDER_ID + "/status").header(AUTH_HEADER, ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("非本人查询按「不存在」应答（-1），不泄露订单存在性")
    void nonOwnerSeesNotFound() throws Exception {
        mockMvc.perform(get("/voucher-orders/" + TEST_ORDER_ID + "/status").header(AUTH_HEADER, otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(-1));
    }

    @Test
    @DisplayName("不存在的订单同样返回 -1")
    void unknownOrderReturnsNotFound() throws Exception {
        mockMvc.perform(get("/voucher-orders/123456789/status").header(AUTH_HEADER, ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(-1));
    }

    @Test
    @DisplayName("匿名查询返回 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/voucher-orders/" + TEST_ORDER_ID + "/status"))
                .andExpect(status().isUnauthorized());
    }
}
