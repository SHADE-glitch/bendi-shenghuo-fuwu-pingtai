import { ref } from 'vue';

export type ThemeMode = 'light' | 'dark';

const THEME_KEY = 'theme';

export const getThemeMode = (): ThemeMode => {
  try {
    return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
};

export const applyTheme = (mode: ThemeMode) => {
  document.documentElement.classList.toggle('van-theme-dark', mode === 'dark');
};

export const initTheme = () => {
  applyTheme(getThemeMode());
};

export const useTheme = () => {
  const isDark = ref(getThemeMode() === 'dark');

  const setTheme = (mode: ThemeMode) => {
    isDark.value = mode === 'dark';
    applyTheme(mode);
    try {
      localStorage.setItem(THEME_KEY, mode);
    } catch {}
  };

  const toggle = () => setTheme(isDark.value ? 'light' : 'dark');

  return { isDark, setTheme, toggle };
};