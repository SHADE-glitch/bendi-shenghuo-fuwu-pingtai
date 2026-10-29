import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ShopCard from '@/components/ShopCard.vue';
import type { Shop } from '@/api/types';

describe('ShopCard.vue', () => {
  const mockShop: Shop = {
    id: 101,
    name: '海底捞火锅',
    typeId: 1,
    images: '/imgs/shops/shop_1.jpg',
    area: '静安区',
    address: '静安区南京西路100号',
    x: 121.47,
    y: 31.23,
    avgPrice: 120,
    sold: 500,
    comments: 120,
    score: 48,
    openHours: '10:00-22:00',
    distance: 1500,
  };

  it('renders shop information correctly', () => {
    const wrapper = mount(ShopCard, {
      props: { shop: mockShop },
    });

    expect(wrapper.text()).toContain('海底捞火锅');
    expect(wrapper.text()).toContain('1.5km');
    expect(wrapper.text()).toContain('4.8分');
    expect(wrapper.text()).toContain('月售 500');
    expect(wrapper.text()).toContain('人均 ¥120');
  });

  it('emits select event with shop id on click', async () => {
    const wrapper = mount(ShopCard, {
      props: { shop: mockShop },
    });

    await wrapper.trigger('click');
    expect(wrapper.emitted('select')).toBeTruthy();
    expect(wrapper.emitted('select')?.[0]).toEqual([101]);
  });
});
