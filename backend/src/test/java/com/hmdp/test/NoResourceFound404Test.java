package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不存在 HTTP 路由 / 静态资源（NoResourceFoundException）的全局处理测试
 * <p>
 * 覆盖 WebExceptionAdvice 对 NoResourceFoundException 的分流（404 + 40400），
 * 以及登录拦截器短路（未登录 401）与既有异常（参数校验）不发生回归。
 * </p>
 */
@DisplayName("不存在资源路由（NoResourceFound / 404 / 401 / 回归）")
class NoResourceFound404Test extends WebIntegrationTestBase {

    /** 完全不存在的路径，确保 Spring 抛 NoResourceFoundException */
    private static final String NOT_FOUND_PATH = "/definitely-not-exist-404";

    /** 新 handler 的专用错误码与文案（与 WebExceptionAdvice 保持一致） */
    private static final String NOT_FOUND_ERROR_CODE = "40400";
    private static final String NOT_FOUND_ERROR_MSG = "资源不存在";

    private String token;

    @AfterEach
    void tearDown() {
        logout(token);
        token = null;
    }

    @Test
    @DisplayName("已登录 GET 不存在路径 -> 404 + 40400")
    void authenticatedNotFound() throws Exception {
        token = loginAs(1L, "NoResource404用户");

        mockMvc.perform(get(NOT_FOUND_PATH).header(AUTH_HEADER, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(NOT_FOUND_ERROR_CODE))
                .andExpect(jsonPath("$.errorMsg").value(NOT_FOUND_ERROR_MSG));
    }

    @Test
    @DisplayName("已登录 POST 不存在路径 -> 404 + 40400")
    void authenticatedNotFoundPost() throws Exception {
        token = loginAs(1L, "NoResource404用户");

        mockMvc.perform(post(NOT_FOUND_PATH).header(AUTH_HEADER, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(NOT_FOUND_ERROR_CODE))
                .andExpect(jsonPath("$.errorMsg").value(NOT_FOUND_ERROR_MSG));
    }

    @Test
    @DisplayName("未登录 GET 不存在路径 -> 401 + 10001（LoginInterceptor 短路优先于 404）")
    void anonymousNotFoundUnauthorized() throws Exception {
        mockMvc.perform(get(NOT_FOUND_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    @Test
    @DisplayName("已登录正常接口回归 -> 200 + success=true")
    void authenticatedKnownEndpoint() throws Exception {
        token = loginAs(1L, "NoResource404用户");

        mockMvc.perform(get("/shops/1").header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("参数类型错误回归 -> 200 + 10008（Parameter 校验不被 404 抢走）")
    void parameterMismatchStillParamInvalid() throws Exception {
        token = loginAs(1L, "NoResource404用户");

        mockMvc.perform(get("/shops").param("typeId", "abc").header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));
    }
}