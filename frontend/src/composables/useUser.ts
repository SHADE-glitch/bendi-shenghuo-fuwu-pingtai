import { useUserStore } from '@/store/user';
import { userService } from '@/api/user';
import type { LoginFormDTO, UpdateUserInfoDTO, UserDTO } from '@/api/types';

/**
 * 用户相关逻辑：登录 / 登出 / 拉取当前用户 / 更新资料。
 */
export function useUser() {
  const userStore = useUserStore();

  const login = async (form: LoginFormDTO): Promise<UserDTO | null> => {
    const res = await userService.login(form);
    const { token, user } = res.data.data;
    userStore.setToken(token);
    userStore.setUser(user);
    return user;
  };

  const logout = async () => {
    try {
      await userService.logout();
    } finally {
      userStore.logout();
    }
  };

  const refreshMe = async (): Promise<UserDTO | null> => {
    const res = await userService.me();
    if (res.data.success && res.data.data) {
      userStore.setUser(res.data.data);
    }
    return res.data.data;
  };

  const updateInfo = (data: UpdateUserInfoDTO) => userService.updateInfo(data);

  return { userStore, login, logout, refreshMe, updateInfo };
}
