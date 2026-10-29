import fs from 'node:fs';
import { request, type APIRequestContext } from '@playwright/test';
import { E2E_API_BASE, STORAGE_STATE } from './env';

/** 从 globalSetup 落盘的 storageState 中读取登录 token */
export function readToken(): string {
  const raw = JSON.parse(fs.readFileSync(STORAGE_STATE, 'utf-8')) as {
    origins?: Array<{ localStorage?: Array<{ name: string; value: string }> }>;
  };
  const entry = raw.origins?.[0]?.localStorage?.find((i) => i.name === 'authorization');
  if (!entry?.value) {
    throw new Error('storageState 中缺少 authorization，请检查 e2e/global-setup.ts');
  }
  return entry.value;
}

/** 带登录态的 API 上下文：用于测试造数前置与收尾清理 */
export async function apiContext(): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: readToken() },
  });
}

/** 生成短且唯一的后缀，避免用例之间互相干扰 */
export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;
}

/** 弹出层确认按钮（Vant 二次确认弹窗） */
export const CONFIRM_BUTTON = '确认';
