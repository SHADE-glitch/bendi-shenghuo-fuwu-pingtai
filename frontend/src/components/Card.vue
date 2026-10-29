<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  variant?: 'light' | 'cream' | 'dark';
  padding?: 'sm' | 'md' | 'lg' | 'xl';
  hoverable?: boolean;
  clickable?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'light',
  padding: 'lg',
  hoverable: false,
  clickable: false,
});

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const cardClasses = computed(() => [
  'card-stripe-base',
  `card-stripe-${props.variant}`,
  `card-stripe-padding-${props.padding}`,
  {
    'card-stripe-hover': props.hoverable || props.clickable,
    'card-stripe-clickable': props.clickable,
  },
]);

const handleClick = (e: Event) => {
  if (props.clickable) {
    emit('click', e as MouseEvent);
  }
};
</script>

<template>
  <div
    :class="cardClasses"
    :tabindex="clickable ? 0 : undefined"
    role="button"
    @click="handleClick"
    @keydown.enter="handleClick"
    @keydown.space="handleClick"
  >
    <slot></slot>
  </div>
</template>

<style scoped>
.card-stripe-base {
  border-radius: 12px;
  transition: all 0.2s ease;
}

/* Variant Styles */
.card-stripe-light {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  box-shadow: rgba(0, 55, 112, 0.08) 0 1px 3px;
}

.card-stripe-cream {
  background: var(--color-canvas-cream);
  border: none;
}

.card-stripe-dark {
  background: var(--color-brand-dark-900);
  color: var(--color-on-primary);
  border: none;
}

/* Padding Variants */
.card-stripe-padding-sm {
  padding: 16px;
}

.card-stripe-padding-md {
  padding: 24px;
}

.card-stripe-padding-lg {
  padding: 32px;
}

.card-stripe-padding-xl {
  padding: 40px;
}

/* Hover Effects */
.card-stripe-hover:not(.card-stripe-clickable):hover {
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 12px, rgba(0, 55, 112, 0.08) 0 2px 6px;
  transform: translateY(-2px);
}

.card-stripe-clickable {
  cursor: pointer;
  outline: none;
}

.card-stripe-clickable:focus-visible {
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.3);
}

.card-stripe-clickable:hover {
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 12px, rgba(0, 55, 112, 0.08) 0 2px 6px;
  transform: translateY(-2px);
}

.card-stripe-clickable:active {
  transform: translateY(0);
}

/* Dark mode adjustments */
@media (prefers-color-scheme: dark) {
  .card-stripe-light {
    box-shadow: rgba(0, 0, 0, 0.2) 0 1px 3px;
  }
  
  .card-stripe-hover:hover,
  .card-stripe-clickable:hover {
    box-shadow: rgba(0, 0, 0, 0.3) 0 4px 12px, rgba(0, 0, 0, 0.15) 0 2px 6px;
  }
}
</style>
