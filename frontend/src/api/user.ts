import client from './client';
import {
  Result,
  UserDTO,
  UserInfoDTO,
  UserProfileDTO,
  UpdateUserInfoDTO,
  LoginResultDTO,
  LoginFormDTO,
  Blog,
} from './types';

/**
 * 对应后端 UserController
 */
export const userService = {
  /**
   * 发送手机验证码
   * POST /users/code?phone=...
   */
  sendCode(phone: string) {
    return client.post<Result<null>>('/users/code', null, { params: { phone } });
  },

  /**
   * 开发/测试辅助：设置固定验证码
   * POST /users/code/mock?phone=...&code=...
   */
  mockCode(phone: string, code: string) {
    return client.post<Result<null>>('/users/code/mock', null, { params: { phone, code } });
  },

  /**
   * 登录
   * POST /users/login
   */
  login(loginForm: LoginFormDTO) {
    return client.post<Result<LoginResultDTO>>('/users/login', loginForm);
  },

  /**
   * 登出
   * POST /users/logout
   */
  logout() {
    return client.post<Result<null>>('/users/logout');
  },

  /**
   * 获取当前登录用户信息
   * GET /users/me
   */
  me() {
    return client.get<Result<UserDTO>>('/users/me');
  },

  /**
   * 获取用户详情
   * GET /users/info/{id}
   */
  info(id: number | string) {
    return client.get<Result<UserInfoDTO>>(`/users/info/${id}`);
  },

  /**
   * 更新用户信息
   * PUT /users/info
   */
  updateInfo(data: UpdateUserInfoDTO) {
    return client.put<Result<null>>('/users/info', data);
  },

  /**
   * 用户签到
   * POST /users/signs
   */
  sign() {
    return client.post<Result<null>>('/users/signs');
  },

  /**
   * 获取连续签到天数
   * GET /users/signs/count
   */
  signCount() {
    return client.get<Result<number>>('/users/signs/count');
  },

  /**
   * 查询今日是否已签到
   * GET /users/signs/today
   */
  signToday() {
    return client.get<Result<boolean>>('/users/signs/today');
  },

  /**
   * 根据ID查询用户DTO
   * GET /users/{id}
   */
  queryById(id: number | string) {
    return client.get<Result<UserDTO>>(`/users/${id}`);
  },

  /**
   * 查询他人主页聚合信息（免登录可读）
   * GET /users/{id}/profile
   */
  profile(id: number | string) {
    return client.get<Result<UserProfileDTO>>(`/users/${id}/profile`);
  },

  /**
   * 分页查询某用户发布的笔记（免登录可读）
   * GET /users/{id}/blogs?current=&size=
   */
  userBlogs(id: number | string, current = 1, size = 10) {
    return client.get<Result<Blog[]>>(`/users/${id}/blogs`, { params: { current, size } });
  },
};
