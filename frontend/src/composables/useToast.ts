import {
  showToast,
  showSuccessToast,
  showFailToast,
  showLoadingToast,
  closeToast,
} from 'vant';

/**
 * 统一 Toast 提示封装，语义化方法名，便于全局替换提示实现。
 */
export function useToast() {
  return {
    /** 普通提示 */
    info: showToast,
    /** 成功提示 */
    success: showSuccessToast,
    /** 失败提示 */
    fail: showFailToast,
    /** 加载提示（需手动 close） */
    loading: showLoadingToast,
    /** 关闭当前提示 */
    close: closeToast,
  };
}
