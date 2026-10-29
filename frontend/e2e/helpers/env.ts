import path from 'node:path';

/** 被测前端地址 */
export const E2E_BASE_ORIGIN = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';

/** 后端直连地址（e2e 里用 API 做前置/清理，绕过 Vite 代理） */
export const E2E_API_BASE = process.env.E2E_API_BASE ?? 'http://127.0.0.1:8081';

/** 登录手机号（种子用户 1） */
export const E2E_PHONE = process.env.E2E_PHONE ?? '13686869696';

/** 与 backend/tests/api-crud-smoke.sh 保持一致的固定验证码 */
export const LOGIN_CODE = '123456';

/** Redis 容器名（用于预置验证码） */
export const REDIS_CONTAINER =
  process.env.E2E_REDIS_CONTAINER ?? 'Local-life-service-platform-redis';

/**
 * Redis 密码。docker-compose.yml 的 redis 启用了 requirepass（见其 `--requirepass`），
 * 所以 redis-cli 必须带 -a，否则会被 NOAUTH 拒绝。名字与语义同
 * backend/tests/api-crud-smoke.sh 的 REDIS_PASSWORD，两者在 CI 里由同一个 env 提供。
 */
export const REDIS_PASSWORD = process.env.REDIS_PASSWORD ?? '';

/** redis-cli 的认证参数：有密码时 `-a <pw> --no-auth-warning`，无密码时为空 */
export const REDIS_AUTH_ARGS: readonly string[] = REDIS_PASSWORD
  ? ['-a', REDIS_PASSWORD, '--no-auth-warning']
  : [];

/** 登录态落盘位置（test-results 已在 .gitignore 中） */
export const STORAGE_STATE = path.join('test-results', 'e2e-auth', 'user.json');

/** 所有 e2e 造数统一前缀，便于失败后批量清理 */
export const E2E_PREFIX = 'E2E-';
