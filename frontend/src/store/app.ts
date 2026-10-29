import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { getThemeMode, applyTheme, type ThemeMode } from '@/utils/theme';

/**
 * 全局应用状态：主题、当前城市、全局加载开关
 */
export const useAppStore = defineStore(
  'app',
  () => {
    // ---- state ----
    const theme = ref<ThemeMode>(getThemeMode());
    const city = ref<string>('杭州');
    const globalLoading = ref(false);

    // ---- getters ----
    const isDark = computed(() => theme.value === 'dark');

    // ---- actions ----
    function setTheme(mode: ThemeMode) {
      theme.value = mode;
      applyTheme(mode);
    }

    function toggleTheme() {
      setTheme(isDark.value ? 'light' : 'dark');
    }

    function setCity(name: string) {
      city.value = name;
    }

    function setLoading(value: boolean) {
      globalLoading.value = value;
    }

    return {
      theme,
      city,
      globalLoading,
      isDark,
      setTheme,
      toggleTheme,
      setCity,
      setLoading,
    };
  },
  {
    persist: { pick: ['theme', 'city'] },
  }
);
