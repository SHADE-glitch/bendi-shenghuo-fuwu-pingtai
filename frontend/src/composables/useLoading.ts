import { ref } from 'vue';

/**
 * 通用加载状态管理：暴露布尔 loading 与 withLoading 包装器。
 */
export function useLoading(initial = false) {
  const loading = ref(initial);

  const start = () => {
    loading.value = true;
  };

  const stop = () => {
    loading.value = false;
  };

  /** 包裹异步任务，自动开启/关闭 loading */
  const withLoading = async <T>(fn: () => Promise<T>): Promise<T> => {
    loading.value = true;
    try {
      return await fn();
    } finally {
      loading.value = false;
    }
  };

  return { loading, start, stop, withLoading };
}
