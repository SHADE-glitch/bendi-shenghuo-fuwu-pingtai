import { reactive, ref } from 'vue';
import { showConfirmDialog, showToast } from 'vant';
import { orderService } from '@/api/orderService';

export interface VoucherOrderLike {
  orderId: number | string;
  title?: string | null;
  payValue?: number | null;
  status?: number | null;
}

export const PAY_TYPE_OPTIONS = [
  { value: 1, label: '余额支付' },
  { value: 2, label: '微信支付' },
  { value: 3, label: '支付宝' },
];

/**
 * 优惠券订单操作（伪支付 / 取消 / 退款 / 删除）共用逻辑
 */
export function useVoucherOrderActions(refresh?: () => void) {
  const payDialog = reactive({
    show: false,
    order: null as VoucherOrderLike | null,
    payType: 1,
    paying: false,
  });
  const actionLoading = ref(false);

  const openPayDialog = (order: VoucherOrderLike) => {
    payDialog.order = order;
    payDialog.payType = 1;
    payDialog.show = true;
  };

  const doPay = async () => {
    const order = payDialog.order;
    if (!order || payDialog.paying) return;
    payDialog.paying = true;
    try {
      await orderService.pay(order.orderId, payDialog.payType);
      payDialog.show = false;
      showToast('支付成功');
      refresh?.();
    } catch (e: any) {
      showToast(e?.errorMsg || '支付失败，请重试');
    } finally {
      payDialog.paying = false;
    }
  };

  const runAction = async (action: Promise<unknown>, successMsg: string) => {
    if (actionLoading.value) return false;
    actionLoading.value = true;
    try {
      await action;
      showToast(successMsg);
      refresh?.();
      return true;
    } catch (e: any) {
      showToast(e?.errorMsg || '操作失败，请重试');
      return false;
    } finally {
      actionLoading.value = false;
    }
  };

  const confirmThen = (title: string, message: string) =>
    showConfirmDialog({
      title,
      message,
      theme: 'round-button',
      confirmButtonText: '确认',
      cancelButtonText: '再想想',
    }).then(
      () => true,
      () => false
    );

  const cancelOrder = async (order: VoucherOrderLike) => {
    if (!(await confirmThen('取消订单', '确定取消该订单吗？取消后将释放优惠券库存。'))) return false;
    return runAction(orderService.cancel(order.orderId), '订单已取消');
  };

  const refundOrder = async (order: VoucherOrderLike) => {
    if (!(await confirmThen('申请退款', '确定对该优惠券发起退款吗？退款后优惠券将不可用。'))) return false;
    // 后端退款为同步 CAS 流转（2→6）且同调用内回滚库存，成功即终态，不可描述为「退款中」
    return runAction(orderService.refund(order.orderId), '已退款，优惠券不可再用');
  };

  const deleteOrder = async (order: VoucherOrderLike) => {
    if (!(await confirmThen('删除订单', '确定删除该订单记录吗？删除后不可恢复。'))) return false;
    return runAction(orderService.removeOrder(order.orderId), '订单已删除');
  };

  return {
    payDialog,
    actionLoading,
    openPayDialog,
    doPay,
    cancelOrder,
    refundOrder,
    deleteOrder,
  };
}
