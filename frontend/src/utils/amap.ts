const AMAP_KEY = import.meta.env.VITE_AMAP_KEY;
const AMAP_SECURITY_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE;
const AMAP_SRC = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}`;
const LOAD_TIMEOUT = 15000;
const MAX_RETRY = 2;

let loading: Promise<any> | null = null;
let loaded = false;

/**
 * 按需加载高德地图 JS API（带缓存、超时与失败重试）
 *
 * 仅在地图页进入时触发，避免同步脚本阻塞整个应用首屏；
 * 多次调用共享同一个 Promise，加载成功后直接返回已缓存的 AMap。
 */
export const loadAMap = (): Promise<any> => {
  if (loaded && window.AMap) {
    return Promise.resolve(window.AMap);
  }
  if (loading) {
    return loading;
  }
  loading = tryLoad(MAX_RETRY);
  return loading;
};

const tryLoad = (retries: number): Promise<any> =>
  new Promise((resolve, reject) => {
    if (window.AMap) {
      loaded = true;
      resolve(window.AMap);
      return;
    }

    // 安全密钥需在高德脚本之前注入
    (window as any)._AMapSecurityConfig = {
      securityJsCode: AMAP_SECURITY_CODE,
    };

    const script = document.createElement('script');
    script.src = AMAP_SRC;
    script.async = true;

    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      script.remove();
      reject(new Error('地图加载超时'));
    }, LOAD_TIMEOUT);

    script.onload = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (window.AMap) {
        loaded = true;
        resolve(window.AMap);
      } else {
        reject(new Error('地图组件初始化失败'));
      }
    };

    script.onerror = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      script.remove();
      if (retries > 0) {
        loading = tryLoad(retries - 1);
        loading!.then(resolve, reject);
      } else {
        reject(new Error('地图加载失败'));
      }
    };

    document.head.appendChild(script);
  });

/** 强制重置缓存（供重试/刷新场景使用） */
export const resetAMapCache = () => {
  loading = null;
  loaded = false;
};