<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { messageService } from '@/api/message';
import { MessageType, type MessageListDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';
import UserAvatar from '@/components/business/UserAvatar.vue';

const PAGE_SIZE = 10;
/** 0-未读 1-已读 2-全部（全部不传 isRead） */
const TAB_UNREAD = 0;
const TAB_READ = 1;

const router = useRouter();
const activeTab = ref(TAB_UNREAD);
const list = ref<MessageListDTO[]>([]);
const unreadCount = ref(0);
const loading = ref(false);
const finished = ref(false);
const current = ref(1);

/** 全部 Tab 不传 isRead，由后端返回全部消息 */
const isRead = computed<number | undefined>(() => {
  if (activeTab.value === TAB_UNREAD) return 0;
  if (activeTab.value === TAB_READ) return 1;
  return undefined;
});

const fetchUnread = async () => {
  try {
    const res = await messageService.getUnreadCount();
    unreadCount.value = res.data.data ?? 0;
  } catch {
    /* 未读数失败不影响列表 */
  }
};

const load = async (reset = false) => {
  if (loading.value) return;
  if (reset) {
    current.value = 1;
    list.value = [];
    finished.value = false;
  }
  loading.value = true;
  try {
    const res = await messageService.queryMessages({
      isRead: isRead.value,
      current: current.value,
      size: PAGE_SIZE,
    });
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

const typeIcon = (type: number) => {
  switch (type) {
    case MessageType.LIKE:
      return 'like-o';
    case MessageType.FOLLOW:
      return 'friends-o';
    case MessageType.COMMENT:
      return 'chat-o';
    default:
      return 'bell';
  }
};

const typeLabel = (type: number) => {
  switch (type) {
    case MessageType.LIKE:
      return '点赞';
    case MessageType.FOLLOW:
      return '关注';
    case MessageType.COMMENT:
      return '评论';
    default:
      return '通知';
  }
};

/** 点击消息：标记已读 + 跳转到关联内容 */
const onOpen = async (msg: MessageListDTO) => {
  if (msg.isRead === 0) {
    try {
      await messageService.markAsRead(msg.id);
      msg.isRead = 1;
      unreadCount.value = Math.max(0, unreadCount.value - 1);
      if (activeTab.value === TAB_UNREAD) {
        list.value = list.value.filter((m) => m.id !== msg.id);
      }
    } catch (err) {
      showToast((err as { errorMsg?: string })?.errorMsg || '操作失败');
    }
  }
  if (!msg.relatedId) return;
  if (msg.type === MessageType.FOLLOW) {
    router.push('/follows');
  } else {
    router.push(`/blog/${msg.relatedId}`);
  }
};

const goBack = () => router.back();
const goHome = () => router.replace('/');

// ---------- 全部已读 ----------
const markingAll = ref(false);
const markAllRead = async () => {
  if (markingAll.value || unreadCount.value === 0) return;
  markingAll.value = true;
  try {
    await messageService.readAll();
    showToast('已全部标记为已读');
    unreadCount.value = 0;
    await load(true);
  } catch (err) {
    showToast((err as { errorMsg?: string })?.errorMsg || '操作失败');
  } finally {
    markingAll.value = false;
  }
};

const emptyText = computed(() => {
  if (activeTab.value === TAB_UNREAD) return '暂无未读消息';
  if (activeTab.value === TAB_READ) return '暂无已读消息';
  return '暂无消息';
});

watch(activeTab, () => load(true));

onMounted(() => {
  load(true);
  fetchUnread();
});
</script>

<template>
  <div class="message-page min-h-screen pb-24">
    <van-nav-bar title="消息中心" left-arrow fixed placeholder @click-left="goBack">
      <template #right>
        <button
          type="button"
          class="read-all-btn"
          :class="{ disabled: unreadCount === 0 }"
          :disabled="markingAll || unreadCount === 0"
          @click="markAllRead"
        >
          {{ markingAll ? '处理中' : '全部已读' }}
        </button>
      </template>
    </van-nav-bar>

    <van-tabs v-model:active="activeTab" color="#533afd" title-active-color="#0d253d">
      <van-tab :title="`未读${unreadCount ? ` (${unreadCount})` : ''}`" />
      <van-tab title="已读" />
      <van-tab title="全部" />
    </van-tabs>

    <div class="list-wrap">
      <van-skeleton v-if="loading && !list.length" title :row="3" />

      <div v-else-if="list.length === 0" class="empty-state">
        <van-icon name="bell" size="52" color="var(--color-ink-mute)" />
        <p>{{ emptyText }}</p>
        <van-button round type="primary" size="small" @click="goHome">去逛逛</van-button>
      </div>

      <template v-else>
        <div class="message-list">
          <div
            v-for="msg in list"
            :key="msg.id"
            class="message-item"
            :class="{ unread: msg.isRead === 0 }"
            @click="onOpen(msg)"
          >
            <UserAvatar v-if="msg.sender" :src="msg.sender.icon" :size="40" />
            <div v-else class="msg-icon" :class="`type-${msg.type}`">
              <van-icon :name="typeIcon(msg.type)" size="20" />
            </div>
            <div class="msg-copy">
              <div class="msg-title">
                <span class="msg-type stripe-caption">
                  {{ msg.sender?.nickName || typeLabel(msg.type) }}
                </span>
                <span v-if="msg.isRead === 0" class="unread-dot" />
              </div>
              <p class="msg-content">{{ msg.content }}</p>
              <span class="msg-time stripe-caption">{{ timeAgo(msg.createTime) }}</span>
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
.message-page {
  background: var(--color-canvas-soft);
}

.list-wrap {
  padding: 12px;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.message-item {
  display: flex;
  gap: 12px;
  padding: 14px;
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  cursor: pointer;
}

.message-item.unread {
  border-left: 3px solid var(--color-primary);
}

.msg-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-canvas-soft);
  color: var(--color-primary);
}

.msg-icon.type-2 {
  color: var(--color-lemon);
}

.msg-icon.type-3 {
  color: var(--color-ruby);
}

.msg-copy {
  min-width: 0;
  flex: 1;
}

.msg-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.msg-type {
  color: var(--color-ink);
  font-weight: 600;
}

.unread-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-ruby);
}

.msg-content {
  margin: 6px 0 4px;
  color: var(--color-ink-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.msg-time {
  color: var(--color-ink-mute);
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

.read-all-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-primary);
  font-size: 14px;
  cursor: pointer;
}

.read-all-btn.disabled {
  color: var(--color-ink-mute);
  cursor: default;
}
</style>
