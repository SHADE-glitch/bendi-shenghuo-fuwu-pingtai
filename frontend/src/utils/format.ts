import type { OrderStatus } from '@/api/types';

export const formatMoney = (value?: number | null): string => {
  const cents = Number(value || 0);
  const yuan = cents / 100;
  return Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2);
};

export const formatScore = (score?: number | null): number => {
  return Number(((score || 0) / 10).toFixed(1));
};

export const orderStatusText = (status?: OrderStatus | number | null): string => {
  switch (status) {
    case -1:
      return '排队中';
    case 1:
      return '待支付';
    case 2:
      return '已支付';
    case 3:
      return '已核销';
    case 4:
      return '已取消';
    case 5:
      return '已过期';
    case 6:
      return '已退款';
    default:
      return '未知状态';
  }
};

export const isOrderSuccessStatus = (status?: OrderStatus | null): boolean => {
  return status === 1 || status === 2 || status === 3;
};

export const isOrderFinalFailedStatus = (status?: OrderStatus | null): boolean => {
  return status === 4 || status === 5 || status === 6;
};

export const formatDateTime = (value?: string | null): string => {
  if (!value) return '-';
  const m = value.replace('T', ' ').match(/^(\d{4}-\d{2}-\d{2})[ ](\d{2}:\d{2})/);
  if (m) return `${m[1]} ${m[2]}`;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

export const firstImage = (imagesJson?: string | null): string => {
  if (!imagesJson) return '';
  try {
    const arr = JSON.parse(imagesJson);
    return Array.isArray(arr) && arr.length > 0 ? arr[0] : '';
  } catch {
    return imagesJson;
  }
};

/**
 * 把后端的富文本字段（种子数据用 `<br/>` 表示换行）转成纯文本换行。
 * 只做换行符替换、不解析任何 HTML 标签，渲染侧继续用插值，天然免疫 XSS。
 */
export const plainText = (raw?: string | null): string =>
  (raw ?? '').replace(/<br\s*\/?>/gi, '\n').replace(/&nbsp;/gi, ' ');

/** 相对时间：刚刚 / N分钟前 / N小时前 / N天前 / 具体日期 */
export const timeAgo = (value?: string | number | Date | null, now = Date.now()): string => {
  if (!value) return '-';
  const time = typeof value === 'number' ? value : new Date(value).getTime();
  if (Number.isNaN(time)) return '-';
  const diff = now - time;
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) return '刚刚';
  if (diff < hour) return `${Math.floor(diff / minute)}分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)}小时前`;
  if (diff < 30 * day) return `${Math.floor(diff / day)}天前`;
  return formatDateTime(typeof value === 'string' ? value : new Date(time).toISOString());
};
