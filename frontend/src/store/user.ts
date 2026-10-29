import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { UserDTO } from '@/api/types';

const TOKEN_KEY = 'authorization';
const LEGACY_TOKEN_KEY = 'token';

/**
 * 用户状态（鉴权令牌 + 基本信息）
 * - state / getters / actions 分离
 * - token 持久化到 localStorage（兼容旧 key）
 */
export const useUserStore = defineStore(
  'user',
  () => {
    // ---- state ----
    const token = ref<string | null>(
      localStorage.getItem(TOKEN_KEY) || localStorage.getItem(LEGACY_TOKEN_KEY)
    );
    const user = ref<UserDTO | null>(null);

    // ---- getters ----
    const isLoggedIn = computed(() => Boolean(token.value));

    // ---- actions ----
    function setToken(newToken: string) {
      token.value = newToken;
      localStorage.setItem(TOKEN_KEY, newToken);
      // Backward-compatible key for existing clients/routes.
      localStorage.setItem(LEGACY_TOKEN_KEY, newToken);
    }

    function setUser(newUser: UserDTO | null) {
      user.value = newUser;
    }

    function logout() {
      token.value = null;
      user.value = null;
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(LEGACY_TOKEN_KEY);
    }

    return {
      token,
      user,
      isLoggedIn,
      setToken,
      setUser,
      logout,
    };
  },
  {
    persist: true,
  }
);
