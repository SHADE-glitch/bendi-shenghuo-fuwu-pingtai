import client from './client';
import { Result, Shop, ShopType } from './types';

/**
 * 对应后端 ShopController, ShopTypeController
 */
export const shopService = {
  /**
   * 根据ID查询商铺详情
   * GET /shops/{id}
   */
  queryById(id: number | string) {
    return client.get<Result<Shop>>(`/shops/${id}`);
  },

  /**
   * 查询商铺类型列表
   * GET /shop-types
   */
  queryTypeList() {
    return client.get<Result<ShopType[]>>('/shop-types');
  },

  /**
   * 根据商铺类型分页查询（支持GEO排序）
   * GET /shops?typeId=&current=&x=&y=&city=
   */
  queryByType(params: { typeId: number; current: number; x?: number; y?: number; city?: string }) {
    return client.get<Result<Shop[]>>('/shops', { params });
  },

  /**
   * 根据商铺名称关键字分页查询
   * GET /shops/search?name=&current=
   */
  queryByName(name: string, current: number) {
    return client.get<Result<Shop[]>>('/shops/search', { params: { name, current } });
  },

};
