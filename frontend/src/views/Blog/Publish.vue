<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { blogService } from '@/api/blog';
import { shopService } from '@/api/shop';
import { uploadService } from '@/api/upload';
import type { Shop } from '@/api/types';

const MAX_IMAGES = 9;
const ACCEPTED_TYPES = /^image\/(jpeg|png|webp)$/i;
const MAX_SIZE = 2 * 1024 * 1024;

const route = useRoute();
const router = useRouter();

const title = ref('');
const content = ref('');
const images = ref<string[]>([]);
const shop = ref<Shop | null>(null);
const submitting = ref(false);
const uploading = ref(false);
const fileInput = ref<HTMLInputElement>();

/** 携带 ?id= 时进入「编辑笔记」模式 */
const editId = computed(() => {
  const raw = route.query.id;
  return typeof raw === 'string' && raw ? raw : null;
});
const isEdit = computed(() => editId.value !== null);

const loadPresetShop = async () => {
  // 编辑模式：回填原有笔记内容
  if (editId.value) {
    try {
      const res = await blogService.getBlogDetail(editId.value);
      const detail = res.data.data;
      if (detail) {
        title.value = detail.title || '';
        content.value = detail.content || '';
        images.value = [...(detail.images || [])];
        if (detail.shopId != null) {
          const shopRes = await shopService.queryById(detail.shopId);
          shop.value = shopRes.data.data ?? null;
        }
      }
    } catch {
      showToast('笔记加载失败');
    }
    return;
  }
  // 从商铺详情页「写点评」进入时预填店铺
  const presetShopId = route.query.shopId as string | undefined;
  if (!presetShopId) return;
  try {
    const res = await shopService.queryById(presetShopId);
    shop.value = res.data.data ?? null;
  } catch {
    /* 预填失败可忽略，用户仍可手动选择 */
  }
};

// 店铺搜索选择
const showShopPicker = ref(false);
const shopKeyword = ref('');
const shopResults = ref<Shop[]>([]);
const shopSearching = ref(false);

const searchShops = async () => {
  const keyword = shopKeyword.value.trim();
  if (!keyword) return;
  shopSearching.value = true;
  try {
    const res = await shopService.queryByName(keyword, 1);
    shopResults.value = res.data.data || [];
  } catch {
    shopResults.value = [];
  } finally {
    shopSearching.value = false;
  }
};

const selectShop = (s: Shop) => {
  shop.value = s;
  showShopPicker.value = false;
};

// 图片上传
const onPickImages = () => {
  if (uploading.value) return;
  if (images.value.length >= MAX_IMAGES) {
    showToast(`最多上传 ${MAX_IMAGES} 张图片`);
    return;
  }
  fileInput.value?.click();
};

const onFilesChange = async (e: Event) => {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  const remaining = MAX_IMAGES - images.value.length;
  const picked = files.slice(0, remaining);

  for (const file of picked) {
    if (!ACCEPTED_TYPES.test(file.type)) {
      showToast('仅支持 jpg/png/webp 格式的图片');
      continue;
    }
    if (file.size > MAX_SIZE) {
      showToast('图片大小不能超过 2MB');
      continue;
    }
    uploading.value = true;
    try {
      const res = await uploadService.uploadIcon(file);
      if (res.data.success && res.data.data) {
        images.value.push(res.data.data);
      }
    } catch {
      /* 错误提示由拦截器统一处理 */
    } finally {
      uploading.value = false;
    }
  }
};

const removeImage = (index: number) => {
  images.value.splice(index, 1);
};

// 发布
const submit = async () => {
  if (!shop.value) {
    showToast('请选择关联店铺');
    return;
  }
  if (!title.value.trim()) {
    showToast('请输入标题');
    return;
  }
  if (!content.value.trim()) {
    showToast('请输入正文');
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      title: title.value.trim(),
      content: content.value.trim(),
      images: images.value,
      shopId: shop.value.id,
    };
    const res = editId.value
      ? await blogService.updateBlog(editId.value, payload)
      : await blogService.createBlog(payload);
    if (res.data.success) {
      showToast(isEdit.value ? '已保存' : '发布成功');
      router.replace('/my-blogs');
    }
  } catch (err) {
    showToast((err as { errorMsg?: string })?.errorMsg || '发布失败，请重试');
  } finally {
    submitting.value = false;
  }
};

onMounted(loadPresetShop);
</script>

