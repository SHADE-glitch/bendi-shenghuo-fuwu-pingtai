<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  variant?: 'primary' | 'secondary' | 'danger' | 'text';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  type?: 'button' | 'submit' | 'reset';
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  size: 'md',
  loading: false,
  disabled: false,
  block: false,
  type: 'button',
});

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const classes = computed(() => [
  'btn-stripe-base',
  `btn-stripe-${props.variant}`,
  `btn-stripe-${props.size}`,
  {
    'btn-stripe-block': props.block,
    'btn-stripe-loading': props.loading,
    'btn-stripe-disabled': props.disabled || props.loading,
  },
]);

const handleClick = (e: MouseEvent) => {
  if (!props.disabled && !props.loading) {
    emit('click', e);
  }
};
</script>

<template>
  <button
    :type="type"
    :class="classes"
    :disabled="disabled || loading"
    @click="handleClick"
  >
    <span v-if="loading" class="btn-stripe-spinner"></span>
    <slot v-else></slot>
  </button>
</template>

<style scoped>
/* Base Button Styles */
.btn-stripe-base {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-family: inherit;
  font-weight: 400;
  line-height: 1;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  white-space: nowrap;
  text-decoration: none;
  border: none;
  outline: none;
}

.btn-stripe-base:focus-visible {
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.3);
}

/* Primary Variant - Stripe Indigo */
.btn-stripe-primary {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.btn-stripe-primary:hover:not(.btn-stripe-disabled) {
  background: var(--color-primary-deep);
}

.btn-stripe-primary:active:not(.btn-stripe-disabled) {
  background: var(--color-primary-press);
}

/* Secondary Variant - Outline */
.btn-stripe-secondary {
  background: var(--color-canvas);
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}

.btn-stripe-secondary:hover:not(.btn-stripe-disabled) {
  background: var(--color-canvas-soft);
}

/* Danger Variant - Ruby Red */
.btn-stripe-danger {
  background: var(--color-ruby);
  color: var(--color-on-primary);
}

.btn-stripe-danger:hover:not(.btn-stripe-disabled) {
  background: #c91b4d;
}

/* Text Variant - No Background */
.btn-stripe-text {
  background: transparent;
  color: var(--color-primary);
  padding: 8px 16px;
}

.btn-stripe-text:hover:not(.btn-stripe-disabled) {
  background: var(--color-canvas-soft);
}

/* Size Variants */
.btn-stripe-sm {
  padding: 6px 12px;
  font-size: 14px;
  border-radius: 9999px;
}

.btn-stripe-md {
  padding: 8px 16px;
  font-size: 16px;
  border-radius: 9999px;
}

.btn-stripe-lg {
  padding: 12px 24px;
  font-size: 18px;
  border-radius: 9999px;
}

/* Block Variant */
.btn-stripe-block {
  width: 100%;
}

/* Loading State */
.btn-stripe-loading {
  opacity: 0.7;
  cursor: wait;
}

.btn-stripe-spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid currentColor;
  border-color: transparent currentColor currentColor transparent;
  border-radius: 50%;
  animation: btn-stripe-spin 0.8s linear infinite;
}

@keyframes btn-stripe-spin {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}

/* Disabled State */
.btn-stripe-disabled {
  opacity: 0.6;
  cursor: not-allowed;
  pointer-events: none;
}

/* Mobile Touch Target Optimization */
@media (max-width: 768px) {
  .btn-stripe-sm {
    min-height: 44px;
    min-width: 44px;
    padding: 10px 20px;
  }

  .btn-stripe-md {
    min-height: 44px;
    min-width: 44px;
    padding: 12px 24px;
  }

  .btn-stripe-lg {
    min-height: 48px;
    min-width: 48px;
    padding: 14px 28px;
  }
}
</style>
