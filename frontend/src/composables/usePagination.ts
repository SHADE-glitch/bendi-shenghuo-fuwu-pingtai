import { computed, ref, shallowRef } from 'vue';

export interface PageFetchResult<T> {
  list: T[];
  total: number;
}

export interface PaginationOptions {
  pageSize?: number;
}

/**
 * 通用分页逻辑：封装加载、追加、重置与 finished 状态。
 * 适用于滚动加载 / “加载更多” 场景。
 */
export function usePagination<T>(
  fetcher: (params: { current: number; size: number }) => Promise<PageFetchResult<T>>,
  options: PaginationOptions = {}
) {
  const pageSize = options.pageSize ?? 10;
  const current = ref(1);
  const list = shallowRef<T[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const finished = ref(false);

  // 以 finished 为准：total 缺失（后端返回 0）时不能仅凭 list.length < total 判定「已加载完」
  const hasMore = computed(() => !finished.value);

  /**
   * 请求序号：切 Tab / 重复 reset 时会发起新请求，
   * 旧请求的返回结果通过序号比对被丢弃，避免「后发先至」覆盖新数据。
   */
  let requestSeq = 0;

  const load = async (reset = false) => {
    // 非 reset 的并发加载直接忽略；reset 允许打断进行中的请求
    if (loading.value && !reset) return;
    if (reset) current.value = 1;
    const seq = ++requestSeq;
    loading.value = true;
    try {
      const res = await fetcher({ current: current.value, size: pageSize });
      if (seq !== requestSeq) return;
      list.value = reset ? res.list : [...list.value, ...res.list];
      total.value = res.total;
      // total 缺失（后端返回 0）时退回按「本页是否满页」判断，避免 loadMore 直接失效
      finished.value = res.total > 0
        ? list.value.length >= res.total
        : res.list.length < pageSize;
    } finally {
      if (seq === requestSeq) loading.value = false;
    }
  };

  const loadMore = async () => {
    if (!hasMore.value || finished.value || loading.value) return;
    current.value += 1;
    await load();
  };

  const reset = () => load(true);

  return { current, pageSize, list, total, loading, finished, hasMore, load, loadMore, reset };
}