<template>
  <div class="publish-page min-h-screen pb-24">
    <van-nav-bar
      :title="isEdit ? '编辑笔记' : '发布探店笔记'"
      left-arrow
      fixed
      placeholder
      @click-left="router.back()"
    >
      <!-- 用真 <button> 而非 right-text：Vant 的 right-text 渲染成裸 span，无 role/tabindex，键盘不可达 -->
      <template #right>
        <button type="button" class="van-nav-bar__text van-nav-bar__text-btn" @click="submit">
          {{ isEdit ? '保存' : '发布' }}
        </button>
      </template>
    </van-nav-bar>

    <div class="publish-form card-stripe-light">
      <!-- 关联店铺 -->
      <div class="field-row">
        <span class="field-label">关联店铺</span>
        <button type="button" class="shop-picker" @click="showShopPicker = true">
          <span :class="{ placeholder: !shop }">{{ shop ? shop.name : '点击选择店铺' }}</span>
          <van-icon name="arrow" size="14" class="text-gray-300" />
        </button>
      </div>

      <!-- 标题 -->
      <div class="field-row">
        <span class="field-label">标题</span>
        <input
          v-model="title"
          class="field-input"
          maxlength="60"
          placeholder="给你的探店笔记起个标题"
        />
      </div>

      <!-- 正文 -->
      <div class="content-field">
        <textarea
          v-model="content"
          class="content-input"
          rows="6"
          maxlength="2000"
          placeholder="记录这次探店的体验、亮点和踩坑……"
        />
        <span class="word-count stripe-caption">{{ content.length }}/2000</span>
      </div>

      <!-- 图片 -->
      <div class="images-field">
        <span class="field-label">图片（选填，最多 9 张）</span>
        <div class="images-grid">
          <div v-for="(img, i) in images" :key="img" class="image-item">
            <van-image :src="img" fit="cover" width="100%" height="100%" radius="8" />
            <button type="button" class="image-remove" @click="removeImage(i)">
              <van-icon name="cross" size="12" color="#fff" />
            </button>
          </div>

          <button
            v-if="images.length < MAX_IMAGES"
            type="button"
            class="image-add"
            :disabled="uploading"
            @click="onPickImages"
          >
            <van-loading v-if="uploading" size="20" color="var(--color-primary)" />
            <template v-else>
              <van-icon name="photograph" size="22" color="var(--color-ink-mute)" />
              <span>上传图片</span>
            </template>
          </button>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          class="hidden-input"
          @change="onFilesChange"
        />
      </div>
    </div>

    <!-- 店铺选择弹窗 -->
    <van-popup v-model:show="showShopPicker" position="bottom" round :style="{ height: '70%' }">
      <div class="shop-picker-sheet">
        <div class="sheet-title">选择关联店铺</div>
        <div class="search-row">
          <van-search
            v-model="shopKeyword"
            placeholder="搜索店铺名称"
            shape="round"
            background="transparent"
            @search="searchShops"
          />
        </div>
        <div class="shop-list">
          <van-loading v-if="shopSearching" class="shop-loading" />
          <div v-else-if="shopResults.length === 0" class="shop-empty">输入关键词搜索店铺</div>
          <button
            v-for="s in shopResults"
            :key="s.id"
            type="button"
            class="shop-result"
            :class="{ active: shop?.id === s.id }"
            @click="selectShop(s)"
          >
            <span class="shop-name">{{ s.name }}</span>
            <span class="shop-meta stripe-caption">{{ s.area || '本地' }} · {{ s.address }}</span>
          </button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.publish-page {
  background: var(--color-canvas-soft);
}

.publish-form {
  margin: 12px;
  padding: 4px 16px;
  border-radius: 12px;
}

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-hairline);
}

.field-label {
  flex-shrink: 0;
  width: 64px;
  color: var(--color-ink-secondary);
  font-size: 14px;
}

.field-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--color-ink);
  font-size: 15px;
}

.shop-picker {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: none;
  background: transparent;
  color: var(--color-ink);
  font-size: 15px;
  cursor: pointer;
  text-align: left;
}

.shop-picker .placeholder {
  color: var(--color-ink-mute);
}

.content-field {
  position: relative;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-hairline);
}

.content-input {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--color-ink);
  font-size: 15px;
  line-height: 1.6;
}

.word-count {
  display: block;
  text-align: right;
  color: var(--color-ink-mute);
}

.images-field {
  padding: 14px 0;
}

.images-field .field-label {
  display: block;
  width: auto;
  margin-bottom: 10px;
}

.images-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.image-item {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 8px;
}

.image-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.image-add {
  aspect-ratio: 1;
  border: 1px dashed var(--color-hairline-input);
  border-radius: 8px;
  background: var(--color-canvas-soft);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--color-ink-mute);
  font-size: 11px;
  cursor: pointer;
}

.image-add:disabled {
  opacity: 0.6;
}

.hidden-input {
  display: none;
}

.shop-picker-sheet {
  padding: 16px;
}

.sheet-title {
  color: var(--color-ink);
  font-size: 15px;
  margin-bottom: 4px;
}

.search-row {
  margin: 0 -16px;
}

.shop-list {
  max-height: calc(70vh - 130px);
  overflow-y: auto;
}

.shop-loading,
.shop-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--color-ink-mute);
  font-size: 13px;
}

.shop-result {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 13px 4px;
  border: none;
  border-bottom: 1px solid var(--color-hairline);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.shop-result.active {
  color: var(--color-primary);
}

.shop-name {
  color: var(--color-ink);
  font-size: 15px;
}

.shop-result.active .shop-name {
  color: var(--color-primary);
}

.shop-meta {
  color: var(--color-ink-mute);
}
</style>
