<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { showDialog } from 'vant';
import { orderService } from '@/api/orderService';

const route = useRoute();
const router = useRouter();
const orderId = route.params.orderId as string;

// Polling with Vue Query
const { data, isLoading } = useQuery({
  queryKey: ['orderStatus', orderId],
  queryFn: async () => {
    const res = await orderService.queryOrderStatus(orderId);
    return res.data.data;
  },
  refetchInterval: (query) => {
    const status = query.state.data;
    if (status === -1) return 2000;
    return false;
  },
  // Ensure we don't refetch on window focus while polling
  refetchOnWindowFocus: false,
});

const startedAt = ref(Date.now());

// 兜底：排队超过 30s 仍未出结果（-1），视为失败，避免无限转圈
const stuck = computed(
  () => data.value === -1 && Date.now() - startedAt.value > 30000
);

const handleSuccess = () => {
  router.replace(`/order/detail/${orderId}`);
};

const backToShop = () => {
  const shopId = route.query.shopId as string | undefined;
  router.replace(shopId ? `/shop/${shopId}` : '/');
};

const handleFailure = () => {
  showDialog({
    title: '抢购失败',
    message: '哎呀，手慢了，优惠券已经抢光啦！',
    theme: 'round-button',
    confirmButtonText: '回到首页',
    showCancelButton: true,
    cancelButtonText: '返回',
    closeOnClickOverlay: true,
  })
    .then(() => {
      router.replace('/');
    })
    .catch(() => {});
};
</script>

<template>
  <div class="queue-page min-h-screen bg-gray-50 flex flex-col items-center justify-center p-8">
    <div v-if="(data === -1 || isLoading) && !stuck" class="text-center">
      <van-loading size="48px" type="spinner" color="#f72d4e" vertical>
        正在拼命排队中...
      </van-loading>
      <p class="text-gray-400 mt-6 text-sm">
        系统正在为你处理抢购申请，请耐心等待 <br/>
        当前人数较多，请勿离开页面
      </p>
    </div>

    <div v-else-if="data === 1 || data === 2 || data === 3" class="text-center fade-in">
      <van-icon name="checked" size="64px" color="#07c160" />
      <h2 class="text-2xl font-bold mt-4 text-gray-800">抢购成功！</h2>
      <p class="text-gray-500 mt-2 mb-10">
        {{ data === 1 ? '订单已创建，请尽快完成支付' : '恭喜你，抢到了心仪的优惠券' }}
      </p>
      <div class="grid grid-cols-2 gap-3 w-full max-w-xs">
        <van-button block round plain type="primary" @click="backToShop">
          返回店铺
        </van-button>
        <van-button block round type="primary" @click="handleSuccess">
          {{ data === 1 ? '去支付' : '查看订单详情' }}
        </van-button>
      </div>
    </div>

    <div v-else-if="data === 4 || data === 5 || data === 6" class="text-center fade-in">
      <van-icon name="clear" size="64px" color="#f44" />
      <h2 class="text-2xl font-bold mt-4 text-gray-800">抢购失败</h2>
      <p class="text-gray-500 mt-2 mb-10">
        {{ data === 4 ? '抱歉，库存已售罄或订单已超时' : '订单已取消或退款中，请查看详情' }}
      </p>
      <van-button block round plain type="primary" @click="handleFailure">
        回到首页
      </van-button>
    </div>

    <div v-else-if="stuck" class="text-center fade-in">
      <van-icon name="clear" size="64px" color="#f44" />
      <h2 class="text-2xl font-bold mt-4 text-gray-800">抢购失败</h2>
      <p class="text-gray-500 mt-2 mb-10">订单处理超时，请稍后查看订单详情</p>
      <van-button block round plain type="primary" @click="handleFailure">
        回到首页
      </van-button>
    </div>

    <div v-else class="text-center">
      <p class="text-gray-400">正在查询订单状态...</p>
    </div>
  </div>
</template>

<style scoped>
.fade-in {
  animation: fadeIn 0.5s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
