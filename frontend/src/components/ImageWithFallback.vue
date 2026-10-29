<script setup lang="ts">
import { ref, watch } from 'vue';
import { fallbackImage } from '@/utils/images';

const props = withDefaults(defineProps<{
  src?: string | null;
  width?: string | number;
  height?: string | number;
  radius?: string | number;
  fit?: 'contain' | 'cover' | 'fill' | 'none' | 'scale-down';
}>(), {
  src: null,
  width: undefined,
  height: undefined,
  radius: undefined,
  fit: 'cover',
});

const currentSrc = ref(props.src || fallbackImage);

watch(() => props.src, (src) => {
  currentSrc.value = src || fallbackImage;
});
</script>

<template>
  <van-image
    :src="currentSrc"
    :width="width"
    :height="height"
    :radius="radius"
    :fit="fit"
    @error="currentSrc = fallbackImage"
  />
</template>
