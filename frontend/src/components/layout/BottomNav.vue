<script setup lang="ts">
export interface NavItem {
  path: string;
  label: string;
  icon: string;
}

interface Props {
  modelValue?: number;
  items: NavItem[];
  activeColor?: string;
}

withDefaults(defineProps<Props>(), {
  modelValue: 0,
  activeColor: '#533afd',
});

const emit = defineEmits<{
  'update:modelValue': [index: number];
  change: [index: number];
}>();
</script>

<template>
  <van-tabbar
    :model-value="modelValue"
    fixed
    placeholder
    border
    safe-area-inset-bottom
    :active-color="activeColor"
    style="z-index: 9999"
    @update:model-value="(v: number) => emit('update:modelValue', v)"
    @change="(v: number) => emit('change', v)"
  >
    <van-tabbar-item v-for="item in items" :key="item.path" :icon="item.icon">
      {{ item.label }}
    </van-tabbar-item>
  </van-tabbar>
</template>
