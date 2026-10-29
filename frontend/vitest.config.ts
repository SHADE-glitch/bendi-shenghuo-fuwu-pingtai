import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import path from 'path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    include: ['tests/**/*.spec.ts'],
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    // 覆盖率：只出报告（coverage/ 目录 + 终端摘要），不设 thresholds ——
    // 覆盖率不达标不会让 `npm run test:coverage` 失败。
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html', 'lcov'],
      reportsDirectory: './coverage',
    },
  },
});
