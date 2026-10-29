<script setup lang="ts">
import type { Voucher } from '@/api/types';
import { formatDateTime, formatMoney } from '@/utils/format';

defineProps<{
  voucher: Voucher;
  loading?: boolean;
  disabled?: boolean;
}>();

defineEmits<{
  buy: [voucher: Voucher];
}>();
</script>

<template>
  <article class="voucher-card">
    <div class="voucher-price">
      <div>¥{{ formatMoney(voucher.actualValue) }}</div>
      <span>满 {{ formatMoney(voucher.payValue) }} 可用</span>
    </div>
    <div class="voucher-info">
      <h3>{{ voucher.title }}</h3>
      <p>{{ voucher.subTitle || voucher.rules || '到店消费可用，具体以商家说明为准' }}</p>
      <span v-if="voucher.type === 1">秒杀库存 {{ voucher.stock ?? 0 }}</span>
      <span v-else>普通券</span>
      <div class="voucher-time">
        <van-icon name="clock-o" />
        <span v-if="voucher.type === 1 && voucher.beginTime && voucher.endTime">{{ formatDateTime(voucher.beginTime) }} ~ {{ formatDateTime(voucher.endTime) }}</span>
        <span v-else>长期有效</span>
      </div>
    </div>
    <van-button
      round
      size="small"
      type="primary"
      :loading="loading"
      :disabled="disabled"
      @click="$emit('buy', voucher)"
    >
      {{ voucher.type === 1 ? '立即秒杀' : '立即领取' }}
    </van-button>
  </article>
</template>

<style scoped>
.voucher-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px dashed var(--c-border);
}

.voucher-card:first-child {
  border-top: 0;
}

.voucher-price {
  width: 82px;
  height: 72px;
  border-radius: 8px;
  background: linear-gradient(135deg, #e84855, #f97316);
  color: white;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.voucher-price div {
  font-size: 24px;
  font-weight: 900;
  line-height: 1;
}

.voucher-price span,
.voucher-info span {
  margin-top: 5px;
  font-size: 10px;
}

.voucher-info {
  min-width: 0;
  flex: 1;
}

.voucher-info h3 {
  margin: 0;
  color: var(--c-text-1);
  font-size: 14px;
  font-weight: 900;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voucher-info p {
  margin: 6px 0 0;
  color: var(--c-text-2);
  font-size: 12px;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.voucher-info span {
  display: inline-block;
  color: #e84855;
  font-weight: 800;
}

.voucher-time {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-top: 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--c-text-3);
}
</style>
