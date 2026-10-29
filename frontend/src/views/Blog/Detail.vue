<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { showConfirmDialog, showToast } from 'vant';
import { blogService } from '@/api/blog';
import { followService } from '@/api/follow';
import { historyService } from '@/api/history';
import { getErrorMessage } from '@/api/client';
import { useUserStore } from '@/store/user';
import { BrowseTargetType, type BlogCommentDTO, type BlogDetailDTO } from '@/api/types';
import { timeAgo, plainText } from '@/utils/format';
import { parseImageList } from '@/utils/images';
import { usePagination } from '@/composables/usePagination';
import UserAvatar from '@/components/business/UserAvatar.vue';
import ImageWithFallback from '@/components/ImageWithFallback.vue';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const blogId = route.params.id as string;

const { data: blog, isLoading, isError, refetch } = useQuery({
  queryKey: ['blogDetail', blogId],
  queryFn: () => blogService.getBlogDetail(blogId).then((res) => res.data.data),
  refetchOnWindowFocus: false,
});

const images = computed(() => parseImageList(blog.value?.images));

/** 种子数据正文用 <br/> 表示换行，按纯文本渲染时需还原成换行符 */
const contentText = computed(() => plainText(blog.value?.content));

// ---------- 点赞（按用户幂等，状态以后端为准） ----------
// 注意：useQuery 返回的 data 是 readonly，不能直接改；必须回写 query cache
const queryClient = useQueryClient();
const blogQueryKey = ['blogDetail', blogId];

const likeMutation = useMutation({
  mutationFn: () =>
    blog.value?.likedByMe
      ? blogService.unlikeBlog(blogId)
      : blogService.likeBlog(blogId),
  onSuccess: (res) => {
    const data = res.data.data;
    if (!data) return;
    queryClient.setQueryData<BlogDetailDTO | undefined>(blogQueryKey, (old) =>
      old
        ? { ...old, likedByMe: data.liked, liked: data.likedCount ?? old.liked }
        : old
    );
  },
  onError: (err) => showToast(getErrorMessage(err)),
});

// ---------- 关注作者 ----------
const authorId = computed(() => blog.value?.author?.id ?? null);
// 必须已知当前登录用户，且不是自己，才展示关注按钮（避免刷新后 user 未就绪时误显示）
const canFollow = computed(
  () =>
    !!userStore.token &&
    userStore.user != null &&
    authorId.value != null &&
    String(authorId.value) !== String(userStore.user.id)
);

// 关注状态：拉一次「我的关注」列表做判断（后端未提供单点查询接口）
const { data: followingIds } = useQuery({
  queryKey: ['followingIds'],
  queryFn: async () => {
    const res = await followService.following(1, 200);
    return (res.data.data || []).map((u) => String(u.userId));
  },
  enabled: computed(() => !!userStore.token),
  refetchOnWindowFocus: false,
});

const followed = computed(() => {
  const id = authorId.value;
  return id != null && (followingIds.value || []).includes(String(id));
});

const followPending = ref(false);
const toggleFollow = async () => {
  const id = authorId.value;
  if (id == null || followPending.value) return;
  const willUnfollow = followed.value;
  followPending.value = true;
  try {
    if (willUnfollow) {
      await followService.unfollow(id);
    } else {
      await followService.follow(id);
    }
    queryClient.setQueryData<string[]>(['followingIds'], (old) =>
      willUnfollow
        ? (old || []).filter((x) => x !== String(id))
        : [...(old || []), String(id)]
    );
    showToast(willUnfollow ? '已取消关注' : '关注成功');
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    followPending.value = false;
  }
};

// ---------- 评论 ----------
const commentError = ref<string | null>(null);
const {
  list: comments,
  total: commentTotal,
  loading: commentLoading,
  finished: commentFinished,
  load: loadComments,
  loadMore: loadMoreComments,
} = usePagination<BlogCommentDTO>(async ({ current, size }) => {
  commentError.value = null;
  try {
    const res = await blogService.listComments(blogId, current, size);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  } catch (err) {
    commentError.value = getErrorMessage(err);
    return { list: [], total: 0 };
  }
});

const draft = ref('');
const replyTarget = ref<BlogCommentDTO | null>(null);
const submitting = ref(false);

const isMine = (c: BlogCommentDTO) =>
  userStore.user?.id != null && String(c.userId) === String(userStore.user.id);

const startReply = (c: BlogCommentDTO) => {
  replyTarget.value = c;
};

const cancelReply = () => {
  replyTarget.value = null;
};

const submitComment = async () => {
  const content = draft.value.trim();
  if (!content) {
    showToast('请输入评论内容');
    return;
  }
  if (content.length > 255) {
    showToast('评论内容最长255个字符');
    return;
  }
  submitting.value = true;
  try {
    const target = replyTarget.value;
    await blogService.createComment(blogId, {
      content,
      parentId: target ? target.parentId && Number(target.parentId) !== 0 ? target.parentId : target.id : 0,
      answerId: target ? target.id : 0,
    });
    showToast('评论成功');
    draft.value = '';
    replyTarget.value = null;
    loadComments(true);
    refetch();
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    submitting.value = false;
  }
};

