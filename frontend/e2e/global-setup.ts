import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { request } from '@playwright/test';
import { E2E_API_BASE, E2E_BASE_ORIGIN, E2E_PHONE, LOGIN_CODE, REDIS_AUTH_ARGS, REDIS_CONTAINER, STORAGE_STATE } from './helpers/env';

/**
 * 真实链路 e2e 的全局前置：
 * 1. 校验后端健康（不健康则直接失败，避免把环境问题误判为用例问题）
 * 2. 向 Redis 预置登录验证码（mock 开关默认关闭，直接写 Redis 是唯一可复现路径）
 * 3. 调用真实 `/users/login` 拿 token，落盘为 storageState，供各用例复用
 */
export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({ baseURL: E2E_API_BASE });

  const health = await api.get('/actuator/health');
  if (!health.ok()) {
    throw new Error(`后端健康检查失败：${E2E_API_BASE}/actuator/health -> ${health.status()}`);
  }

  try {
    execFileSync('docker', [
      'exec',
      REDIS_CONTAINER,
      'redis-cli',
      ...REDIS_AUTH_ARGS,
      'set',
      `login:code:${E2E_PHONE}`,
      LOGIN_CODE,
      'EX',
      '1800',
    ]);
  } catch (err) {
    throw new Error(
      `预置验证码失败，请确认容器 ${REDIS_CONTAINER} 正在运行（可用 E2E_REDIS_CONTAINER 覆盖）：${String(err)}`
    );
  }

  const loginRes = await api.post('/users/login', {
    data: { phone: E2E_PHONE, code: LOGIN_CODE },
  });
  const body = (await loginRes.json()) as { success?: boolean; data?: { token?: string } };
  const token = body?.data?.token;
  if (!body?.success || !token) {
    throw new Error(`登录失败，无法生成 e2e 登录态：${JSON.stringify(body)}`);
  }

  fs.mkdirSync(path.dirname(STORAGE_STATE), { recursive: true });
  fs.writeFileSync(
    STORAGE_STATE,
    JSON.stringify(
      {
        cookies: [],
        origins: [
          {
            origin: E2E_BASE_ORIGIN,
            localStorage: [
              { name: 'authorization', value: token },
              { name: 'token', value: token },
            ],
          },
        ],
      },
      null,
      2
    )
  );

  await api.dispose();
}
