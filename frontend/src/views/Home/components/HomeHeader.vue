<script setup lang="ts">
import { useRouter } from 'vue-router';
import type { ShopType } from '@/api/types';

defineProps<{
  types?: ShopType[];
  isSearchMode: boolean;
  cityLabel?: string;
}>();

const router = useRouter();

const searchText = defineModel<string>('searchText', { required: true });
const activeTab = defineModel<number>('activeTab', { required: true });

const emit = defineEmits<{
  search: [];
  clear: [];
  pickCity: [];
}>();
</script>

<template>
  <van-sticky>
    <!-- Hero Section -->
    <section class="home-hero gradient-mesh">
      <div class="hero-content">
        <div class="city-trigger" @click="emit('pickCity')">
          <p class="home-city stripe-caption">{{ cityLabel || '本地精选' }}</p>
          <van-icon name="arrow-down" class="city-arrow" />
        </div>
        <h1 class="hero-title stripe-display-sm">附近好店与限时优惠</h1>
      </div>
      <button class="scan-button btn-stripe-secondary btn-stripe-sm" @click="router.push('/scan')">
        <van-icon name="scan" size="16" />
        <span>扫码</span>
      </button>
    </section>

    <!-- Search Bar -->
    <div class="search-shell">
      <van-search
        v-model="searchText"
        placeholder="搜索商户、地址或菜系"
        background="transparent"
        shape="round"
        clearable
        @search="emit('search')"
        @clear="emit('clear')"
      />
    </div>

    <!-- Category Tabs -->
    <van-tabs
      v-if="!isSearchMode"
      v-model:active="activeTab"
      class="type-tabs"
      color="#533afd"
      title-active-color="#0d253d"
      offset-left="0"
    >
      <van-tab v-for="t in types" :key="t.id" :title="t.name" />
    </van-tabs>
  </van-sticky>
</template>

<style scoped>
.home-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 16px 16px;
  background: var(--c-hero-bg);
  position: relative;
}

.hero-content {
  flex: 1;
}

.city-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  margin-bottom: 8px;
}

.home-city {
  margin: 0;
  color: var(--color-primary);
  font-weight: 400;
}

.city-arrow {
  color: var(--color-primary);
  font-size: 10px;
  transition: transform 0.2s ease;
}

.hero-title {
  margin: 0;
  color: var(--color-ink);
  line-height: 1.12;
  font-weight: 300;
}

.scan-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.search-shell {
  padding: 8px 12px;
  background: var(--color-canvas);
  border-bottom: 1px solid var(--color-hairline);
}

.type-tabs {
  background: var(--color-canvas);
  border-bottom: 1px solid var(--color-hairline);
}

/* Mobile optimization */
@media (max-width: 768px) {
  .home-hero {
    padding: 20px 16px 12px;
  }
  
  .hero-title {
    font-size: 20px;
    letter-spacing: -0.2px;
  }
  
  .scan-button {
    padding: 6px 12px;
    font-size: 13px;
  }
}
</style>
