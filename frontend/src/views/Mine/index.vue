<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { useUserStore } from '@/store/user';
import { userService } from '@/api/user';
import { orderService } from '@/api/orderService';
import MineProfileHeader from './components/MineProfileHeader.vue';
import MineStatsPanel from './components/MineStatsPanel.vue';

const router = useRouter();
const userStore = useUserStore();

const signCount = ref(0);
const isSigned = ref(false);
const voucherCount = ref(0);
const loading = ref(true);

const displayName = computed(() => userStore.user?.nickName || '未登录用户');
const userId = computed(() => userStore.user?.id || '----');
/** 商家入口仅对 role === 1 的用户展示（后端权威校验，前端只做显隐） */
const isMerchant = computed(() => userStore.user?.role === 1);

const refreshUserInfo = async () => {
  const res = await userService.me();
  if (res.data.success && res.data.data) {
    userStore.setUser(res.data.data);
  }
};

const fetchDashboard = async () => {
  const [signRes, todayRes, voucherRes] = await Promise.allSettled([
    userService.signCount(),
    userService.signToday(),
    orderService.queryMyVouchers('unused', 1, 1),
  ]);

  if (signRes.status === 'fulfilled' && signRes.value.data.success) {
    signCount.value = signRes.value.data.data || 0;
  }
  if (todayRes.status === 'fulfilled' && todayRes.value.data.success) {
    isSigned.value = !!todayRes.value.data.data;
  }
  if (voucherRes.status === 'fulfilled' && voucherRes.value.data.success) {
    voucherCount.value = Number(voucherRes.value.data.total || voucherRes.value.data.data?.length || 0);
  }
};

const handleSign = async () => {
  if (isSigned.value) return;
  try {
    await userService.sign();
    isSigned.value = true;
    const res = await userService.signCount();
    signCount.value = res.data.data || signCount.value;
    showToast('签到成功');
  } catch {}
};

onMounted(async () => {
  try {
    await refreshUserInfo();
    await fetchDashboard();
  } finally {
    loading.value = false;
  }
});

const handleLogout = () => {
  showConfirmDialog({
    title: '退出登录',
    message: '确定要退出当前账号吗？',
  })
    .then(async () => {
      try {
        await userService.logout();
      } catch {}
      userStore.logout();
      router.replace('/login');
    })
    .catch(() => {});
};

const goToInfo = () => router.push('/info');
const goToMyVouchers = () => router.push('/my-vouchers');
const goToMyBlogs = () => router.push('/my-blogs');
const goToMessages = () => router.push('/message');
const goToHome = () => router.push('/');
const goToSettings = () => router.push('/settings');
const goToFollows = () => router.push('/follows');
const goToMyComments = () => router.push('/my-comments');
const goToHistory = () => router.push('/history');
const goToFeedback = () => router.push('/feedback');
const goToMerchantShops = () => router.push('/merchant/shops');
const goToMerchantOrders = () => router.push('/merchant/orders');
const goToMerchantVouchers = () => router.push('/merchant/vouchers');
const goToMerchantDashboard = () => router.push('/merchant/dashboard');
</script>

<template>
  <div class="mine-page min-h-screen">
    <!-- Profile Header -->
    <div class="profile-header gradient-mesh">
      <MineProfileHeader
        :user="userStore.user"
        :display-name="displayName"
        :user-id="userId"
        :is-signed="isSigned"
        @click-info="goToInfo"
        @sign="handleSign"
        @click-vouchers="goToMyVouchers"
        @click-settings="goToSettings"
      />
    </div>

    <!-- Stats Panel -->
    <div class="stats-container">
      <MineStatsPanel :loading="loading" :sign-count="signCount" :voucher-count="voucherCount" />
    </div>

    <!-- Menu Sections -->
    <section class="menu-section card-stripe-light">
      <van-cell
        v-if="isMerchant"
        title="经营概览"
        label="查看经营数据与最近动态"
        icon="chart-trending-o"
        is-link
        size="large"
        @click="goToMerchantDashboard"
      />
      <van-cell
        v-if="isMerchant"
        title="商家店铺"
        label="查看并维护我的店铺信息"
        icon="shop-collect-o"
        is-link
        size="large"
        @click="goToMerchantShops"
      />
      <van-cell
        v-if="isMerchant"
        title="商家优惠券"
        label="新增与维护我的优惠券"
        icon="coupon-o"
        is-link
        size="large"
        @click="goToMerchantVouchers"
      />
      <van-cell
        v-if="isMerchant"
        title="商家管理"
        label="查看并核销本店订单"
        icon="manager-o"
        is-link
        size="large"
        @click="goToMerchantOrders"
      />
      <van-cell title="个人资料" label="昵称、城市、生日、简介" icon="contact-o" is-link size="large" @click="goToInfo" />
      <van-cell title="我的优惠券" label="查看已领取和订单状态" icon="coupon-o" is-link size="large" @click="goToMyVouchers" />
      <van-cell title="我的笔记" label="管理你发布的探店笔记" icon="notes-o" is-link size="large" @click="goToMyBlogs" />
      <van-cell title="消息中心" label="点赞、关注和评论通知" icon="chat-o" is-link size="large" @click="goToMessages" />
      <van-cell title="继续找店" label="回到首页浏览真实后端店铺数据" icon="shop-o" is-link size="large" @click="goToHome" />
    </section>

    <section class="menu-section card-stripe-light">
      <van-cell title="关注 / 粉丝" label="查看我关注的人和我的粉丝" icon="friends-o" is-link size="large" @click="goToFollows" />
      <van-cell title="我的评价" label="管理店铺评价与笔记评论" icon="comment-o" is-link size="large" @click="goToMyComments" />
      <van-cell title="浏览历史" label="最近看过的店铺和笔记" icon="clock-o" is-link size="large" @click="goToHistory" />
      <van-cell title="帮助与反馈" label="常见问题与意见反馈" icon="service-o" is-link size="large" @click="goToFeedback" />
    </section>

    <!-- Logout Button -->
    <div class="logout-wrap">
      <Button
        variant="secondary"
        size="lg"
        block
        @click="handleLogout"
      >
        退出登录
      </Button>
    </div>
  </div>
</template>

<style scoped>
.mine-page {
  background: var(--color-canvas-soft);
}

.profile-header {
  position: relative;
  height: 180px;
  overflow: hidden;
}

.stats-container {
  margin-top: -32px;
  padding: 0 16px;
  position: relative;
  z-index: 10;
}

.menu-section {
  margin: 0 12px 12px;
  border: 1px solid var(--color-hairline);
  border-radius: 12px;
  background: var(--color-canvas);
  box-shadow: rgba(0, 55, 112, 0.08) 0 1px 3px;
  overflow: hidden;
}

.muted {
  opacity: 0.7;
}

.logout-wrap {
  margin: 24px 16px;
}

/* Mobile optimization */
@media (max-width: 768px) {
  .profile-header {
    height: 160px;
  }
  
  .stats-container {
    padding: 0 12px;
  }
  
  .menu-section {
    margin: 0 8px 8px;
  }
  
  .logout-wrap {
    margin: 16px 8px 24px;
  }
}
</style>
