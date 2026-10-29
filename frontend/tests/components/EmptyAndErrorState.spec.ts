import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

describe('EmptyState.vue', () => {
  it('renders title and description properly', () => {
    const wrapper = mount(EmptyState, {
      props: {
        title: '暂无店铺',
        description: '请尝试搜索其他关键字',
      },
    });

    expect(wrapper.text()).toContain('暂无店铺');
    expect(wrapper.text()).toContain('请尝试搜索其他关键字');
  });
});

describe('ErrorState.vue', () => {
  it('renders title and emits retry event when button clicked', async () => {
    const wrapper = mount(ErrorState, {
      props: {
        title: '网络出错了',
      },
    });

    expect(wrapper.text()).toContain('网络出错了');
    const button = wrapper.find('button');
    await button.trigger('click');
    expect(wrapper.emitted('retry')).toBeTruthy();
  });
});
