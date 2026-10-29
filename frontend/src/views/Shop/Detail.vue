<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMutation, useQuery } from '@tanstack/vue-query';
import { showDialog, showToast } from 'vant';
import { useUserStore } from '@/store/user';
import { BrowseTargetType, ErrorCode, Voucher } from '@/api/types';
import { shopService } from '@/api/shop';
import { voucherService } from '@/api/voucher';
import { blogService } from '@/api/blog';
import { historyService } from '@/api/history';
import { parseImageList } from '@/utils/images';
import VoucherCard from '@/components/VoucherCard.vue';
import BlogCard from '@/components/business/BlogCard.vue';
import ShopSummary from './components/ShopSummary.vue';
import ShopComments from './components/ShopComments.vue';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const shopId = route.params.id as string;

const { data: shop, isLoading: shopLoading } = useQuery({
  queryKey: ['shop', shopId],
  queryFn: () => shopService.queryById(shopId).then((res) => res.data.data),
});

const { data: vouchers, isLoading: voucherLoading } = useQuery({
  queryKey: ['vouchers', shopId],
  queryFn: () => voucherService.queryVoucherOfShop(shopId).then((res) => res.data.data || []),
});

const { data: blogs, isLoading: blogLoading } = useQuery({
  queryKey: ['blogsByShop', shopId],
  queryFn: () => blogService.getBlogsByShop(shopId).then((res) => res.data.data || []),
  enabled: computed(() => Boolean(userStore.token)),
});

const images = computed(() => parseImageList(shop.value?.images));
const scoreValue = computed(() => Number(((shop.value?.score || 0) / 10).toFixed(1)));

const handleSeckillError = (err: any) => {
  const errorCode = err?.errorCode || '';
  if (errorCode === ErrorCode.ORDER_CONFLICT) {
    const pendingOrderId = err?.data?.orderId;
    if (pendingOrderId) {
      router.push(`/order/status/${pendingOrderId}?shopId=${shopId}`).catch(() => showToast('页面加载失败，请刷新重试'));
    } else {
      showToast('排队人数较多，请稍候...');
    }
    return;
  }
  if (errorCode === ErrorCode.STOCK_NOT_ENOUGH) {
    showDialog({
      title: '抢购结束',
      message: '抱歉，该优惠券已被抢购一空，看看其他商家的优惠吧~',
      theme: 'round-button',
      confirmButtonText: '回到首页',
      showCancelButton: true,
      cancelButtonText: '返回',
      closeOnClickOverlay: true,
    })
      .then(() => router.push('/').catch(() => showToast('页面加载失败，请刷新重试')))
      .catch(() => {});
    return;
  }
  if (errorCode === ErrorCode.ORDER_DUPLICATE) {
    showDialog({
      title: '已下单',
      message: '该优惠券你已下单，可前往“我的优惠券”查看处理结果。',
      theme: 'round-button',
      confirmButtonText: '查看我的优惠券',
      showCancelButton: true,
      cancelButtonText: '返回',
      closeOnClickOverlay: true,
    })
      .then(() => router.push('/my-vouchers').catch(() => showToast('页面加载失败，请刷新重试')))
      .catch(() => {});
    return;
  }
  if (errorCode === ErrorCode.SECKILL_NOT_STARTED) {
    showToast('秒杀活动尚未开始，请耐心等待~');
    return;
  }
  if (errorCode === ErrorCode.SECKILL_ENDED) {
    showToast('秒杀活动已结束，看看其他优惠券吧~');
    return;
  }
  if (errorCode === ErrorCode.VOUCHER_NOT_FOUND) {
    showToast('优惠券不存在或已下架');
    return;
  }
  showToast(err?.errorMsg || '操作失败，请重试');
};

const voucherMutation = useMutation({
  mutationFn: (v: Voucher) => (v.type === 1 ? voucherService.seckill(v.id) : voucherService.purchase(v.id)),
  onMutate: () => {
    buying.value = true;
  },
  onSettled: () => {
    buying.value = false;
  },
  onSuccess: (res, v) => {
    if (!res.data.success) return;
    const orderId = res.data.data?.orderId;
    if (v.type === 1) {
      if (orderId) {
        router.push(`/order/status/${orderId}?shopId=${shopId}`).catch(() => showToast('页面加载失败，请刷新重试'));
      } else {
        showToast('订单处理中，请稍后在订单中查看');
      }
      return;
    }

    showDialog({
      title: '领取成功',
      message: '优惠券已加入你的账户，请在我的优惠券中完成支付。',
      confirmButtonText: '去支付',
      cancelButtonText: '继续逛逛',
      showCancelButton: true,
      closeOnClickOverlay: true,
      theme: 'round-button',
    })
      .then(() => router.push('/my-vouchers').catch(() => showToast('页面加载失败，请刷新重试')))
      .catch(() => {});
  },
  onError: handleSeckillError,
});

