<script setup lang="ts">
import { computed } from 'vue';
import type { Blog } from '@/api/types';
import { firstImage, timeAgo, plainText } from '@/utils/format';
import ImageWithFallback from '@/components/ImageWithFallback.vue';

const props = withDefaults(
  defineProps<{
    blog: Blog;
    clickable?: boolean;
  }>(),
  { clickable: true }
);

const emit = defineEmits<{
  select: [id: number | string];
}>();

const cover = computed(() => firstImage(props.blog.images));

/** 种子数据正文用 <br/> 表示换行，摘要按纯文本渲染需还原成换行符 */
const contentText = computed(() => plainText(props.blog.content));
</script>

<template>
  <article class="blog-card" :class="{ clickable }" @click="clickable && emit('select', blog.id)">
    <div class="blog-copy">
      <h3 class="blog-title">{{ blog.title }}</h3>
      <p v-if="blog.content" class="blog-content">{{ contentText }}</p>
      <div class="blog-meta stripe-caption">
        <span v-if="blog.createTime">{{ timeAgo(blog.createTime) }}</span>
        <span class="blog-likes">
          <van-icon name="like-o" /> {{ blog.liked }}
        </span>
      </div>
    </div>

    <ImageWithFallback
      v-if="cover"
      :src="cover"
      class="blog-cover"
      width="96"
      height="96"
      radius="8"
    />
  </article>
</template>

<style scoped>
.blog-card {
  display: flex;
  gap: 12px;
  padding: 14px;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  box-shadow: rgba(0, 55, 112, 0.08) 0 1px 3px;
}

.blog-card.clickable {
  cursor: pointer;
  transition: all 0.2s ease;
}

.blog-card.clickable:hover {
  box-shadow: rgba(0, 55, 112, 0.12) 0 4px 12px, rgba(0, 55, 112, 0.08) 0 2px 6px;
  transform: translateY(-2px);
}

.blog-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.blog-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 300;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.blog-content {
  margin: 8px 0 0;
  color: var(--color-ink-secondary);
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.blog-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: auto;
  padding-top: 10px;
  color: var(--color-ink-mute);
}

.blog-likes {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.blog-cover {
  flex-shrink: 0;
}
</style>
