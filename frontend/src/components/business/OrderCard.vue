<script setup lang="ts">
import type { MyVoucherItemDTO } from '@/api/types';
import { formatMoney, orderStatusText } from '@/utils/format';

withDefaults(
  defineProps<{
    order: MyVoucherItemDTO;
  }>(),
  {}
);

const emit = defineEmits<{
  select: [order: MyVoucherItemDTO];
  pay: [order: MyVoucherItemDTO];
  cancel: [order: MyVoucherItemDTO];
  refund: [order: MyVoucherItemDTO];
  remove: [order: MyVoucherItemDTO];
}>();
</script>

<template>
  <article class="order-card" @click="emit('select', order)">
    <div class="order-price">
      <div class="price-value">¥{{ formatMoney(order.actualValue) }}</div>
      <div class="price-label">满{{ formatMoney(order.payValue) }}可用</div>
    </div>

    <div class="order-body">
      <h3 class="order-title">{{ order.title }}</h3>
      <p class="order-sub">{{ order.rules || '全场通用' }}</p>
      <span class="order-status">{{ orderStatusText(order.orderStatus) }}</span>
    </div>

    <div class="order-actions">
      <template v-if="order.orderStatus === 1">
        <button type="button" class="btn-ghost" @click.stop="emit('cancel', order)">取消</button>
        <button type="button" class="btn-primary" @click.stop="emit('pay', order)">去支付</button>
      </template>
      <button
        v-else-if="order.orderStatus === 2"
        type="button"
        class="btn-ghost"
        @click.stop="emit('refund', order)"
      >
        申请退款
      </button>
      <button
        v-else-if="order.orderStatus === 4 || order.orderStatus === 5 || order.orderStatus === 6"
        type="button"
        class="btn-ghost"
        @click.stop="emit('remove', order)"
      >
        删除
      </button>
    </div>
  </article>
</template>

<style scoped>
.order-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  cursor: pointer;
  transition: box-shadow 0.2s ease;
}

.order-card:hover {
  box-shadow: rgba(0, 55, 112, 0.08) 0 4px 12px;
}

.order-price {
  flex-shrink: 0;
  width: 82px;
  height: 68px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 8px;
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.price-value {
  font-size: 22px;
  font-weight: 400;
  line-height: 1;
}

.price-label {
  font-size: 10px;
  opacity: 0.85;
}

.order-body {
  min-width: 0;
  flex: 1;
}

.order-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 300;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-sub {
  margin: 4px 0 0;
  color: var(--color-ink-mute);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-status {
  display: inline-block;
  margin-top: 6px;
  color: var(--color-primary);
  font-size: 12px;
}

.order-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.btn-primary,
.btn-ghost {
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  cursor: pointer;
}

.btn-primary {
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: 1px solid var(--color-primary);
}

.btn-ghost {
  background: transparent;
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}
</style>
