<script setup lang="ts">
interface Props {
  title?: string;
  subtitle?: string;
  height?: string;
  animated?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  subtitle: '',
  height: '200px',
  animated: true,
});
</script>

<template>
  <div class="gradient-header" :style="{ height: props.height }">
    <div 
      v-if="props.animated" 
      class="gradient-mesh"
      :class="{ 'gradient-shift': props.animated }"
    ></div>
    
    <div class="content-wrapper">
      <div v-if="props.title" class="title stripe-display-md">{{ title }}</div>
      <div v-if="props.subtitle" class="subtitle stripe-body-lg">{{ subtitle }}</div>
    </div>
  </div>
</template>

<style scoped>
.gradient-header {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: var(--color-canvas-cream);
}

.gradient-mesh {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 0;
}

@keyframes gradient-shift {
  0% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0% 50%;
  }
}

.content-wrapper {
  position: relative;
  z-index: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 24px;
  text-align: center;
}

.title {
  color: var(--color-ink);
  margin-bottom: 8px;
}

.subtitle {
  color: var(--color-ink-secondary);
}

/* Mobile optimization */
@media (max-width: 768px) {
  .content-wrapper {
    padding: 20px 16px;
  }
  
  .title {
    font-size: 24px;
    letter-spacing: -0.26px;
  }
  
  .subtitle {
    font-size: 14px;
  }
}
</style>
