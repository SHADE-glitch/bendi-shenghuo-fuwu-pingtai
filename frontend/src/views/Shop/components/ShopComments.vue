<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { useUserStore } from '@/store/user';
import { shopCommentService } from '@/api/shopComment';
import { getErrorMessage } from '@/api/client';
import type { ShopCommentDTO, ShopCommentSummaryDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';
import { usePagination } from '@/composables/usePagination';
import UserAvatar from '@/components/business/UserAvatar.vue';
import StarRating from '@/components/business/StarRating.vue';

const props = defineProps<{
  shopId: number | string;
}>();

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const summary = ref<ShopCommentSummaryDTO | null>(null);
const error = ref<string | null>(null);

const { list, loading, finished, load, loadMore } = usePagination<ShopCommentDTO>(
  async ({ current, size }) => {
    error.value = null;
    try {
      const res = await shopCommentService.listByShop(props.shopId, current, size);
      return { list: res.data.data || [], total: Number(res.data.total || 0) };
    } catch (err) {
      error.value = getErrorMessage(err);
      return { list: [], total: 0 };
    }
  }
);

const avgRating = computed(() => {
  const value = summary.value?.avgRating;
  return value == null ? 0 : Number(value.toFixed(1));
});

const fetchSummary = async () => {
  try {
    const res = await shopCommentService.summary(props.shopId);
    summary.value = res.data.data || null;
  } catch {
    /* 汇总失败不影响列表展示 */
  }
};

const refresh = () => {
  load(true);
  fetchSummary();
};

// ---------- 写 / 编辑评价 ----------
const showEditor = ref(false);
const submitting = ref(false);
const form = ref({ rating: 5, content: '' });
/** 非空表示当前处于「编辑已有评价」状态 */
const editingId = ref<string | null>(null);

const openEditor = () => {
  if (!userStore.token) {
    router.push({ path: '/login', query: { redirect: route.fullPath } });
    return;
  }
  editingId.value = null;
  form.value = { rating: 5, content: '' };
  showEditor.value = true;
};

const openEdit = (item: ShopCommentDTO) => {
  editingId.value = String(item.id);
  form.value = { rating: item.rating, content: item.content };
  showEditor.value = true;
};

const submitComment = async () => {
  const content = form.value.content.trim();
  if (!content) {
    showToast('请填写评价内容');
    return;
  }
  if (content.length > 1024) {
    showToast('评价内容最长1024个字符');
    return;
  }
  submitting.value = true;
  try {
    if (editingId.value) {
      await shopCommentService.update(editingId.value, { content, rating: form.value.rating });
      showToast('评价已更新');
    } else {
      await shopCommentService.create({
        shopId: props.shopId,
        content,
        rating: form.value.rating,
      });
      showToast('评价发布成功');
    }
    showEditor.value = false;
    editingId.value = null;
    refresh();
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    submitting.value = false;
  }
};

const isMine = (item: ShopCommentDTO) =>
  userStore.user?.id != null && String(item.userId) === String(userStore.user.id);

const removeComment = (item: ShopCommentDTO) => {
  showConfirmDialog({ title: '删除评价', message: '确定删除这条评价吗？' })
    .then(async () => {
      try {
        await shopCommentService.remove(item.id);
        showToast('已删除');
        refresh();
      } catch (err) {
        showToast(getErrorMessage(err));
      }
    })
    .catch(() => {});
};

onMounted(refresh);
</script>

<template>
  <section class="comment-section">
    <div class="section-title">
      <div class="title-copy">
        <h2>用户评价</h2>
        <span v-if="Number(summary?.count) > 0" class="summary stripe-caption">
          <StarRating :model-value="avgRating" :size="13" />
          <em>{{ avgRating }}</em>
          <em>· {{ Number(summary?.count) }} 条评价</em>
        </span>
      </div>
      <van-button size="mini" round plain type="primary" @click="openEditor">写评价</van-button>
    </div>

    <van-skeleton v-if="loading && !list.length" :row="3" />

    <div v-else-if="error" class="soft-tip">
      <p>{{ error }}</p>
      <van-button size="mini" round type="primary" @click="refresh">重试</van-button>
    </div>

    <div v-else-if="!list.length" class="soft-tip">
      <van-icon name="comment-o" size="26" />
      <p>还没有评价，来写第一条吧～</p>
    </div>

    <template v-else>
      <div class="comment-list">
        <div v-for="item in list" :key="item.id" class="comment-item">
          <UserAvatar :src="item.icon" :size="36" />
          <div class="comment-body">
            <div class="comment-head">
              <span class="comment-name">{{ item.nickName || '匿名用户' }}</span>
              <span class="comment-time stripe-caption">{{ timeAgo(item.createTime) }}</span>
            </div>
            <StarRating :model-value="item.rating" :size="13" />
            <p class="comment-text">{{ item.content }}</p>
            <div v-if="isMine(item)" class="comment-actions">
              <button type="button" class="action-btn" @click="openEdit(item)">编辑</button>
              <button type="button" class="action-btn" @click="removeComment(item)">删除</button>
            </div>
          </div>
        </div>
      </div>

      <div class="load-more">
        <van-loading v-if="loading" size="16" color="var(--color-primary)" />
        <span v-else-if="finished" class="stripe-caption">没有更多了</span>
        <van-button v-else size="mini" round plain type="primary" @click="loadMore()">
          加载更多
        </van-button>
      </div>
    </template>

    <!-- 写评价弹窗 -->
    <van-popup v-model:show="showEditor" position="bottom" round :style="{ paddingBottom: '16px' }">
      <div class="editor">
        <h3 class="editor-title">{{ editingId ? '编辑评价' : '写评价' }}</h3>
        <div class="editor-rate">
          <span class="stripe-caption">评分</span>
          <StarRating v-model="form.rating" :readonly="false" :size="26" :allow-half="false" />
        </div>
        <van-field
          v-model="form.content"
          type="textarea"
          rows="4"
          autosize
          maxlength="1024"
          show-word-limit
          placeholder="分享你的消费体验，帮助更多人～"
        />
        <div class="editor-actions">
          <van-button round plain block @click="showEditor = false">取消</van-button>
          <van-button round type="primary" block :loading="submitting" @click="submitComment">
            {{ editingId ? '保存' : '发布' }}
          </van-button>
        </div>
      </div>
    </van-popup>
  </section>
</template>

<style scoped>
.comment-section {
  margin: 10px 12px 0;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.title-copy h2 {
  margin: 0;
  color: var(--c-text-1);
  font-size: 17px;
  font-weight: 900;
}

.summary {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  color: var(--c-text-3);
}

.summary em {
  font-style: normal;
}

.soft-tip {
  padding: 24px 0;
  text-align: center;
  color: var(--c-text-3);
  font-size: 13px;
}

.soft-tip p {
  margin: 8px 0 12px;
}

.comment-list {
  display: flex;
  flex-direction: column;
}

.comment-item {
  display: flex;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid var(--c-border);
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
  color: var(--c-text-1);
  font-size: 14px;
  font-weight: 600;
}

.comment-time {
  color: var(--c-text-3);
}

.comment-text {
  margin: 6px 0 0;
  color: var(--c-text-2);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.comment-actions {
  display: flex;
  gap: 14px;
  margin-top: 6px;
}

.action-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--c-text-3);
  font-size: 12px;
  cursor: pointer;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 14px 0 0;
  color: var(--c-text-3);
}

.editor {
  padding: 20px 16px 4px;
}

.editor-title {
  margin: 0 0 12px;
  color: var(--color-ink);
  font-size: 17px;
  font-weight: 700;
  text-align: center;
}

.editor-rate {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0 12px;
}

.editor-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
</style>
