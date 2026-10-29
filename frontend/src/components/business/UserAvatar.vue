<script setup lang="ts">
const DEFAULT_AVATAR = '/imgs/icons/default-icon.svg';

withDefaults(
  defineProps<{
    src?: string | null;
    size?: string | number;
    radius?: string | number;
  }>(),
  { src: null, size: 48, radius: undefined }
);
</script>

<template>
  <van-image
    :src="src || DEFAULT_AVATAR"
    :width="size"
    :height="size"
    :radius="radius"
    round
    fit="cover"
  />
</template>
