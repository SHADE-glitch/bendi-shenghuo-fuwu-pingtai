import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { Shop, ShopType } from '@/api/types';

/**
 * 商铺状态：商铺类型列表、当前商铺、附近商铺缓存
 */
export const useShopStore = defineStore('shop', () => {
  // ---- state ----
  const types = ref<ShopType[]>([]);
  const shops = ref<Shop[]>([]);
  const currentShop = ref<Shop | null>(null);

  // ---- actions ----
  function setTypes(list: ShopType[]) {
    types.value = list;
  }

  function setShops(list: Shop[]) {
    shops.value = list;
  }

  function setCurrentShop(shop: Shop | null) {
    currentShop.value = shop;
  }

  function reset() {
    types.value = [];
    shops.value = [];
    currentShop.value = null;
  }

  return {
    types,
    shops,
    currentShop,
    setTypes,
    setShops,
    setCurrentShop,
    reset,
  };
});
