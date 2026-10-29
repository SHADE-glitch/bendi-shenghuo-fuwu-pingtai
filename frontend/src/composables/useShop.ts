import { shopService } from '@/api/shop';
import { useShopStore } from '@/store/shop';
import type { Shop, ShopType } from '@/api/types';

/**
 * 商铺查询逻辑：类型列表 / 分类查询 / 详情。
 */
export function useShop() {
  const shopStore = useShopStore();

  const fetchTypes = async (): Promise<ShopType[]> => {
    const res = await shopService.queryTypeList();
    const types = res.data.data || [];
    shopStore.setTypes(types);
    return types;
  };

  const fetchShops = async (
    params: { typeId: number; current: number; x?: number; y?: number; city?: string }
  ): Promise<Shop[]> => {
    const res = await shopService.queryByType(params);
    const shops = res.data.data || [];
    shopStore.setShops(shops);
    return shops;
  };

  const fetchShopDetail = async (id: number | string): Promise<Shop | null> => {
    const res = await shopService.queryById(id);
    const shop = res.data.data ?? null;
    shopStore.setCurrentShop(shop);
    return shop;
  };

  return { shopStore, fetchTypes, fetchShops, fetchShopDetail };
}