const removeComment = (c: BlogCommentDTO) => {
  showConfirmDialog({ title: '删除评论', message: '确定删除这条评论吗？' })
    .then(async () => {
      try {
        await blogService.deleteComment(blogId, c.id);
        showToast('已删除');
        loadComments(true);
        refetch();
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

/** 作者本人才能编辑 / 删除 */
const isAuthor = computed(
  () => userStore.user?.id != null && String(blog.value?.userId) === String(userStore.user.id)
);

const goEdit = () => {
  if (blog.value) router.push({ path: '/publish', query: { id: String(blog.value.id) } });
};

const removeBlog = () => {
  showConfirmDialog({ title: '删除笔记', message: '删除后笔记及其评论将无法恢复，确定删除吗？' })
    .then(async () => {
      try {
        await blogService.deleteBlog(blogId);
        showToast('已删除');
        router.replace('/my-blogs');
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

const goShop = () => {
  if (blog.value?.shopId) router.push(`/shop/${blog.value.shopId}`);
};

/** 进入他人主页 */
const goAuthor = () => {
  if (authorId.value != null) router.push(`/user/${authorId.value}`);
};

const goBack = () => router.back();

onMounted(() => {
  loadComments(true);
  // 已登录时上报浏览历史（笔记也计入历史），失败静默不影响阅读
  if (userStore.token) {
    historyService.record({ targetType: BrowseTargetType.BLOG, targetId: blogId }).catch(() => {});
  }
});
</script>

<template>
  <div class="blog-detail-page min-h-screen pb-40">
    <van-nav-bar title="笔记详情" left-arrow fixed placeholder @click-left="goBack" />

    <div v-if="isLoading" class="p-10 text-center">
      <van-loading type="spinner" size="24">加载中...</van-loading>
    </div>

    <div v-else-if="isError || !blog" class="empty-state">
      <van-icon name="description-o" size="52" color="var(--color-ink-mute)" />
      <p>笔记不存在或已删除</p>
      <van-button round type="primary" size="small" @click="refetch">重试</van-button>
    </div>

    <template v-else>
      <!-- 作者 -->
      <div class="author-row">
        <button type="button" class="author-main" @click="goAuthor">
          <UserAvatar :src="blog.author?.icon" :size="44" />
          <div class="author-copy">
            <div class="author-name">{{ blog.author?.nickName || '匿名用户' }}</div>
            <button type="button" class="shop-link stripe-caption" @click.stop="goShop">
              <van-icon name="shop-o" size="12" /> {{ blog.shopName || '探店笔记' }}
            </button>
          </div>
        </button>
        <button
          v-if="canFollow"
          type="button"
          class="follow-btn"
          :class="{ followed: followed }"
          :disabled="followPending"
          @click="toggleFollow"
        >
          {{ followed ? '已关注' : '+ 关注' }}
        </button>
        <template v-if="isAuthor">
          <button type="button" class="edit-btn" @click="goEdit">编辑</button>
          <button type="button" class="edit-btn" @click="removeBlog">删除</button>
        </template>
        <span v-else class="time stripe-caption">{{ timeAgo(blog.createTime) }}</span>
      </div>

      <!-- 图片 -->
      <van-swipe v-if="images.length" class="blog-swipe" :autoplay="0" indicator-color="var(--color-primary)">
        <van-swipe-item v-for="img in images" :key="img">
          <ImageWithFallback :src="img" fit="cover" class="blog-image" />
        </van-swipe-item>
      </van-swipe>

      <!-- 标题与正文 -->
      <div class="blog-body">
        <h1 class="blog-title stripe-heading-lg">{{ blog.title }}</h1>
        <p class="blog-content stripe-body-lg">{{ contentText }}</p>
      </div>

      <!-- 点赞 -->
      <div class="like-bar">
        <button
          type="button"
          class="like-btn"
          :class="{ active: blog.likedByMe }"
          :disabled="likeMutation.isPending.value"
          @click="likeMutation.mutate()"
        >
          <van-icon :name="blog.likedByMe ? 'like' : 'like-o'" size="20" />
          <span>{{ blog.liked }} 赞</span>
        </button>
      </div>

      <!-- 评论 -->
      <div class="comments-section">
        <div class="comments-header">
          <h2 class="comments-title stripe-heading-sm">评论（{{ commentTotal || blog.comments || 0 }}）</h2>
        </div>

        <van-skeleton v-if="commentLoading && !comments.length" :row="2" />
        <div v-else-if="commentError" class="comments-empty stripe-caption">{{ commentError }}</div>
        <div v-else-if="!comments.length" class="comments-empty stripe-caption">
          暂无评论，快来抢沙发～
        </div>
        <div v-else class="comments-list">
          <div v-for="c in comments" :key="c.id" class="comment-item">
            <UserAvatar :src="c.icon" :size="34" />
            <div class="comment-body">
              <div class="comment-head">
                <span class="comment-name">{{ c.nickName || '匿名用户' }}</span>
                <span class="comment-time stripe-caption">{{ timeAgo(c.createTime) }}</span>
              </div>
              <p class="comment-text">
                <template v-if="Number(c.parentId) !== 0 && c.answerNickName">
                  <span class="reply-at">回复 @{{ c.answerNickName }}：</span>
                </template>
                {{ c.content }}
              </p>
              <div class="comment-actions">
                <button type="button" class="link-btn" @click="startReply(c)">回复</button>
                <button v-if="isMine(c)" type="button" class="link-btn" @click="removeComment(c)">删除</button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="comments.length" class="comments-more">
          <van-loading v-if="commentLoading" size="16" color="var(--color-primary)" />
          <span v-else-if="commentFinished" class="stripe-caption">没有更多了</span>
          <van-button v-else size="mini" round plain type="primary" @click="loadMoreComments()">
            加载更多
          </van-button>
        </div>
      </div>
    </template>

    <!-- 评论输入栏 -->
    <div v-if="blog" class="comment-bar">
      <div v-if="replyTarget" class="reply-hint">
        回复 @{{ replyTarget.nickName || '匿名用户' }}
        <button type="button" class="link-btn" @click="cancelReply">取消</button>
      </div>
      <div class="comment-bar-row">
        <van-field
          v-model="draft"
          rows="1"
          autosize
          type="textarea"
          maxlength="255"
          :placeholder="replyTarget ? '回复评论...' : '说点什么...'"
        />
        <van-button
          size="small"
          round
          type="primary"
          :loading="submitting"
          :disabled="!draft.trim()"
          @click="submitComment"
        >
          发送
        </van-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.blog-detail-page {
  background: var(--color-canvas);
}

.empty-state {
  padding: 90px 20px;
  text-align: center;
  color: var(--color-ink-mute);
}

.empty-state p {
  margin: 12px 0 18px;
}

.author-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}

.author-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.author-copy {
  min-width: 0;
  flex: 1;
}

.edit-btn {
  flex-shrink: 0;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--color-hairline-input);
  background: transparent;
  color: var(--color-ink-secondary);
  font-size: 13px;
  cursor: pointer;
}

.author-name {
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 400;
}

.shop-link {
  margin-top: 2px;
  border: none;
  background: transparent;
  padding: 0;
  color: var(--color-primary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.time {
  color: var(--color-ink-mute);
}

.follow-btn {
  flex-shrink: 0;
  padding: 5px 14px;
  border-radius: 999px;
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.4;
  cursor: pointer;
  transition: all 0.2s ease;
}

.follow-btn.followed {
  background: transparent;
  color: var(--color-ink-mute);
  border-color: var(--color-hairline-input);
}

.follow-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.blog-swipe {
  height: 280px;
  background: var(--color-canvas-soft);
}

.blog-image {
  width: 100%;
  height: 280px;
}

.blog-body {
  padding: 20px 16px;
}

.blog-title {
  margin: 0 0 12px;
  color: var(--color-ink);
}

.blog-content {
  margin: 0;
  color: var(--color-ink-secondary);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.like-bar {
  display: flex;
  justify-content: center;
  padding: 8px 0 20px;
}

.like-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 24px;
  border: 1px solid var(--color-hairline);
  border-radius: 999px;
  background: var(--color-canvas);
  color: var(--color-ink-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.like-btn.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-on-primary);
}

.like-btn:disabled {
  opacity: 0.6;
}

.comments-section {
  margin: 0 16px;
  padding-top: 16px;
  border-top: 1px solid var(--color-hairline);
}

.comments-header {
  margin-bottom: 8px;
}

.comments-title {
  margin: 0;
  color: var(--color-ink);
}

.comments-empty {
  padding: 24px 0;
  text-align: center;
  color: var(--color-ink-mute);
}

.comments-list {
  display: flex;
  flex-direction: column;
}

.comment-item {
  display: flex;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-hairline);
}

.comment-item:last-child {
  border-bottom: none;
}

.comment-body {
  flex: 1;
  min-width: 0;
}

.comment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.comment-name {
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 600;
}

.comment-time {
  color: var(--color-ink-mute);
}

.comment-text {
  margin: 6px 0 0;
  color: var(--color-ink-secondary);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.reply-at {
  color: var(--color-primary);
}

.comment-actions {
  display: flex;
  gap: 14px;
  margin-top: 6px;
}

.link-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-ink-mute);
  font-size: 12px;
  cursor: pointer;
}

.comments-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 14px 0 0;
  color: var(--color-ink-mute);
}

.comment-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  background: var(--color-canvas);
  border-top: 1px solid var(--color-hairline);
}

.reply-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 6px;
  color: var(--color-ink-mute);
  font-size: 12px;
}

.comment-bar-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.comment-bar-row :deep(.van-field) {
  flex: 1;
  padding: 6px 10px;
  background: var(--color-canvas-soft);
  border-radius: 18px;
}
</style>
