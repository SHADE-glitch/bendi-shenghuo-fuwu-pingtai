<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast } from 'vant';
import { userService } from '@/api/user';
import { followService } from '@/api/follow';
import { getErrorMessage } from '@/api/client';
import { useUserStore } from '@/store/user';
import type { Blog, UserProfileDTO } from '@/api/types';
import { usePagination } from '@/composables/usePagination';
import UserAvatar from '@/components/business/UserAvatar.vue';
import BlogCard from '@/components/business/BlogCard.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const userId = computed(() => String(route.params.id ?? ''));

const profile = ref<UserProfileDTO | null>(null);
const profileLoading = ref(true);
const profileError = ref<string | null>(null);

const isSelf = computed(
  () => userStore.user?.id != null && String(userStore.user.id) === userId.value
);
const canFollow = computed(() => !!userStore.token && !!profile.value && !isSelf.value);

const followed = ref(false);
const followPending = ref(false);

const blogCount = computed(() => Number(profile.value?.blogCount ?? 0));

const loadProfile = async () => {
  profileLoading.value = true;
  profileError.value = null;
  try {
    const res = await userService.profile(userId.value);
    profile.value = res.data.data;
    followed.value = !!res.data.data?.followedByMe;
  } catch (err) {
    profileError.value = getErrorMessage(err);
  } finally {
    profileLoading.value = false;
  }
};

const toggleFollow = async () => {
  if (followPending.value || !profile.value) return;
  followPending.value = true;
  const next = !followed.value;
  try {
    if (next) {
      await followService.follow(userId.value);
    } else {
      await followService.unfollow(userId.value);
    }
    followed.value = next;
    showToast(next ? '已关注' : '已取消关注');
    profile.value = {
      ...profile.value,
      followedByMe: next,
      fans: Math.max(0, (Number(profile.value.fans) || 0) + (next ? 1 : -1)),
    };
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    followPending.value = false;
  }
};

// ---------- 该用户的笔记 ----------
const {
  list: blogs,
  loading: blogLoading,
  finished,
  load: loadBlogs,
  loadMore,
} = usePagination<Blog>(async ({ current, size }) => {
  try {
    const res = await userService.userBlogs(userId.value, current, size);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  } catch {
    return { list: [], total: 0 };
  }
});

const openBlog = (id: number | string) => router.push(`/blog/${id}`);

const goFollows = () => {
  if (isSelf.value) router.push('/follows');
};

const goBack = () => {
  if (window.history.length > 1) router.back();
  else router.replace('/');
};

onMounted(() => {
  loadProfile();
  loadBlogs(true);
});
</script>

<template>
  <div class="user-profile-page min-h-screen pb-16">
    <van-nav-bar
      :title="profile?.nickName || '用户主页'"
      left-arrow
      fixed
      placeholder
      @click-left="goBack"
    />

    <div v-if="profileLoading" class="skeleton-wrap">
      <van-skeleton avatar :row="3" />
    </div>

    <ErrorState v-else-if="profileError || !profile" :description="profileError || '用户不存在'" @retry="loadProfile" />

    <template v-else>
      <!-- 资料卡 -->
      <section class="profile-card card-stripe-light">
        <div class="profile-head">
          <UserAvatar :src="profile.icon" :size="64" />
          <div class="profile-copy">
            <h1 class="profile-name">{{ profile.nickName || '匿名用户' }}</h1>
            <p class="profile-intro stripe-caption">
              {{ profile.introduce || '这个人很懒，还没有留下简介' }}
            </p>
          </div>
          <van-button
            v-if="canFollow"
            size="small"
            round
            :type="followed ? 'default' : 'primary'"
            :plain="followed"
            :loading="followPending"
            @click="toggleFollow"
          >
            {{ followed ? '已关注' : '+ 关注' }}
          </van-button>
          <van-button v-else-if="isSelf" size="small" round plain type="default" @click="router.push('/info')">
            编辑资料
          </van-button>
        </div>

        <div class="stat-row">
          <button type="button" class="stat-item" :class="{ clickable: isSelf }" @click="goFollows">
            <strong>{{ profile.followee ?? 0 }}</strong>
            <span class="stripe-caption">关注</span>
          </button>
          <button type="button" class="stat-item" :class="{ clickable: isSelf }" @click="goFollows">
            <strong>{{ profile.fans ?? 0 }}</strong>
            <span class="stripe-caption">粉丝</span>
          </button>
          <div class="stat-item">
            <strong>{{ blogCount }}</strong>
            <span class="stripe-caption">笔记</span>
          </div>
        </div>
      </section>

      <!-- 笔记列表 -->
      <section class="blog-section">
        <h2 class="section-title">TA 的笔记</h2>

        <van-skeleton v-if="blogLoading && !blogs.length" :row="3" />

        <EmptyState
          v-else-if="!blogs.length"
          icon="notes-o"
          title="还没有发布笔记"
          description="TA 还没有公开的探店记录"
        />

        <template v-else>
          <div class="blog-list">
            <BlogCard v-for="blog in blogs" :key="blog.id" :blog="blog" @select="openBlog" />
          </div>

          <div class="load-more">
            <van-loading v-if="blogLoading" size="18" color="var(--color-primary)" />
            <span v-else-if="finished" class="stripe-caption">没有更多了</span>
            <van-button v-else size="small" round plain type="primary" @click="loadMore()">
              加载更多
            </van-button>
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.user-profile-page {
  background: var(--color-canvas-soft);
}

.skeleton-wrap {
  padding: 20px 16px;
}

.profile-card {
  margin: 12px;
  padding: 18px 16px 8px;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 14px;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 14px;
}

.profile-copy {
  min-width: 0;
  flex: 1;
}

.profile-name {
  margin: 0;
  color: var(--color-ink);
  font-size: 18px;
  font-weight: 600;
}

.profile-intro {
  margin: 6px 0 0;
  color: var(--color-ink-mute);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.stat-row {
  display: flex;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--color-hairline);
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 0;
  border: none;
  background: transparent;
  cursor: default;
}

.stat-item.clickable {
  cursor: pointer;
}

.stat-item strong {
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 700;
}

.stat-item span {
  color: var(--color-ink-mute);
}

.blog-section {
  padding: 4px 12px 0;
}

.section-title {
  margin: 8px 4px 10px;
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 700;
}

.blog-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 0;
  color: var(--color-ink-mute);
}
</style>
