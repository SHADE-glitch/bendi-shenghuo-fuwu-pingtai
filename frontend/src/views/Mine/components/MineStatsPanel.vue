<script setup lang="ts">
defineProps<{
  loading: boolean;
  signCount: number;
  voucherCount: number;
}>();
</script>

<template>
  <section class="stats-panel">
    <van-skeleton v-if="loading" title :row="2" />
    <template v-else>
      <div>
        <strong>{{ signCount }}</strong>
        <span>连续签到</span>
      </div>
      <div>
        <strong>{{ voucherCount }}</strong>
        <span>可用券</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.stats-panel {
  margin: 0 12px 12px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.06);
  overflow: hidden;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  padding: 18px 8px;
  text-align: center;
}

.stats-panel strong {
  display: block;
  color: var(--c-text-1);
  font-size: 20px;
  font-weight: 900;
}

.stats-panel span {
  display: block;
  margin-top: 4px;
  color: var(--c-text-2);
  font-size: 12px;
}
</style>
