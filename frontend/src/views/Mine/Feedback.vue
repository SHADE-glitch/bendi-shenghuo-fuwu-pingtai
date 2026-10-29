<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { feedbackService } from '@/api/feedback';
import { getErrorMessage } from '@/api/client';
import type { FeedbackDTO } from '@/api/types';
import { timeAgo } from '@/utils/format';

const router = useRouter();

const TYPE_OPTIONS = [
  { label: '功能异常', value: 1 },
  { label: '体验建议', value: 2 },
  { label: '其他', value: 0 },
];

const typeLabel = (type: number) => TYPE_OPTIONS.find((o) => o.value === type)?.label || '其他';

const form = ref({ type: 1, content: '', contact: '' });
const submitting = ref(false);
const myList = ref<FeedbackDTO[]>([]);
const listLoading = ref(false);

const canSubmit = computed(() => form.value.content.trim().length > 0);

const loadMine = async () => {
  listLoading.value = true;
  try {
    const res = await feedbackService.mine(1, 10);
    myList.value = res.data.data || [];
  } catch {
    /* 列表失败不影响提交 */
  } finally {
    listLoading.value = false;
  }
};

const onSubmit = async () => {
  const content = form.value.content.trim();
  if (!content) {
    showToast('请填写反馈内容');
    return;
  }
  if (content.length > 1024) {
    showToast('反馈内容最长1024个字符');
    return;
  }
  if (form.value.contact.length > 128) {
    showToast('联系方式最长128个字符');
    return;
  }
  submitting.value = true;
  try {
    await feedbackService.submit({
      content,
      type: form.value.type,
      contact: form.value.contact.trim() || undefined,
    });
    showToast('感谢反馈，我们会尽快处理');
    form.value = { type: 1, content: '', contact: '' };
    loadMine();
  } catch (err) {
    showToast(getErrorMessage(err));
  } finally {
    submitting.value = false;
  }
};

const removingId = ref<string | null>(null);

const onDelete = (item: FeedbackDTO) => {
  showConfirmDialog({ title: '删除反馈', message: '确定撤回这条反馈吗？' })
    .then(async () => {
      removingId.value = String(item.id);
      try {
        await feedbackService.remove(item.id);
        showToast('已删除');
        loadMine();
      } catch (err) {
        showToast(getErrorMessage(err));
      } finally {
        removingId.value = null;
      }
    })
    .catch(() => {});
};

onMounted(loadMine);
</script>

<template>
  <div class="feedback-page min-h-screen pb-16">
    <van-nav-bar title="帮助与反馈" left-arrow fixed placeholder @click-left="router.back()" />

    <div class="content">
      <h3 class="section-label">常见问题</h3>
      <van-cell-group inset>
        <van-cell title="如何领取优惠券？" label="进入商铺详情页即可领取或秒杀。" />
        <van-cell title="秒杀失败怎么办？" label="秒杀人数较多时请耐心等待队列处理。" />
        <van-cell title="笔记发布不成功？" label="请检查网络并确保上传了图片。" />
      </van-cell-group>

      <h3 class="section-label">意见反馈</h3>
      <van-cell-group inset>
        <van-field label="反馈类型">
          <template #input>
            <van-radio-group v-model="form.type" direction="horizontal">
              <van-radio v-for="opt in TYPE_OPTIONS" :key="opt.value" :name="opt.value">
                {{ opt.label }}
              </van-radio>
            </van-radio-group>
          </template>
        </van-field>
        <van-field
          v-model="form.content"
          type="textarea"
          rows="4"
          autosize
          maxlength="1024"
          show-word-limit
          placeholder="请详细描述您遇到的问题或建议..."
        />
        <van-field
          v-model="form.contact"
          label="联系方式"
          maxlength="128"
          placeholder="选填，便于我们联系你"
        />
      </van-cell-group>

      <div class="submit-wrap">
        <van-button block round type="primary" :loading="submitting" :disabled="!canSubmit" @click="onSubmit">
          提交反馈
        </van-button>
      </div>

      <h3 class="section-label">我的反馈</h3>
      <van-skeleton v-if="listLoading && !myList.length" :row="2" />
      <div v-else-if="!myList.length" class="soft-tip">还没有提交过反馈</div>
      <div v-else class="feedback-list">
        <div v-for="item in myList" :key="item.id" class="feedback-card card-stripe-light">
          <div class="card-head">
            <span class="type-tag">{{ typeLabel(item.type) }}</span>
            <span class="time stripe-caption">{{ timeAgo(item.createTime) }}</span>
          </div>
          <p class="feedback-text">{{ item.content }}</p>
          <div class="card-foot">
            <span class="status stripe-caption">
              {{ item.status === 1 ? '已处理' : '待处理' }}
              <template v-if="item.contact"> · {{ item.contact }}</template>
            </span>
            <button
              type="button"
              class="delete-btn"
              :disabled="removingId === String(item.id)"
              @click="onDelete(item)"
            >
              {{ removingId === String(item.id) ? '删除中' : '删除' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.feedback-page {
  background: var(--color-canvas-soft);
}

.content {
  padding: 12px 0 24px;
}

.section-label {
  margin: 16px 0 8px;
  padding: 0 20px;
  color: var(--color-ink);
  font-size: 15px;
  font-weight: 700;
}

.submit-wrap {
  margin: 18px 16px 0;
}

.soft-tip {
  padding: 24px 20px;
  text-align: center;
  color: var(--color-ink-mute);
  font-size: 13px;
}

.feedback-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0 16px;
}

.feedback-card {
  padding: 12px 14px;
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  background: var(--color-canvas);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.type-tag {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-canvas-soft);
  color: var(--color-primary);
  font-size: 12px;
}

.time {
  color: var(--color-ink-mute);
}

.feedback-text {
  margin: 8px 0 6px;
  color: var(--color-ink-secondary);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.status {
  color: var(--color-ink-mute);
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.delete-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-ink-mute);
  font-size: 12px;
  cursor: pointer;
}

.delete-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