const activeVoucherId = computed(() => voucherMutation.variables.value?.id);
const goBack = () => router.back();
const goBlogDetail = (id: number | string) => router.push(`/blog/${id}`);
const goLogin = () =>
  router.push({ path: '/login', query: { redirect: route.fullPath } });
const goWriteBlog = () => {
  if (!userStore.token) {
    goLogin();
    return;
  }
  router.push(`/publish?shopId=${shopId}`);
};

// 防重入：任一抢购/领取请求处理中时，拦截后续点击
const buying = ref(false);
const handleBuy = (v: Voucher) => {
  if (buying.value) {
    showToast('正在处理中，请稍候...');
    return;
  }
  voucherMutation.mutate(v);
};

// 记录浏览历史：仅登录用户上报，失败静默（公开页不因未登录/上报失败而中断）
onMounted(() => {
  if (!userStore.token) return;
  historyService
    .record({ targetType: BrowseTargetType.SHOP, targetId: shopId })
    .catch(() => {});
});
</script>

<template>
  <div class="shop-detail-page min-h-screen pb-24">
    <van-nav-bar fixed placeholder left-arrow :title="shop?.name || '商铺详情'" @click-left="goBack" />

    <div v-if="shopLoading" class="p-4">
      <van-skeleton title avatar :row="6" />
    </div>

    <template v-else-if="shop">
      <ShopSummary :shop="shop" :images="images" :score-value="scoreValue" />

      <section class="voucher-section">
        <div class="section-title">
          <h2>特惠代金券</h2>
          <span>{{ vouchers?.length || 0 }} 张可领</span>
        </div>

        <van-skeleton v-if="voucherLoading" title :row="3" />
        <div v-else-if="!vouchers?.length" class="soft-empty">当前商户暂无优惠券</div>

        <div v-else class="voucher-list">
          <VoucherCard
            v-for="v in vouchers"
            :key="v.id"
            :voucher="v"
            :loading="voucherMutation.isPending.value && activeVoucherId === v.id"
            @buy="handleBuy"
          />
        </div>
      </section>

      <section class="blog-section">
        <div class="section-title">
          <h2>探店笔记</h2>
          <button type="button" class="write-btn" @click="goWriteBlog">
            <van-icon name="edit" size="12" /> 写点评
          </button>
        </div>

        <div v-if="!userStore.token" class="soft-empty clickable" @click="goLogin">
          登录后查看探店笔记
        </div>
        <van-skeleton v-else-if="blogLoading" title :row="2" />
        <div v-else-if="!blogs?.length" class="soft-empty">还没有探店笔记，快来写第一篇～</div>
        <div v-else class="blog-list">
          <BlogCard v-for="b in blogs" :key="b.id" :blog="b" @select="goBlogDetail" />
        </div>
      </section>

      <ShopComments :shop-id="shopId" />
    </template>

    <div v-else class="empty-state">
      <van-icon name="shop-o" size="52" />
      <p>商铺不存在或已下架</p>
      <van-button round type="primary" size="small" @click="router.replace('/')">返回首页</van-button>
    </div>
  </div>
</template>

<style scoped>
.shop-detail-page {
  background: var(--c-page-bg);
}

.voucher-section,
.blog-section {
  margin: 10px 12px 0;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: var(--c-card-bg);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.blog-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.write-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 5px 12px;
  border: 1px solid var(--color-primary);
  border-radius: 999px;
  background: transparent;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title h2 {
  margin: 0;
  color: var(--c-text-1);
  font-size: 17px;
  font-weight: 900;
}

.section-title span {
  color: var(--c-text-3);
  font-size: 12px;
}

.soft-empty,
.empty-state {
  color: var(--c-text-3);
  text-align: center;
  font-size: 13px;
}

.soft-empty {
  padding: 28px 0;
}

.soft-empty.clickable {
  color: var(--color-primary);
  cursor: pointer;
}

.empty-state {
  padding: 90px 20px;
}

.empty-state p {
  margin: 10px 0 18px;
}
</style>
