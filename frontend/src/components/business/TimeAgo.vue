<script setup lang="ts">
import { computed } from 'vue';
import { timeAgo } from '@/utils/format';

const props = withDefaults(
  defineProps<{
    time?: string | number | Date | null;
  }>(),
  { time: null }
);

const text = computed(() => timeAgo(props.time));
</script>

<template>
  <span class="time-ago stripe-caption">{{ text }}</span>
</template>

<style scoped>
.time-ago {
  color: var(--color-ink-mute);
}
</style>
