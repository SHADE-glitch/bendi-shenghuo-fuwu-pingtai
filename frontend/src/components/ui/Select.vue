<script setup lang="ts">
import { computed, ref } from 'vue';

export interface SelectOption {
  label: string;
  value: string | number;
  disabled?: boolean;
}

interface Props {
  modelValue?: string | number | null;
  options: SelectOption[];
  placeholder?: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: null,
  placeholder: '请选择',
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string | number];
  change: [value: string | number];
}>();

const show = ref(false);

const selectedLabel = computed(() => {
  const found = props.options.find((o) => o.value === props.modelValue);
  return found?.label ?? props.placeholder;
});

const toggle = () => {
  if (!props.disabled) show.value = !show.value;
};

const onSelect = (opt: SelectOption) => {
  if (opt.disabled) return;
  show.value = false;
  emit('update:modelValue', opt.value);
  emit('change', opt.value);
};
</script>

<template>
  <div class="select-stripe">
    <button type="button" class="select-trigger" :disabled="disabled" @click="toggle">
      <span :class="{ 'select-placeholder': modelValue == null }">{{ selectedLabel }}</span>
      <van-icon name="arrow-down" class="select-arrow" />
    </button>

    <van-popup v-model:show="show" position="bottom" round>
      <div class="select-sheet">
        <div class="select-sheet-title">{{ placeholder }}</div>
        <div class="select-list">
          <button
            v-for="opt in options"
            :key="opt.value"
            type="button"
            class="select-item"
            :class="{ active: opt.value === modelValue, disabled: opt.disabled }"
            @click="onSelect(opt)"
          >
            <span>{{ opt.label }}</span>
            <van-icon v-if="opt.value === modelValue" name="success" class="select-check" />
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 40px;
  padding: 8px 12px;
  background: var(--color-canvas);
  color: var(--color-ink);
  border: 1px solid var(--color-hairline-input);
  border-radius: 6px;
  font-size: 15px;
  font-weight: 300;
  cursor: pointer;
  transition: border-color 0.2s ease;
}

.select-trigger:focus-visible {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.15);
}

.select-trigger:disabled {
  background: var(--color-canvas-soft);
  color: var(--color-ink-mute);
  cursor: not-allowed;
}

.select-placeholder {
  color: var(--color-ink-mute);
}

.select-arrow {
  color: var(--color-ink-mute);
  font-size: 12px;
}

.select-sheet {
  padding: 16px;
}

.select-sheet-title {
  color: var(--color-ink-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}

.select-list {
  max-height: 50vh;
  overflow-y: auto;
}

.select-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 13px 4px;
  border: none;
  border-bottom: 1px solid var(--color-hairline);
  background: transparent;
  color: var(--color-ink);
  font-size: 15px;
  text-align: left;
  cursor: pointer;
}

.select-item:last-child {
  border-bottom: none;
}

.select-item.active {
  color: var(--color-primary);
}

.select-item.disabled {
  color: var(--color-ink-mute);
  cursor: not-allowed;
}

.select-check {
  color: var(--color-primary);
}
</style>
