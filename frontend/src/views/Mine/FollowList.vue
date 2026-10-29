<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { followService } from '@/api/follow';
import { getErrorMessage } from '@/api/client';
import type { FollowUserDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';
import { usePagination } from '@/composables/usePagination';
import UserAvatar from '@/components/business/UserAvatar.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

const router = useRouter();

const activeTab = ref(0);
const error = ref<string | null>(null);
/** 正在请求中的用户id，用于按钮 loading */
const pendingIds = ref<Set<string>>(new Set());

const { list, total, loading, finished, load, loadMore } = usePagination<FollowUserDTO>(
  async ({ current, size }) => {
    error.value = null;
    try {
      const res =
        activeTab.value === 0
          ? await followService.following(current, size)
          : await followService.followers(current, size);
      return { list: res.data.data || [], total: Number(res.data.total || 0) };
    } catch (err) {
      error.value = getErrorMessage(err);
      return { list: [], total: 0 };
    }
  }
);

const isPending = (userId: number | string) => pendingIds.value.has(String(userId));

const patchFollowed = (userId: number | string, followed: boolean) => {
  // usePagination 的 list 为 shallowRef，替换数组以触发视图更新
  list.value = list.value.map((item) =>
    String(item.userId) === String(userId) ? { ...item, followed } : item
  );
};

const toggleFollow = async (user: FollowUserDTO) => {
  const id = String(user.userId);
  if (pendingIds.value.has(id)) return;
  pendingIds.value.add(id);
  const next = !user.followed;
  try {
    if (next) {
      await followService.follow(user.userId);
      showToast('已关注');
    } else {
      await followService.unfollow(user.userId);
      showToast('已取消关注');
    }
    patchFollowed(user.userId, next);
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    pendingIds.value.delete(id);
  }
};

const onLoadMore = () => {
  loadMore();
};

/** 进入他人主页（社交闭环：从关注/粉丝列表点进对方主页） */
const goProfile = (userId: number | string) => {
  router.push(`/user/${userId}`);
};

watch(activeTab, () => {
  load(true);
});

onMounted(() => {
  load(true);
});
</script>

<template>
  <div class="follow-page min-h-screen pb-16">
    <van-nav-bar title="关注与粉丝" left-arrow fixed placeholder @click-left="router.back()" />

    <van-tabs v-model:active="activeTab" color="var(--color-primary)" title-active-color="var(--color-ink)">
      <van-tab :title="`关注${total && activeTab === 0 ? ` (${total})` : ''}`" />
      <van-tab :title="`粉丝${total && activeTab === 1 ? ` (${total})` : ''}`" />
    </van-tabs>

    <div class="list-wrap">
      <div v-if="loading && !list.length" class="skeleton-wrap">
        <van-skeleton v-for="i in 3" :key="i" avatar :row="1" />
      </div>

      <ErrorState v-else-if="error" :description="error" @retry="load(true)" />

      <EmptyState
        v-else-if="!list.length"
        icon="friends-o"
        :title="activeTab === 0 ? '还没有关注任何人' : '还没有粉丝'"
        :description="activeTab === 0 ? '去笔记或店铺里发现有趣的用户吧' : '发布优质笔记，吸引更多人关注你'"
      />

      <template v-else>
        <div class="user-list">
          <div v-for="user in list" :key="user.userId" class="user-item card-stripe-light">
            <button type="button" class="user-main" @click="goProfile(user.userId)">
              <UserAvatar :src="user.icon" :size="44" />
              <div class="user-copy">
                <div class="user-name">{{ user.nickName || '匿名用户' }}</div>
                <span class="user-time stripe-caption">{{ timeAgo(user.createTime) }}</span>
              </div>
            </button>
            <van-button
              size="small"
              round
              :type="user.followed ? 'default' : 'primary'"
              :plain="user.followed"
              :loading="isPending(user.userId)"
              @click="toggleFollow(user)"
            >
              {{ user.followed ? '已关注' : '关注' }}
            </van-button>
          </div>
        </div>

        <div class="load-more">
          <van-loading v-if="loading" size="18" color="var(--color-primary)" />
          <span v-else-if="finished" class="stripe-caption">没有更多了</span>
          <van-button v-else round plain type="primary" size="small" @click="onLoadMore">
            加载更多
          </van-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.follow-page {
  background: var(--color-canvas-soft);
}

.list-wrap {
  padding: 12px;
}

.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}

.user-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.user-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  background: var(--color-canvas);
}

.user-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.user-copy {
  flex: 1;
  min-width: 0;
}

.user-name {
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-time {
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
