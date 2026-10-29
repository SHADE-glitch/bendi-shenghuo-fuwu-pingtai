<script setup lang="ts">
interface Props {
  modelValue: number;
  readonly?: boolean;
  size?: number;
  color?: string;
  allowHalf?: boolean;
  count?: number;
}

withDefaults(defineProps<Props>(), {
  readonly: true,
  size: 14,
  color: 'var(--color-lemon)',
  allowHalf: true,
  count: 5,
});

const emit = defineEmits<{
  'update:modelValue': [value: number];
  change: [value: number];
}>();
</script>

<template>
  <van-rate
    :model-value="modelValue"
    :readonly="readonly"
    :size="size"
    :color="color"
    :allow-half="allowHalf"
    :count="count"
    @update:model-value="(v: number) => emit('update:modelValue', v)"
    @change="(v: number) => emit('change', v)"
  />
</template>
