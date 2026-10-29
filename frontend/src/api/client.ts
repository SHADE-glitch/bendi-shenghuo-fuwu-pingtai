import axios, {
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
  AxiosResponse,
  AxiosError,
} from 'axios';
import { showToast } from 'vant';
import { Result, ErrorCode } from './types';
import { useUserStore } from '@/store/user';
import router from '@/router';

/** 归一化后的业务/网络错误对象，便于上层统一处理 */
export interface ApiError {
  success: false;
  errorCode: string | null;
  errorMsg: string | null;
  traceId: string | null;
  data?: unknown;
  status?: number;
}

/** 扩展请求配置：支持重试与 AbortController signal */
export interface RequestOptions extends AxiosRequestConfig {
  /** 网络层错误（无响应）时的最大重试次数，默认 0 */
  retries?: number;
  /** 首次重试的退避延迟（ms），之后按指数增长 */
  retryDelay?: number;
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  retries?: number;
  retryDelay?: number;
  __retryCount?: number;
}

const DEFAULT_RETRY_DELAY = 500;
const MAX_RETRY_DELAY = 5000;
/** 幂等方法（GET/HEAD/OPTIONS）网络错误时默认重试一次 */
const IDEMPOTENT_METHODS = new Set(['get', 'head', 'options']);

const client: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

let redirectingToLogin = false;

const redirectToLogin = () => {
  const current = router.currentRoute.value;
  if (current.path === '/login') return;
  if (redirectingToLogin) return;
  redirectingToLogin = true;
  const redirect = current.fullPath || '/';
  router.push({ path: '/login', query: { redirect } }).finally(() => {
    redirectingToLogin = false;
  });
};

const normalizeError = (error: AxiosError<Result>): ApiError => {
  const body = error.response?.data;
  return {
    success: false,
    errorCode: body?.errorCode ?? '',
    errorMsg: body?.errorMsg ?? null,
    traceId: body?.traceId ?? null,
    data: body?.data,
    status: error.response?.status,
  };
};

// Request Interceptor: Inject Token
client.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const userStore = useUserStore();
    if (userStore.token) {
      config.headers.authorization = userStore.token;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response Interceptor: Handle HTTP/Auth errors and pass business errors to callers
client.interceptors.response.use(
  (response: AxiosResponse<Result>) => {
    const { data } = response;
    const errorCode = data.errorCode || '';

    if (import.meta.env.DEV && data.traceId) {
      console.debug('[api traceId]', data.traceId);
    }

    // If business logic failed
    if (!data.success) {
      const normalized: ApiError = {
        success: false,
        errorCode,
        errorMsg: data.errorMsg,
        traceId: data.traceId,
        data: data.data,
        status: response.status,
      };
      if (errorCode === ErrorCode.AUTH_REQUIRED) {
        const userStore = useUserStore();
        userStore.logout();
        showToast('请先登录后操作');
        redirectToLogin();
      }
      return Promise.reject(normalized);
    }

    return response;
  },
  async (error: AxiosError<Result>) => {
    const config = error.config as RetriableConfig | undefined;
    const normalized = normalizeError(error);

    // 网络层错误（无响应）时，对允许重试的请求做指数退避重试
    const shouldRetry =
      !error.response &&
      config &&
      (config.retries ?? 0) > 0 &&
      (config.__retryCount ?? 0) < (config.retries ?? 0);

    if (shouldRetry && config) {
      config.__retryCount = (config.__retryCount ?? 0) + 1;
      const delay = Math.min(
        (config.retryDelay ?? DEFAULT_RETRY_DELAY) * 2 ** (config.__retryCount - 1),
        MAX_RETRY_DELAY
      );
      await new Promise((resolve) => setTimeout(resolve, delay));
      return client.request(config);
    }

    if (import.meta.env.DEV && normalized.traceId) {
      console.debug('[api traceId]', normalized.traceId);
    }

    if (error.response?.status === 401 || normalized.errorCode === ErrorCode.AUTH_REQUIRED) {
      const userStore = useUserStore();
      userStore.logout();
      redirectToLogin();
      showToast('身份认证失败，请重新登录');
    } else if (error.response?.status === 409 || normalized.errorCode === ErrorCode.IDEMPOTENCY_REPLAY) {
      showToast(normalized.errorMsg || '请求已提交，请勿重复操作');
    } else {
      showToast('网络有点不给力，请检查设置');
    }
    return Promise.reject(normalized);
  }
);

/**
 * 发起请求并直接返回业务数据 data（已解包）
 * 出错时 reject 归一化的 ApiError，可通过 getErrorMessage 提取提示文案。
 */
export function request<T = null>(options: RequestOptions): Promise<T> {
  const method = String(options.method || 'get').toLowerCase();
  const retries = options.retries ?? (IDEMPOTENT_METHODS.has(method) ? 1 : 0);
  const config: RequestOptions = { ...options, retries };
  return client.request<Result<T>>(config).then((res) => res.data.data);
}

/** 类型化请求便捷方法（返回已解包的 data） */
export const http = {
  get<T = null>(url: string, config?: RequestOptions) {
    return request<T>({ ...config, url, method: 'GET' });
  },
  post<T = null>(url: string, data?: unknown, config?: RequestOptions) {
    return request<T>({ ...config, url, method: 'POST', data });
  },
  put<T = null>(url: string, data?: unknown, config?: RequestOptions) {
    return request<T>({ ...config, url, method: 'PUT', data });
  },
  delete<T = null>(url: string, config?: RequestOptions) {
    return request<T>({ ...config, url, method: 'DELETE' });
  },
};

/** 创建 AbortController，将 signal 传入 RequestOptions 即可取消请求 */
export const createAbortController = () => new AbortController();

/** 判断是否为归一化的 ApiError */
export const isApiError = (e: unknown): e is ApiError =>
  typeof e === 'object' && e !== null && (e as ApiError).success === false;

/** 从任意错误对象中提取可展示的错误文案 */
export const getErrorMessage = (e: unknown, fallback = '网络有点不给力，请检查设置'): string => {
  if (isApiError(e) && e.errorMsg) return e.errorMsg;
  if (typeof e === 'object' && e !== null && (e as { errorMsg?: string }).errorMsg) {
    return (e as { errorMsg: string }).errorMsg;
  }
  return fallback;
};

export default client;
