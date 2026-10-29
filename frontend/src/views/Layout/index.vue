<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import BottomNav, { type NavItem } from '@/components/layout/BottomNav.vue';

const active = ref(0);
const route = useRoute();
const router = useRouter();

const navItems: NavItem[] = [
  { path: '/', label: '首页', icon: 'wap-home-o' },
  { path: '/map', label: '地图', icon: 'location-o' },
  { path: '/my-vouchers', label: '优惠券', icon: 'coupon-o' },
  { path: '/mine', label: '我的', icon: 'user-o' },
];

const onChange = (index: number) => {
  router.push(navItems[index]?.path ?? '/').catch(() => showToast('页面加载失败，请刷新重试'));
};

// Sync active tab with current route path
watch(
  () => route.path,
  (newPath) => {
    const index = navItems.findIndex((item) => item.path === newPath);
    if (index !== -1) {
      active.value = index;
    }
  },
  { immediate: true }
);
</script>

<template>
  <div class="layout-container" style="padding-bottom: 70px;">
    <router-view />

    <BottomNav v-model="active" :items="navItems" @change="onChange" />
  </div>
</template>
