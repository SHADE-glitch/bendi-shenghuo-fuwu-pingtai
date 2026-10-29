import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import VoucherCard from '@/components/VoucherCard.vue';
import type { Voucher } from '@/api/types';

describe('VoucherCard.vue', () => {
  const mockVoucher: Voucher = {
    id: 1,
    shopId: 101,
    title: '100元代金券',
    subTitle: '周一至周五通用',
    rules: '不可与店内其他优惠同享',
    payValue: 8000,
    actualValue: 10000,
    type: 1,
    stock: 50,
  };

  it('renders voucher values and stock correctly', () => {
    const wrapper = mount(VoucherCard, {
      props: { voucher: mockVoucher },
    });

    expect(wrapper.text()).toContain('¥100');
    expect(wrapper.text()).toContain('满 80 可用');
    expect(wrapper.text()).toContain('100元代金券');
    expect(wrapper.text()).toContain('秒杀库存 50');
  });

  it('emits buy event when button is clicked', async () => {
    const wrapper = mount(VoucherCard, {
      props: { voucher: mockVoucher },
    });

    const button = wrapper.find('button');
    await button.trigger('click');
    expect(wrapper.emitted('buy')).toBeTruthy();
    expect(wrapper.emitted('buy')?.[0]).toEqual([mockVoucher]);
  });
});
