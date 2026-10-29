<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query';
import { showToast } from 'vant';
import { shopService } from '@/api/shop';
import { useLocation } from '@/utils/location';
import ShopCard from '@/components/ShopCard.vue';
import CityPicker from '@/components/CityPicker.vue';
import HomeHeader from './components/HomeHeader.vue';

const router = useRouter();
const activeTab = ref(0);
const searchText = ref('');
const showCityPicker = ref(false);
const { mode, location, usedFallback, cityName, setMode, selectCity } = useLocation();

watch(usedFallback, (v) => {
  if (v) showToast('定位失败，已使用默认演示位置（杭州）');
});

const cityLabel = computed(() => `${cityName.value}精选`);

const onPickCity = () => {
  showCityPicker.value = true;
};

const onSelectCity = (name: string) => {
  selectCity(name);
  showCityPicker.value = false;
  showToast(`已切换到${name}演示坐标`);
};

const onToggleAuto = (v: boolean) => {
  setMode(v ? 'auto' : 'demo');
  if (!v) showToast(`已切换到${cityName.value}演示坐标`);
};

const { data: types, isLoading: typeLoading } = useQuery({
  queryKey: ['shopTypes'],
  queryFn: () => shopService.queryTypeList().then((res) => res.data.data || []),
});

const currentTypeId = computed(() => types.value?.[activeTab.value]?.id);
const isSearchMode = computed(() => searchText.value.trim().length > 0);

/**
 * 店铺列表分页。
 * 页大小与后端 SystemConstants.DEFAULT_PAGE_SIZE 对齐；此前把 current 硬编码为 1
 * 且无任何翻页控件，导致美食分类 331 家仅可见 100 家、搜索命中 12 家仅可见 10 家。
 */
const SHOP_PAGE_SIZE = 100;

const {
  data: shopPages,
  isLoading: shopLoading,
  fetchNextPage,
  hasNextPage,
} = useInfiniteQuery({
  queryKey: ['shops', computed(() => ({ typeId: currentTypeId.value, search: searchText.value.trim(), x: location.value.x, y: location.value.y, city: cityName.value }))],
  initialPageParam: 1,
  queryFn: async ({ pageParam }) => {
    const current = pageParam as number;
    const keyword = searchText.value.trim();
    if (keyword) {
      const res = await shopService.queryByName(keyword, current);
      return res.data;
    }
    if (!currentTypeId.value) return null;
    const res = await shopService.queryByType({
      typeId: currentTypeId.value,
      current,
      x: location.value.x,
      y: location.value.y,
      city: cityName.value,
    });
    return res.data;
  },
  getNextPageParam: (lastPage, allPages) => {
    const items = lastPage?.data?.length ?? 0;
    // 本页不满即到底；GEO 分支不返回 total，只能靠页长收敛
    if (items < SHOP_PAGE_SIZE) return undefined;
    const loaded = allPages.reduce((n, p) => n + (p?.data?.length ?? 0), 0);
    if (typeof lastPage?.total === 'number' && loaded >= lastPage.total) return undefined;
    return allPages.length + 1;
  },
  enabled: computed(() => isSearchMode.value || Boolean(currentTypeId.value)),
});

const shops = computed(() => (shopPages.value?.pages ?? []).flatMap((p) => p?.data ?? []));
const shopTotal = computed(() => shopPages.value?.pages?.[0]?.total ?? null);
const listLoading = ref(false);

const onLoad = async () => {
  listLoading.value = true;
  try {
    await fetchNextPage();
  } finally {
    listLoading.value = false;
  }
};

const clearSearch = () => {
  searchText.value = '';
};

const goToShopDetail = (id: number) => router.push(`/shop/${id}`);
</script>

<template>
  <div class="home-page min-h-screen">
    <HomeHeader
      v-model:search-text="searchText"
      v-model:active-tab="activeTab"
      :types="types"
      :is-search-mode="isSearchMode"
      :city-label="cityLabel"
      @clear="clearSearch"
      @pick-city="onPickCity"
    />

    <div class="px-4 pt-4 locate-hint stripe-caption">定位坐标 {{ location.x.toFixed(4) }}, {{ location.y.toFixed(4) }}</div>

    <div class="px-3 pb-24">
      <van-skeleton v-if="typeLoading || (shopLoading && !shops?.length)" title :row="4" />

      <div v-else-if="shops.length" class="shop-list">
        <div v-if="shopTotal !== null" class="list-count">共 {{ shopTotal }} 家</div>
        <van-list
          v-model:loading="listLoading"
          :finished="hasNextPage === false"
          finished-text="没有更多了"
          :immediate-check="false"
          @load="onLoad"
        >
          <ShopCard
            v-for="shop in shops"
            :key="shop.id"
            :shop="shop"
            @select="goToShopDetail"
          />
        </van-list>
      </div>

      <div v-else class="empty-state">
        <van-icon name="cloud-o" size="64" color="var(--color-primary)" />
        <p class="empty-title stripe-heading-sm">{{ isSearchMode ? '没有找到相关商户' : '当前分类暂无商户' }}</p>
        <van-button v-if="isSearchMode" size="small" round variant="primary" @click="clearSearch">
          查看全部分类
        </van-button>
      </div>
    </div>

    <CityPicker
      v-model:show="showCityPicker"
      :city-name="cityName"
      :auto-mode="mode === 'auto'"
      @select-city="onSelectCity"
      @toggle-auto="onToggleAuto"
    />
  </div>
</template>

<style scoped>
.home-page {
  background: var(--color-canvas-soft);
}

.locate-hint {
  color: var(--color-ink-mute);
}

.shop-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ShopCard 现在挂在 van-list 内部，卡片间距需下沉一层才不丢失 */
.shop-list :deep(.van-list) {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.list-count {
  color: var(--color-ink-mute);
  font-size: 12px;
}

.empty-state {
  padding: 80px 20px;
  text-align: center;
  color: var(--color-ink-mute);
}

.empty-title {
  margin: 16px 0 24px;
  font-weight: 300;
  color: var(--color-ink-secondary);
}
</style>
