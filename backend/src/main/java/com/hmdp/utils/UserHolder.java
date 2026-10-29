package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 用户上下文持有者
 * <p>
 * 基于 ThreadLocal 管理当前线程的 {@link UserDTO} 用户信息，
 * 确保同一请求线程内的用户数据隔离，避免多线程间数据串扰。
 * </p>
 * <p>
 * 使用约定：
 * <ul>
 *   <li>在拦截器中通过 {@link #saveUser(UserDTO)} 存入用户信息</li>
 *   <li>业务层通过 {@link #getUser()} 获取当前登录用户</li>
 *   <li>请求结束时必须调用 {@link #removeUser()} 清理，防止内存泄漏</li>
 * </ul>
 * </p>
 */
public class UserHolder {
    // 使用static确保线程安全，整个应用共享一个ThreadLocal实例
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 将当前登录用户信息保存到 ThreadLocal。
     *
     * @param user 当前登录用户的信息 DTO
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 获取当前线程中保存的登录用户信息。
     *
     * @return 当前登录用户的信息 DTO；未登录时为 null
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 清理用户信息，防止内存泄漏
     * 必须在请求结束时调用
     */
    public static void removeUser(){
        tl.remove();
    }
}
