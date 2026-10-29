import { defineConfig, devices } from '@playwright/test';

/**
 * 端到端测试配置
 *
 * 三个 project：
 * - `mock`    ：网络全量 mock，只验证前端自身的渲染与交互（不依赖后端）
 * - `real`    ：直连真实后端，覆盖登录态下的完整业务流（依赖 docker compose 中的 backend/mysql/redis）
 * - `merchant`：Phase 4.5 商家订单真实链路。登录态由用例自行注入（role=1 商家 + role=0 消费者
 *               多账号切换，无法复用单一 storageState），因此不设 storageState。
 *
 * 真实链路需要登录态：`e2e/global-setup.ts` 会向 Redis 预置验证码、
 * 走真实 `/users/login` 拿 token，并把 token 写入 storageState 供各用例复用。
 */
const AUTH_STATE = 'test-results/e2e-auth/user.json';

export default defineConfig({
  testDir: './e2e/specs',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 12_000 },
  globalSetup: './e2e/global-setup.ts',
  reporter: [['list'], ['json', { outputFile: 'test-results/e2e-report.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    // 复用系统 Chrome，避免额外下载浏览器；如需自带 Chromium 可设 E2E_CHANNEL=chromium
    channel: (process.env.E2E_CHANNEL as 'chrome' | 'chromium' | 'msedge') ?? 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
  },
  projects: [
    {
      name: 'mock',
      testMatch: /phase1-journey\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'real',
      testMatch: /journey-.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], storageState: AUTH_STATE },
    },
    {
      name: 'merchant',
      testMatch: /merchant-.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
