<script setup lang="ts">
import { useUserStore } from '@/store/user';
import { userService } from '@/api/user';

/**
 * 启动时用持久化的 token 拉取当前用户，避免刷新后 userStore.user 为空
 * （否则「我的」页、关注按钮等依赖 user.id 的地方会误判）。
 */
const userStore = useUserStore();

if (userStore.token && !userStore.user) {
  userService
    .me()
    .then((res) => {
      if (res.data.success && res.data.data) userStore.setUser(res.data.data);
    })
    .catch(() => {
      /* token 失效等由请求拦截器统一处理，这里静默降级 */
    });
}
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="stripe-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
/* Stripe-style page transitions */
.stripe-fade-enter-active,
.stripe-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.stripe-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.stripe-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* Smooth scroll behavior */
html {
  scroll-behavior: smooth;
}

/* Focus visible styles for accessibility */
*:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 4px;
}

#app {
  max-width: 430px;
  margin: 0 auto;
  min-height: 100dvh;
  position: relative;
  overflow-x: hidden;
}
</style>
