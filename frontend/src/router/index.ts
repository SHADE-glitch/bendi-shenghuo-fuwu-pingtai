import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { showToast } from 'vant';
import { useUserStore } from '@/store/user';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/views/Layout/index.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/Home/index.vue'),
      },
      {
        path: 'map',
        name: 'Map',
        component: () => import('@/views/Map/index.vue'),
      },
      {
        path: 'my-vouchers',
        name: 'MyVouchersTab',
        component: () => import('@/views/Voucher/MyList.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'message',
        name: 'Message',
        component: () => import('@/views/Message/index.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'mine',
        name: 'Mine',
        component: () => import('@/views/Mine/index.vue'),
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login/index.vue'),
  },
  {
    path: '/scan',
    name: 'Scan',
    component: () => import('@/views/Scan/index.vue'),
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/Settings/index.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/info',
    name: 'UserInfo',
    component: () => import('@/views/Mine/Info.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/publish',
    name: 'Publish',
    component: () => import('@/views/Blog/Publish.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/blog/:id',
    name: 'BlogDetail',
    component: () => import('@/views/Blog/Detail.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/shop/:id',
    name: 'ShopDetail',
    component: () => import('@/views/Shop/Detail.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/order/status/:orderId',
    name: 'OrderStatus',
    component: () => import('@/views/Order/Queue.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/order/detail/:orderId',
    name: 'OrderDetail',
    component: () => import('@/views/Order/Detail.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/my-blogs',
    name: 'MyBlogs',
    component: () => import('@/views/Blog/MyList.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/follows',
    name: 'FollowList',
    component: () => import('@/views/Mine/FollowList.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/history',
    name: 'History',
    component: () => import('@/views/Mine/History.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/my-comments',
    name: 'MyComments',
    component: () => import('@/views/Mine/MyComments.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/feedback',
    name: 'Feedback',
    component: () => import('@/views/Mine/Feedback.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/about',
    name: 'About',
    component: () => import('@/views/Mine/About.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/merchant/orders',
    name: 'MerchantOrders',
    component: () => import('@/views/Merchant/OrderList.vue'),
    meta: { requiresAuth: true, requiresMerchant: true },
  },
  {
    path: '/merchant/dashboard',
    name: 'MerchantDashboard',
    component: () => import('@/views/Merchant/Dashboard.vue'),
    meta: { requiresAuth: true, requiresMerchant: true },
  },
  {
    path: '/merchant/shops',
    name: 'MerchantShops',
    component: () => import('@/views/Merchant/ShopList.vue'),
    meta: { requiresAuth: true, requiresMerchant: true },
  },
  {
    path: '/merchant/vouchers',
    name: 'MerchantVouchers',
    component: () => import('@/views/Merchant/VoucherList.vue'),
    meta: { requiresAuth: true, requiresMerchant: true },
  },
  {
    path: '/user/:id',
    name: 'UserProfile',
    component: () => import('@/views/User/Profile.vue'),
    meta: { requiresAuth: false },
  },
  // catch-all 必须排在最后：此前无 404 兜底，未知路径会渲染空白 router-view
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { requiresAuth: false },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// Global Navigation Guard
router.beforeEach((to) => {
  const userStore = useUserStore();
  if (to.meta.requiresAuth && !userStore.token) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    };
  }

  // 商家页守卫：仅做 UX 收敛，真正的权限由后端 assertMerchant() 强制。
  // role 未知（user 尚未加载 / 历史持久化数据缺 role）时放行，避免误拦真实商家；
  // 已知为非商家（role === 0）时重定向回「我的」。
  if (to.meta.requiresMerchant) {
    const role = userStore.user?.role;
    if (typeof role === 'number' && role !== 1) {
      showToast('仅商家可访问该页面');
      return { path: '/mine' };
    }
  }

  if (to.path === '/login' && userStore.token) {
    const redirect = typeof to.query.redirect === 'string' ? to.query.redirect : '/';
    return redirect;
  }
});

router.onError((error, to) => {
  console.error('[router] navigation error ->', to.fullPath, error);
  showToast('页面加载失败，请刷新重试');
});

export default router;
