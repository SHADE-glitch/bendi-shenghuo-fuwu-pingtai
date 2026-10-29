<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { shopCommentService } from '@/api/shopComment';
import { blogService } from '@/api/blog';
import { getErrorMessage } from '@/api/client';
import type { BlogCommentDTO, ShopCommentDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';
import { usePagination } from '@/composables/usePagination';
import StarRating from '@/components/business/StarRating.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

const router = useRouter();
const activeTab = ref(0);

// ---------- 店铺评价 ----------
const shopError = ref<string | null>(null);
const shopPager = usePagination<ShopCommentDTO>(async ({ current, size }) => {
  shopError.value = null;
  try {
    const res = await shopCommentService.listMine(current, size);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  } catch (err) {
    shopError.value = getErrorMessage(err);
    return { list: [], total: 0 };
  }
});

// ---------- 笔记评论 ----------
const blogError = ref<string | null>(null);
const blogPager = usePagination<BlogCommentDTO>(async ({ current, size }) => {
  blogError.value = null;
  try {
    const res = await blogService.listMyComments(current, size);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  } catch (err) {
    blogError.value = getErrorMessage(err);
    return { list: [], total: 0 };
  }
});

const removeShopComment = (item: ShopCommentDTO) => {
  showConfirmDialog({ title: '删除评价', message: '确定删除这条评价吗？' })
    .then(async () => {
      try {
        await shopCommentService.remove(item.id);
        showToast('已删除');
        shopPager.load(true);
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

const removeBlogComment = (item: BlogCommentDTO) => {
  showConfirmDialog({ title: '删除评论', message: '确定删除这条评论吗？（其下的回复也会一并删除）' })
    .then(async () => {
      try {
        await blogService.deleteComment(item.blogId, item.id);
        showToast('已删除');
        blogPager.load(true);
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

const goShop = (shopId: number | string) => router.push(`/shop/${shopId}`);
const goBlog = (blogId: number | string) => router.push(`/blog/${blogId}`);

watch(activeTab, (tab) => {
  if (tab === 0) shopPager.load(true);
  else blogPager.load(true);
});

onMounted(() => {
  shopPager.load(true);
});
</script>

<template>
  <div class="my-comments-page min-h-screen pb-16">
    <van-nav-bar title="我的评价" left-arrow fixed placeholder @click-left="router.back()" />

    <van-tabs v-model:active="activeTab" color="var(--color-primary)" title-active-color="var(--color-ink)">
      <van-tab :title="`店铺评价${shopPager.total.value ? ` (${shopPager.total.value})` : ''}`" />
      <van-tab :title="`笔记评论${blogPager.total.value ? ` (${blogPager.total.value})` : ''}`" />
    </van-tabs>

    <!-- 店铺评价 -->
    <div v-show="activeTab === 0" class="list-wrap">
      <van-skeleton v-if="shopPager.loading.value && !shopPager.list.value.length" :row="3" />

      <ErrorState
        v-else-if="shopError"
        :description="shopError"
        @retry="shopPager.load(true)"
      />

      <EmptyState
        v-else-if="!shopPager.list.value.length"
        icon="comment-o"
        title="还没有评价过店铺"
        description="去店铺详情页分享你的消费体验吧"
      />

      <template v-else>
        <div class="comment-list">
          <div v-for="item in shopPager.list.value" :key="item.id" class="comment-card card-stripe-light">
            <div class="card-head">
              <button type="button" class="shop-name" @click="goShop(item.shopId)">
                {{ item.shopName || `店铺 #${item.shopId}` }}
              </button>
              <span class="time stripe-caption">{{ timeAgo(item.createTime) }}</span>
            </div>
            <StarRating :model-value="item.rating" :size="14" />
            <p class="comment-text">{{ item.content }}</p>
            <div class="card-actions">
              <van-button size="mini" plain round @click="removeShopComment(item)">删除</van-button>
            </div>
          </div>
        </div>

        <div class="load-more">
          <van-loading v-if="shopPager.loading.value" size="18" color="var(--color-primary)" />
          <span v-else-if="shopPager.finished.value" class="stripe-caption">没有更多了</span>
          <van-button v-else round plain type="primary" size="small" @click="shopPager.loadMore()">
            加载更多
          </van-button>
        </div>
      </template>
    </div>

    <!-- 笔记评论 -->
    <div v-show="activeTab === 1" class="list-wrap">
      <van-skeleton v-if="blogPager.loading.value && !blogPager.list.value.length" :row="3" />

      <ErrorState
        v-else-if="blogError"
        :description="blogError"
        @retry="blogPager.load(true)"
      />

      <EmptyState
        v-else-if="!blogPager.list.value.length"
        icon="chat-o"
        title="还没有评论过笔记"
        description="在感兴趣的笔记下留言互动吧"
      />

      <template v-else>
        <div class="comment-list">
          <div v-for="item in blogPager.list.value" :key="item.id" class="comment-card card-stripe-light">
            <div class="card-head">
              <button type="button" class="shop-name" @click="goBlog(item.blogId)">
                {{ item.blogTitle || `笔记 #${item.blogId}` }}
              </button>
              <span class="time stripe-caption">{{ timeAgo(item.createTime) }}</span>
            </div>
            <p class="comment-text">{{ item.content }}</p>
            <div class="card-actions">
              <van-button size="mini" plain round @click="removeBlogComment(item)">删除</van-button>
            </div>
          </div>
        </div>

        <div class="load-more">
          <van-loading v-if="blogPager.loading.value" size="18" color="var(--color-primary)" />
          <span v-else-if="blogPager.finished.value" class="stripe-caption">没有更多了</span>
          <van-button v-else round plain type="primary" size="small" @click="blogPager.loadMore()">
            加载更多
          </van-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.my-comments-page {
  background: var(--color-canvas-soft);
}

.list-wrap {
  padding: 12px;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comment-card {
  padding: 14px;
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  background: var(--color-canvas);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.shop-name {
  border: none;
  background: transparent;
  padding: 0;
  color: var(--color-primary);
  font-size: 15px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  flex-shrink: 0;
  color: var(--color-ink-mute);
}

.comment-text {
  margin: 8px 0 0;
  color: var(--color-ink-secondary);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 0;
  color: var(--color-ink-mute);
}
</style>
