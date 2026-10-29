<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const props = withDefaults(
  defineProps<{
    threshold?: number;
  }>(),
  { threshold: 300 }
);

const visible = ref(false);

const onScroll = () => {
  visible.value = window.scrollY > props.threshold;
};

const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' });
};

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }));
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll));
</script>

<template>
  <transition name="fade">
    <button v-show="visible" type="button" class="scroll-top" @click="scrollToTop">
      <van-icon name="back-top" size="18" color="var(--color-primary)" />
    </button>
  </transition>
</template>

<style scoped>
.scroll-top {
  position: fixed;
  right: 16px;
  bottom: 84px;
  width: 40px;
  height: 40px;
  border: 1px solid var(--color-hairline);
  border-radius: 50%;
  background: var(--color-canvas);
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 2000;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
