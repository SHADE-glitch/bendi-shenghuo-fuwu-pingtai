<script setup lang="ts">
defineProps<{
  text?: string;
}>();
</script>

<template>
  <div class="loading-state">
    <van-loading type="spinner" size="24">{{ text || '加载中...' }}</van-loading>
  </div>
</template>

<style scoped>
.loading-state {
  padding: 48px 20px;
  text-align: center;
}
</style>
