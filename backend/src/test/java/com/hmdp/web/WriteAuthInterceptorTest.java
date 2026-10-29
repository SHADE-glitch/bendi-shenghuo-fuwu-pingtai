package com.hmdp.web;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写接口登录校验测试
 * <p>
 * 覆盖「读取匿名、写入需登录」：{@code /shops/**} 与 {@code /vouchers/**}
 * 的 GET 仍可匿名访问，但新增 / 修改必须登录。
 * </p>
 */
@DisplayName("写接口登录校验（/shops、/vouchers、/users 主页）")
class WriteAuthInterceptorTest extends WebIntegrationTestBase {

    @Test
    @DisplayName("匿名 POST /shops 返回 401（修复匿名可新建店铺）")
    void anonymousPostShopRejected() throws Exception {
        mockMvc.perform(post("/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名店铺\",\"typeId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    @Test
    @DisplayName("匿名 PUT /shops/{id} 返回 401（修复匿名可修改店铺）")
    void anonymousPutShopRejected() throws Exception {
        mockMvc.perform(put("/shops/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名修改\",\"typeId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    @Test
    @DisplayName("匿名 POST /vouchers 返回 401（修复匿名可建券）")
    void anonymousPostVoucherRejected() throws Exception {
        mockMvc.perform(post("/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"title\":\"匿名券\",\"payValue\":100,\"actualValue\":200,\"type\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("10001"));
    }

    @Test
    @DisplayName("匿名 POST /vouchers/seckill-vouchers 返回 401")
    void anonymousPostSeckillVoucherRejected() throws Exception {
        mockMvc.perform(post("/vouchers/seckill-vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"title\":\"匿名秒杀券\",\"payValue\":100,\"actualValue\":200,\"type\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("匿名 GET /shops/{id} 仍可访问（店铺详情保持匿名）")
    void anonymousGetShopAllowed() throws Exception {
        mockMvc.perform(get("/shops/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("匿名 GET /shops 列表仍可访问")
    void anonymousGetShopListAllowed() throws Exception {
        mockMvc.perform(get("/shops").param("typeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("匿名 GET /vouchers 仍可访问")
    void anonymousGetVouchersAllowed() throws Exception {
        mockMvc.perform(get("/vouchers").param("shopId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("OPTIONS 预检请求不被拦截")
    void optionsRequestAllowed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.options("/shops"))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code == 401) {
                        throw new AssertionError("OPTIONS 预检不应返回 401，实际 " + code);
                    }
                });
    }
}
