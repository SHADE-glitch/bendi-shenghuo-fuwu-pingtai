package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 商家写操作权限拦截器
 * <p>
 * 用于 {@code /shops/**}、{@code /vouchers/**} 这类「读取匿名、写入需商家」的路径。
 * 这些路径整体从 {@link LoginInterceptor} 放行（保证店铺详情/列表/搜索与券列表可匿名浏览），
 * 但其中的写接口（POST/PUT/PATCH/DELETE）必须由商家操作。
 * </p>
 * <p>
 * 规则：
 * <ul>
 *   <li>{@code GET}/{@code HEAD}/{@code OPTIONS} 直接放行（读接口匿名可用，行为不变）；</li>
 *   <li>其余写方法：{@link UserHolder} 无用户 → 401 + {@code 10001}（未登录，语义不变）；</li>
 *   <li>其余写方法：用户存在但 {@code role != 1} → 403 + {@code 40003}（仅商家可操作）。</li>
 * </ul>
 * </p>
 * <p>
 * <b>范围</b>：本拦截器只注册在 {@code /shops/**}、{@code /vouchers/**} 上，
 * 消费者写接口（{@code /users/**}、{@code /blogs/**}、{@code /follows/**}、
 * {@code /shop-comments/**}、{@code /feedbacks/**}、{@code /voucher-orders/**} 等）
 * 不受影响，仍由 {@link LoginInterceptor} 按「已登录」放行。
 * </p>
 * <p>
 * <b>本阶段范围</b>：仅做角色校验（{@code role == 1}），<b>不含</b>店铺归属（owner）校验。
 * </p>
 */
public class WriteAuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        // 只读方法（含预检请求）放行
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        // 写方法：必须有登录态（未登录语义保持 401 + 10001 不变）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            Result<Void> body = Result.fail(ErrorCode.AUTH_REQUIRED);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
            return false;
        }
        // 写方法：必须是商家（role=1）。普通用户(role=0)或角色缺失一律拒绝为 403 + 40003
        Integer role = user.getRole();
        if (role == null || role != 1) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            Result<Void> body = Result.fail(ErrorCode.MERCHANT_ONLY);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
            return false;
        }
        return true;
    }
}
