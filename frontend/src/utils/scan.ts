export const SCAN_SCHEME = 'llsp://';

export interface ScanPayload {
  type: 'shop' | 'voucher';
  shopId?: string;
  voucherId?: string;
}

export const encodeShopLink = (id: string | number): string => `${SCAN_SCHEME}shop/${id}`;

export const encodeVoucherLink = (shopId: string | number, voucherId: string | number): string =>
  `${SCAN_SCHEME}voucher/${shopId}/${voucherId}`;

export const parseScanText = (text: string): ScanPayload | null => {
  const t = text.trim();
  if (!t.startsWith(SCAN_SCHEME)) return null;
  const parts = t.slice(SCAN_SCHEME.length).split('/').filter(Boolean);
  if (parts[0] === 'shop' && parts[1]) {
    return { type: 'shop', shopId: parts[1] };
  }
  if (parts[0] === 'voucher' && parts[1] && parts[2]) {
    return { type: 'voucher', shopId: parts[1], voucherId: parts[2] };
  }
  return null;
};

export const resolveScanTarget = (payload: ScanPayload): string =>
  payload.shopId ? `/shop/${payload.shopId}` : '/';