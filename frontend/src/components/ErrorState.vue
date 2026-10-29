<script setup lang="ts">
defineProps<{
  title?: string;
  description?: string;
}>();

defineEmits<{
  retry: [];
}>();
</script>

<template>
  <div class="error-state">
    <van-icon name="warning-o" size="48" />
    <strong>{{ title || '加载失败' }}</strong>
    <p>{{ description || '网络或服务暂时不可用，请稍后重试' }}</p>
    <van-button size="small" round type="primary" @click="$emit('retry')">重试</van-button>
  </div>
</template>

<style scoped>
.error-state {
  padding: 42px 20px;
  color: var(--c-text-3);
  text-align: center;
}

.error-state strong {
  display: block;
  margin-top: 12px;
  color: var(--c-text-2);
  font-size: 15px;
}

.error-state p {
  margin: 6px 0 18px;
  font-size: 13px;
}
</style>
