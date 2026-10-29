<script setup lang="ts">
withDefaults(
  defineProps<{
    padding?: string;
    background?: string;
  }>(),
  { padding: '16px', background: 'transparent' }
);
</script>

<template>
  <div class="page-container" :style="{ padding, background }">
    <slot />
  </div>
</template>

<style scoped>
.page-container {
  width: 100%;
  box-sizing: border-box;
}
</style>
