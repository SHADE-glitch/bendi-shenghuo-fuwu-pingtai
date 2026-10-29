<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    actionText?: string;
  }>(),
  { subtitle: '', actionText: '' }
);

const emit = defineEmits<{
  action: [];
}>();
</script>

<template>
  <div class="section-header">
    <div class="section-header-copy">
      <h2 class="section-title">{{ title }}</h2>
      <p v-if="subtitle" class="section-subtitle stripe-caption">{{ subtitle }}</p>
    </div>
    <button v-if="actionText" type="button" class="section-action stripe-caption" @click="emit('action')">
      {{ actionText }}
    </button>
  </div>
</template>

<style scoped>
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-header-copy {
  min-width: 0;
}

.section-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 20px;
  font-weight: 300;
  letter-spacing: -0.2px;
}

.section-subtitle {
  margin: 4px 0 0;
  color: var(--color-ink-mute);
}

.section-action {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
}
</style>
