<script setup lang="ts">
import type { Shop } from '@/api/types';
import { firstImage } from '@/utils/images';
import { formatScore } from '@/utils/format';
import ImageWithFallback from './ImageWithFallback.vue';

defineProps<{
  shop: Shop;
}>();

defineEmits<{
  select: [id: number];
}>();

const shortAddress = (shop: Shop) => {
  const address = shop.address || '';
  return address.includes('区') ? address.split('区').slice(1).join('区') || address : address;
};
</script>

<template>
  <article class="shop-card-stripe" @click="$emit('select', shop.id)">
    <div class="image-wrapper">
      <ImageWithFallback 
        :src="firstImage(shop.images)" 
        width="120" 
        height="120" 
        radius="12" 
      />
    </div>
    
    <div class="content">
      <div>
        <div class="title-row">
          <h2 class="shop-title">{{ shop.name }}</h2>
          <span v-if="shop.distance" class="distance stripe-body-tabular">
            {{ (shop.distance / 1000).toFixed(1) }}km
          </span>
        </div>
        
        <div class="rating-row">
          <van-rate 
            :model-value="formatScore(shop.score)" 
            readonly 
            allow-half 
            size="14" 
            color="var(--color-lemon)"
          />
          <span class="score stripe-caption">{{ formatScore(shop.score).toFixed(1) }}分</span>
          <span class="sales stripe-caption">月售 {{ shop.sold || 0 }}</span>
        </div>
      </div>
      
      <div class="meta-row stripe-caption">
        {{ shop.area || '本地' }} · {{ shortAddress(shop) }}
      </div>
      
      <div class="price-row">
        <span class="price stripe-body-tabular">人均 ¥{{ shop.avgPrice || 0 }}</span>
        <button 
          type="button" 
          class="btn-stripe-primary btn-stripe-sm" 
          @click.stop="$emit('select', shop.id)"
        >
          看优惠
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* Stripe Design System - Shop Card */
.shop-card-stripe {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  box-shadow: rgba(0, 55, 112, 0.08) 0 1px 3px;
  transition: all 0.2s ease;
  cursor: pointer;
}

.shop-card-stripe:hover {
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 12px, rgba(0, 55, 112, 0.08) 0 2px 6px;
  transform: translateY(-2px);
}

.image-wrapper {
  flex-shrink: 0;
  width: 120px;
  height: 120px;
  border-radius: 12px;
  overflow: hidden;
}

.content {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 8px;
}

.title-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.shop-title {
  flex: 1;
  min-width: 0;
  margin: 0;
  color: var(--color-ink);
  font-size: 18px;
  line-height: 1.25;
  font-weight: 300;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.distance {
  flex-shrink: 0;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
}

.rating-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-ink-secondary);
}

.score {
  font-weight: 400;
}

.sales {
  color: var(--color-ink-mute);
}

.meta-row {
  color: var(--color-ink-mute);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
}

.price {
  color: var(--color-ink);
  font-weight: 300;
}

.btn-stripe-sm {
  padding: 6px 12px;
  font-size: 14px;
  border-radius: 9999px;
}
</style>
