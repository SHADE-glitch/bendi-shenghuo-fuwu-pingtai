<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  current: number;
  total: number;
  pageSize?: number;
  maxButtons?: number;
}

const props = withDefaults(defineProps<Props>(), {
  pageSize: 10,
  maxButtons: 5,
});

const emit = defineEmits<{
  'update:current': [page: number];
  change: [page: number];
}>();

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));

const pages = computed(() => {
  const count = Math.max(1, props.maxButtons);
  const half = Math.floor(count / 2);
  let start = Math.max(1, props.current - half);
  let end = Math.min(totalPages.value, start + count - 1);
  start = Math.max(1, end - count + 1);
  const result: number[] = [];
  for (let i = start; i <= end; i++) result.push(i);
  return result;
});

const go = (page: number) => {
  if (page < 1 || page > totalPages.value || page === props.current) return;
  emit('update:current', page);
  emit('change', page);
};
</script>

<template>
  <div class="pagination-stripe">
    <button
      type="button"
      class="page-btn"
      :disabled="current <= 1"
      @click="go(current - 1)"
    >
      上一页
    </button>

    <button
      v-for="page in pages"
      :key="page"
      type="button"
      class="page-num"
      :class="{ active: page === current }"
      @click="go(page)"
    >
      {{ page }}
    </button>

    <button
      type="button"
      class="page-btn"
      :disabled="current >= totalPages"
      @click="go(current + 1)"
    >
      下一页
    </button>
  </div>
</template>

<style scoped>
.pagination-stripe {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.page-btn,
.page-num {
  min-width: 34px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--color-hairline);
  border-radius: 8px;
  background: var(--color-canvas);
  color: var(--color-ink-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-num.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-on-primary);
}

.page-num:not(.active):hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
</style>
