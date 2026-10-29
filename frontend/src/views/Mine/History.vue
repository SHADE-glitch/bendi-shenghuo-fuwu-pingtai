<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { historyService } from '@/api/history';
import { getErrorMessage } from '@/api/client';
import { BrowseTargetType, type BrowseHistoryDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';
import { fallbackImage } from '@/utils/images';
import { usePagination } from '@/composables/usePagination';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

const router = useRouter();
const error = ref<string | null>(null);

const { list, loading, finished, load, loadMore } = usePagination<BrowseHistoryDTO>(
  async ({ current, size }) => {
    error.value = null;
    try {
      const res = await historyService.mine(current, size);
      return { list: res.data.data || [], total: Number(res.data.total || 0) };
    } catch (err) {
      error.value = getErrorMessage(err);
      return { list: [], total: 0 };
    }
  }
);

const open = (item: BrowseHistoryDTO) => {
  if (item.targetType === BrowseTargetType.SHOP) {
    router.push(`/shop/${item.targetId}`);
  } else {
    router.push(`/blog/${item.targetId}`);
  }
};

const clearAll = () => {
  showConfirmDialog({ title: '清空浏览历史', message: '确定清空全部浏览历史吗？' })
    .then(async () => {
      try {
        await historyService.clear();
        showToast('已清空');
        load(true);
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

onMounted(() => {
  load(true);
});
</script>

<template>
  <div class="history-page min-h-screen pb-16">
    <van-nav-bar title="浏览历史" left-arrow fixed placeholder @click-left="router.back()">
      <template #right>
        <!-- span 改 button：原写法无 role/tabindex，键盘无法触达「清空」 -->
        <button v-if="list.length" type="button" class="clear-btn van-nav-bar__text-btn" @click="clearAll">清空</button>
      </template>
    </van-nav-bar>

    <div class="list-wrap">
      <van-skeleton v-if="loading && !list.length" :row="3" />

      <ErrorState v-else-if="error" :description="error" @retry="load(true)" />

      <EmptyState v-else-if="!list.length" icon="clock-o" title="暂无浏览历史" description="去首页逛逛，看看附近的店铺吧">
        <van-button round type="primary" size="small" @click="router.push('/')">去逛逛</van-button>
      </EmptyState>

      <template v-else>
        <div class="history-list">
          <div v-for="item in list" :key="item.id" class="history-item card-stripe-light" @click="open(item)">
            <van-image
              class="history-thumb"
              :src="item.image || fallbackImage"
              fit="cover"
              radius="8"
            />
            <div class="history-copy">
              <div class="history-title">{{ item.title || '内容已下架' }}</div>
              <span class="history-meta stripe-caption">
                {{ item.targetType === BrowseTargetType.SHOP ? '店铺' : '笔记' }}
                · {{ timeAgo(item.createTime) }}
              </span>
            </div>
            <van-icon name="arrow" color="var(--color-ink-mute)" />
          </div>
        </div>

        <div class="load-more">
          <van-loading v-if="loading" size="18" color="var(--color-primary)" />
          <span v-else-if="finished" class="stripe-caption">没有更多了</span>
          <van-button v-else round plain type="primary" size="small" @click="loadMore()">
            加载更多
          </van-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.history-page {
  background: var(--color-canvas-soft);
}

.list-wrap {
  padding: 12px;
}

.clear-btn {
  color: var(--color-primary);
  font-size: 14px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  background: var(--color-canvas);
  cursor: pointer;
}

.history-thumb {
  flex-shrink: 0;
  width: 60px;
  height: 60px;
}

.history-copy {
  flex: 1;
  min-width: 0;
}

.history-title {
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-meta {
  color: var(--color-ink-mute);
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 0;
  color: var(--color-ink-mute);
}
</style>
