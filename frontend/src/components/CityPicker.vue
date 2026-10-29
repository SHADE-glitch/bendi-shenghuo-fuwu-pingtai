<script setup lang="ts">
import { CITY_LIST } from '@/utils/location';

defineProps<{
  show: boolean;
  cityName: string;
  autoMode: boolean;
}>();

const emit = defineEmits<{
  'update:show': [v: boolean];
  selectCity: [name: string];
  toggleAuto: [v: boolean];
}>();
</script>

<template>
  <van-popup
    :show="show"
    position="bottom"
    round
    :style="{ height: '62%' }"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="city-picker">
      <div class="city-picker-title">选择演示城市</div>
      <div class="auto-row">
        <span>自动定位（使用浏览器真实位置）</span>
        <van-switch
          :model-value="autoMode"
          size="20px"
          @update:model-value="(v: boolean) => emit('toggleAuto', v)"
        />
      </div>
      <div class="city-grid">
        <button
          v-for="c in CITY_LIST"
          :key="c.name"
          type="button"
          class="city-item"
          :class="{ active: c.name === cityName && !autoMode }"
          @click="emit('selectCity', c.name)"
        >
          {{ c.name }}
        </button>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.city-picker {
  padding: 14px;
}

.city-picker-title {
  color: var(--c-text-1);
  font-size: 15px;
  font-weight: 800;
  margin-bottom: 8px;
}

.auto-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text-2);
  font-size: 13px;
}

.city-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  max-height: calc(62vh - 130px);
  overflow-y: auto;
}

.city-item {
  padding: 8px 0;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-card-bg);
  color: var(--c-text-2);
  font-size: 13px;
}

.city-item.active {
  border-color: #e84855;
  color: #e84855;
  font-weight: 800;
}
</style>