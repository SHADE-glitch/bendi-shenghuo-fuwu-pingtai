<script setup lang="ts">
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { useTheme } from '@/utils/theme';

const router = useRouter();
const { isDark, setTheme } = useTheme();

const clearCache = () => {
  const keys = Object.keys(localStorage).filter((k) => k.startsWith('loc_'));
  keys.forEach((k) => localStorage.removeItem(k));
  showToast('缓存已清除');
};
</script>

<template>
  <div class="settings-page min-h-screen pb-24">
    <van-nav-bar fixed placeholder title="设置" left-arrow @click-left="router.back()" />

    <section class="menu-section">
      <van-cell-group inset title="外观">
        <van-cell title="深色模式" label="跟随你的使用习惯，随时切换" size="large">
          <template #right-icon>
            <van-switch :model-value="isDark" size="22" @update:model-value="setTheme($event ? 'dark' : 'light')" />
          </template>
        </van-cell>
      </van-cell-group>
    </section>

    <section class="menu-section">
      <van-cell-group inset title="通用">
        <van-cell title="清除本地缓存" label="清除定位与城市等本地记录" icon="delete-o" is-link size="large" @click="clearCache" />
        <van-cell title="关于我们" label="了解本地生活服务平台" icon="info-o" is-link size="large" @click="router.push('/about')" />
      </van-cell-group>
    </section>

    <div class="version">本地生活服务平台 · 面试演示版 v1.0.0</div>
  </div>
</template>

<style scoped>
.settings-page {
  background: var(--c-page-bg);
}

.menu-section {
  margin-bottom: 12px;
}

:deep(.van-cell-group--inset) {
  margin: 0 12px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.version {
  padding: 26px 0 12px;
  color: var(--c-text-3);
  text-align: center;
  font-size: 12px;
}
</style>