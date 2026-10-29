<script setup lang="ts">
import { ref } from 'vue';
import { useImageUpload } from '@/composables/useImageUpload';

const props = withDefaults(
  defineProps<{
    modelValue?: string | null;
    disabled?: boolean;
    placeholderIcon?: string;
  }>(),
  { modelValue: null, disabled: false, placeholderIcon: 'photograph' }
);

const emit = defineEmits<{
  'update:modelValue': [value: string | null];
  uploaded: [url: string];
  error: [message: string];
}>();

const { uploading, upload } = useImageUpload();
const fileRef = ref<HTMLInputElement>();

const onPick = () => {
  if (props.disabled || uploading.value) return;
  fileRef.value?.click();
};

const onFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const url = await upload(file);
  if (url) {
    emit('update:modelValue', url);
    emit('uploaded', url);
  }
};
</script>

<template>
  <div class="image-uploader" :class="{ disabled }">
    <div class="preview" @click="onPick">
      <van-image v-if="modelValue" :src="modelValue" fit="cover" class="preview-img" />
      <div v-else class="placeholder">
        <van-loading v-if="uploading" color="#fff" size="20" />
        <template v-else>
          <van-icon :name="placeholderIcon" size="22" />
          <span>上传图片</span>
        </template>
      </div>
      <div v-if="modelValue && !uploading" class="overlay">
        <van-icon name="photograph" color="#fff" size="16" />
      </div>
    </div>
    <input
      ref="fileRef"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      class="hidden-input"
      :disabled="disabled"
      @change="onFileChange"
    />
  </div>
</template>

<style scoped>
.image-uploader.disabled {
  opacity: 0.6;
  pointer-events: none;
}

.preview {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px dashed var(--color-hairline-input);
  background: var(--color-canvas-soft);
  cursor: pointer;
}

.preview-img {
  width: 100%;
  height: 100%;
}

.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 100%;
  color: var(--color-ink-mute);
  font-size: 11px;
}

.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(13, 37, 61, 0.4);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.preview:hover .overlay {
  opacity: 1;
}

.hidden-input {
  display: none;
}
</style>
