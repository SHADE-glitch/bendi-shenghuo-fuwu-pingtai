<script setup lang="ts">
defineProps<{
  icon?: string;
  title: string;
  description?: string;
}>();
</script>

<template>
  <div class="empty-state">
    <van-icon :name="icon || 'info-o'" size="48" />
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <slot />
  </div>
</template>

<style scoped>
.empty-state {
  padding: 42px 20px;
  color: var(--c-text-3);
  text-align: center;
}

.empty-state strong {
  display: block;
  margin-top: 12px;
  color: var(--c-text-2);
  font-size: 15px;
}

.empty-state p {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.5;
}
</style>
