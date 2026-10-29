import { createApp } from 'vue';
import { createPinia } from 'pinia';
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate';
import { VueQueryPlugin } from '@tanstack/vue-query';
import App from './App.vue';
import router from './router';
import { initTheme } from './utils/theme';
import './assets/main.css';
// Import Vant components correctly (unplugin-vue-components takes care of auto-import, 
// but we might need some global styles if not using the resolver correctly or for some specific components)
import 'vant/lib/index.css';

initTheme();

const app = createApp(App);
const pinia = createPinia();
pinia.use(piniaPluginPersistedstate);

app.use(pinia);
app.use(router);
// 业务失败（HTTP 200 + success:false，如「订单不存在」）是确定性结果，
// vue-query 默认 retry:3 会把它当瞬时故障重试，实测放大成 4 次相同 GET。
// 网络层故障（无响应）的重试由 api/client.ts 的幂等方法重试负责，此处只保留一次兜底。
app.use(VueQueryPlugin, {
  queryClientConfig: {
    defaultOptions: {
      queries: {
        retry: (failureCount: number, error: unknown) =>
          failureCount < 1 && (error as { status?: number })?.status === undefined,
      },
    },
  },
});

app.mount('#app');
