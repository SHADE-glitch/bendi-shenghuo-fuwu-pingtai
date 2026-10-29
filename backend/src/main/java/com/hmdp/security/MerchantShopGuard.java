package com.hmdp.security;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 店铺归属校验器（MerchantShopGuard）
 * <p>
 * <b>职责</b>：判断「当前登录用户是否为商家」以及「该商家是否拥有指定店铺」。
 * 不负责创建/修改店铺，也不涉及 Voucher、Order、RBAC、商家后台或角色管理。
 * </p>
 * <ul>
 *   <li>{@link #assertMerchant()}：商家身份校验，返回当前商家用户 id；
 *       用于 <b>拦截器覆盖不到</b> 的商家私有接口（如 {@code GET /shops/my}
 *       —— {@code /shops/**} 整体从 {@code LoginInterceptor} 放行，
 *       且 {@code WriteAuthInterceptor} 只管写方法，GET 不在其内）。</li>
 *   <li>{@link #assertOwner(Long)}：店铺归属校验。</li>
 * </ul>
 *
 * <h3>与 WriteAuthInterceptor 的职责边界</h3>
 * <ul>
 *   <li>{@code WriteAuthInterceptor}：负责写方法的「<b>是不是商家</b>」（{@code role == 1}）；</li>
 *   <li>{@code MerchantShopGuard}：负责「<b>是不是商家</b>」（{@code assertMerchant}，兜底/只读场景）
 *       与「<b>是不是这家店铺的 owner</b>」（{@code shop.owner_id == 当前用户 id}）。</li>
 * </ul>
 * 写路径上两者不重叠：拦截器保证角色，Guard 保证归属；Guard 的角色判断同时为
 * 只读商家接口与拦截器未覆盖的路径提供防御性兜底。
 *
 * <h3>校验顺序与返回</h3>
 * <table border="1">
 *   <tr><th>情况</th><th>错误码</th><th>HTTP</th></tr>
 *   <tr><td>A. 未登录</td><td>10001 AUTH_REQUIRED</td><td>401</td></tr>
 *   <tr><td>B. 非商家</td><td>40003 MERCHANT_ONLY</td><td>403</td></tr>
 *   <tr><td>C. 店铺不存在</td><td>40001 SHOP_NOT_FOUND</td><td>200（业务错误惯例）</td></tr>
 *   <tr><td>D. owner 匹配</td><td>—</td><td>放行</td></tr>
 *   <tr><td>E. owner 不匹配</td><td>40004 SHOP_NOT_OWNED</td><td>403</td></tr>
 * </table>
 * <p>
 * 情况 A/B 正常情况下已由 {@code LoginInterceptor}/{@code WriteAuthInterceptor} 拦截，
 * 此处为防御性判断，保证 Guard 被其它调用方复用时不会产生 NPE 或越权。
 * 情况 C 与 E 严格区分：<b>「店铺不存在」不得被当成「无权限」</b>。
 * </p>
 */
@Component
public class MerchantShopGuard {

    @Resource
    private IShopService shopService;

    /**
     * 断言当前登录用户是商家（{@code role == 1}），并返回其用户 id。
     * <p>
     * 用于<b>没有具体 shopId</b> 的商家私有接口（例如 {@code GET /shops/my}），
     * 这类接口无法复用 {@link #assertOwner(Long)}，又因 {@code /shops/**}
     * 整体放行 / 只读方法不受 {@code WriteAuthInterceptor} 约束，
     * 需要在此显式校验身份。
     * </p>
     * <p>
     * 查询条件一律使用本方法返回的 id（即当前登录态），
     * <b>不信任</b>客户端传入的任何 owner 参数。
     * </p>
     *
     * @return 当前登录商家的用户 id
     * @throws MerchantAccessException 未登录（401 + 10001）/ 非商家（403 + 40003）
     */
    public Long assertMerchant() {
        // A. 无登录态 -> 401 + 10001
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new MerchantAccessException(ErrorCode.AUTH_REQUIRED, HttpStatus.UNAUTHORIZED);
        }
        // B. 非商家（role 缺失或 != 1）-> 403 + 40003
        Integer role = user.getRole();
        if (role == null || role != 1) {
            throw new MerchantAccessException(ErrorCode.MERCHANT_ONLY, HttpStatus.FORBIDDEN);
        }
        return user.getId();
    }

    /**
     * 断言当前登录商家拥有指定店铺，不满足时抛出 {@link MerchantAccessException}。
     * <p>
     * 归属判断直接读库（{@code getById}），不走店铺缓存，避免缓存值与权限判断不一致。
     * </p>
     *
     * @param shopId 目标店铺 id
     * @throws MerchantAccessException 未登录 / 非商家 / 店铺不存在 / 非该店铺 owner
     */
    public void assertOwner(Long shopId) {
        // A. 防御性：无登录态（正常已由 LoginInterceptor 拦截）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new MerchantAccessException(ErrorCode.AUTH_REQUIRED, HttpStatus.UNAUTHORIZED);
        }
        // B. 防御性：非商家（正常已由 WriteAuthInterceptor 拦截）
        Integer role = user.getRole();
        if (role == null || role != 1) {
            throw new MerchantAccessException(ErrorCode.MERCHANT_ONLY, HttpStatus.FORBIDDEN);
        }
        // C. 店铺不存在 —— 与「无权限」严格区分
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            throw new MerchantAccessException(ErrorCode.SHOP_NOT_FOUND, HttpStatus.OK);
        }
        // D/E. owner 归属判断；owner_id 为 NULL（历史店铺）视为「无人拥有」，一律拒绝
        if (shop.getOwnerId() == null || !shop.getOwnerId().equals(user.getId())) {
            throw new MerchantAccessException(ErrorCode.SHOP_NOT_OWNED, HttpStatus.FORBIDDEN);
        }
    }
}
