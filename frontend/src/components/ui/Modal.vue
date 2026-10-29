<script setup lang="ts">
interface Props {
  show: boolean;
  title?: string;
  closeOnOverlay?: boolean;
  closeable?: boolean;
}

withDefaults(defineProps<Props>(), {
  title: '',
  closeOnOverlay: true,
  closeable: true,
});

const emit = defineEmits<{
  'update:show': [value: boolean];
  close: [];
}>();

const onClose = () => {
  emit('update:show', false);
  emit('close');
};
</script>

<template>
  <van-popup
    :show="show"
    round
    class="modal-stripe"
    :close-on-click-overlay="closeOnOverlay"
    @update:show="(v: boolean) => emit('update:show', v)"
    @click-overlay="onClose"
  >
    <div class="modal-header">
      <span v-if="title" class="modal-title">{{ title }}</span>
      <button v-if="closeable" type="button" class="modal-close" @click="onClose">
        <van-icon name="cross" size="18" />
      </button>
    </div>
    <div class="modal-body">
      <slot />
    </div>
    <div v-if="$slots.footer" class="modal-footer">
      <slot name="footer" />
    </div>
  </van-popup>
</template>

<style scoped>
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 8px;
}

.modal-title {
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 300;
}

.modal-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 50%;
  background: var(--color-canvas-soft);
  color: var(--color-ink-secondary);
  cursor: pointer;
}

.modal-body {
  padding: 12px 20px 20px;
  color: var(--color-ink-secondary);
  font-size: 15px;
  font-weight: 300;
  line-height: 1.5;
}

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 0 20px 20px;
}
</style>
