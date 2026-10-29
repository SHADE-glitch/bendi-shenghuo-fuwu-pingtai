<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { blogService } from '@/api/blog';
import type { Blog } from '@/api/types';
import BlogCard from '@/components/business/BlogCard.vue';

const PAGE_SIZE = 10;

const router = useRouter();
const list = ref<Blog[]>([]);
const loading = ref(false);
const finished = ref(false);
const current = ref(1);

const load = async (reset = false) => {
  if (loading.value) return;
  if (reset) {
    current.value = 1;
    list.value = [];
    finished.value = false;
  }
  loading.value = true;
  try {
    const res = await blogService.getMyBlogs(current.value, PAGE_SIZE);
    const items = res.data.data || [];
    list.value = reset ? items : [...list.value, ...items];
    if (items.length < PAGE_SIZE) finished.value = true;
  } catch (err) {
    showToast((err as { errorMsg?: string })?.errorMsg || '加载失败');
  } finally {
    loading.value = false;
  }
};

const loadMore = () => {
  if (finished.value || loading.value) return;
  current.value += 1;
  load();
};

const goPublish = () => router.push('/publish');
const goDetail = (id: number | string) => router.push(`/blog/${id}`);
const goBack = () => router.back();
const goEdit = (id: number | string) => router.push({ path: '/publish', query: { id: String(id) } });

// ---------- 删除笔记 ----------
const removingId = ref<string | null>(null);
const onDelete = (blog: Blog) => {
  showConfirmDialog({ title: '删除笔记', message: '删除后笔记及其评论将无法恢复，确定删除吗？' })
    .then(async () => {
      removingId.value = String(blog.id);
      try {
        await blogService.deleteBlog(blog.id);
        showToast('已删除');
        load(true);
      } catch (err) {
        showToast((err as { errorMsg?: string })?.errorMsg || '删除失败');
      } finally {
        removingId.value = null;
      }
    })
    .catch(() => {});
};

onMounted(() => load(true));
</script>

<template>
  <div class="my-blogs-page min-h-screen pb-24">
    <van-nav-bar title="我的笔记" left-arrow fixed placeholder @click-left="goBack">
      <!-- 用真 <button> 而非 right-text：Vant 的 right-text 渲染成裸 span，无 role/tabindex，键盘不可达 -->
      <template #right>
        <button type="button" class="van-nav-bar__text van-nav-bar__text-btn" @click="goPublish">写笔记</button>
      </template>
    </van-nav-bar>

    <div class="list-wrap">
      <van-skeleton v-if="loading && !list.length" title :row="3" />

      <div v-else-if="list.length === 0" class="empty-state">
        <van-icon name="notes-o" size="56" color="var(--color-ink-mute)" />
        <p>还没有发布过探店笔记</p>
        <van-button round type="primary" size="small" @click="goPublish">
          去写第一篇笔记
        </van-button>
      </div>

      <template v-else>
        <div class="blog-list">
          <div v-for="blog in list" :key="blog.id" class="blog-item">
            <BlogCard :blog="blog" @select="goDetail" />
            <div class="blog-actions">
              <button type="button" class="action-btn" @click="goEdit(blog.id)">编辑</button>
              <button
                type="button"
                class="action-btn"
                :disabled="removingId === String(blog.id)"
                @click="onDelete(blog)"
              >
                {{ removingId === String(blog.id) ? '删除中' : '删除' }}
              </button>
            </div>
          </div>
        </div>

        <div class="load-more">
          <van-loading v-if="loading" size="18" color="var(--color-primary)" />
          <span v-else-if="finished" class="stripe-caption">没有更多了</span>
          <van-button v-else round plain type="primary" size="small" @click="loadMore">
            加载更多
          </van-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.my-blogs-page {
  background: var(--color-canvas-soft);
}

.list-wrap {
  padding: 12px;
}

.blog-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.blog-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.blog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 16px;
  padding-right: 4px;
}

.action-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-ink-mute);
  font-size: 12px;
  cursor: pointer;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-state {
  padding: 90px 20px;
  text-align: center;
  color: var(--color-ink-mute);
}

.empty-state p {
  margin: 12px 0 18px;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 0;
  color: var(--color-ink-mute);
}
</style>
