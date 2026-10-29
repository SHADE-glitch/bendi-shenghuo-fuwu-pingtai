import { execFileSync } from 'node:child_process';
import { request, type APIRequestContext } from '@playwright/test';
import { E2E_API_BASE, E2E_PREFIX, LOGIN_CODE, REDIS_AUTH_ARGS, REDIS_CONTAINER } from './env';

/**
 * Merchant E2E fixture（Phase 4.5）
 *
 * 本仓库**没有角色提升接口**（Phase 3.6 的 R5：role=1 只能手改 SQL），
 * 因此让一个账号成为商家必须直写一次 DB —— 这是本套件**唯一**的 DB 写入。
 * 其余全部走真实 API：登录 / 建店 / 建券 / 下单 / 支付 / 核销。
 *
 * 造数约定：
 * - 商家账号使用专用手机号（测后整体删除）；
 * - 店铺/券使用 `E2E-` 前缀；
 * - 券一律用**普通券 type=0**：不消耗 tb_seckill_voucher 库存、不写 Redis 秒杀集合，
 *   与既有 `journey-order.spec.ts` 的取舍一致，因此清理可直接删行而不影响冻结的秒杀数据。
 */

const MYSQL_CONTAINER =
  process.env.E2E_MYSQL_CONTAINER ?? 'Local-life-service-platform-mysql';
const MYSQL_DB = 'hmdp';

/** 专用 e2e 商家手机号（role=1 由 SQL 提升） */
export const MERCHANT_A_PHONE = process.env.E2E_MERCHANT_A_PHONE ?? '13900000001';
export const MERCHANT_B_PHONE = process.env.E2E_MERCHANT_B_PHONE ?? '13900000002';
/** 消费者 = 种子用户 1（与 e2e/global-setup.ts 一致） */
export const CONSUMER_PHONE = process.env.E2E_PHONE ?? '13686869696';

function redisCli(args: string[]): void {
  execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', ...REDIS_AUTH_ARGS, ...args], {
    stdio: ['ignore', 'ignore', 'ignore'],
  });
}

/**
 * 直连 mysql 执行 SQL（SQL 经 stdin 传入，避免 shell 转义问题）。
 * 必须带上默认库名：多表 `DELETE ... JOIN` 即使写了 `hmdp.` 前缀，
 * MySQL 仍要求 session 有默认库，否则报 1046 No database selected。
 */
function mysql(sql: string): string {
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      MYSQL_CONTAINER,
      'sh',
      '-c',
      `mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B ${MYSQL_DB}`,
    ],
    { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'ignore'] }
  );
}

async function loginWith(
  api: APIRequestContext,
  phone: string
): Promise<{ token: string; userId: string; role: number }> {
  redisCli(['set', `login:code:${phone}`, LOGIN_CODE, 'EX', '1800']);
  const res = await api.post('/users/login', { data: { phone, code: LOGIN_CODE } });
  const body = (await res.json()) as {
    success?: boolean;
    data?: { token?: string; user?: { id?: string | number; role?: number } };
  };
  if (!body?.success || !body.data?.token) {
    throw new Error(`e2e 登录失败 phone=${phone}: ${JSON.stringify(body)}`);
  }
  return {
    token: body.data.token,
    userId: String(body.data.user?.id ?? ''),
    role: Number(body.data.user?.role ?? 0),
  };
}

/** 通过真实登录接口拿 token（手机号不存在时后端自动注册，role=0） */
export async function loginUser(
  phone: string
): Promise<{ token: string; userId: string; role: number }> {
  const api = await request.newContext({ baseURL: E2E_API_BASE });
  try {
    return await loginWith(api, phone);
  } finally {
    await api.dispose();
  }
}

/** 消费者真实下单 + 支付 → PAID(2) */
export async function createPaidOrder(consumerToken: string, voucherId: string): Promise<string> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: consumerToken },
  });
  try {
    const purchaseRes = await api.post(`/voucher-orders/purchase-orders?voucherId=${voucherId}`);
    const purchase = (await purchaseRes.json()) as {
      success?: boolean;
      data?: { orderId?: string | number };
    };
    const orderId = String(purchase?.data?.orderId ?? '');
    if (!purchase?.success || !orderId) {
      throw new Error(`e2e 下单失败 voucherId=${voucherId}: ${JSON.stringify(purchase)}`);
    }
    const payRes = await api.post(`/voucher-orders/${orderId}/pay?payType=1`);
    const pay = (await payRes.json()) as { success?: boolean; errorMsg?: string };
    if (!pay?.success) {
      throw new Error(`e2e 支付失败 orderId=${orderId}: ${JSON.stringify(pay)}`);
    }
    return orderId;
  } finally {
    await api.dispose();
  }
}

export interface MerchantFixture {
  phone: string;
  userId: string;
  token: string;
  shopId: string;
  voucherId: string;
  orderId: string;
}

/**
 * 建一个「有店 / 有券 / 有一张 PAID(2) 订单」的商家 fixture。
 */
export async function createMerchantFixture(
  phone: string,
  label: string,
  consumerToken: string
): Promise<MerchantFixture> {
  // 1) 首次登录：不存在则自动注册（role=0）
  await loginUser(phone);
  // 2) 唯一的一步 DB 写入：提升 role=1（无接口可用，见文件头注释）
  mysql(`UPDATE ${MYSQL_DB}.tb_user SET role=1 WHERE phone='${phone}';`);
  // 3) 重新登录：旧 token 的 Redis 会话里仍缓存 role=0，必须换一个 role=1 的会话
  const merchant = await loginUser(phone);
  if (merchant.role !== 1) {
    throw new Error(`role 提升未生效 phone=${phone} role=${merchant.role}`);
  }

  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: merchant.token },
  });
  try {
    // 4) 建店（owner_id 由后端从登录态注入，客户端无法指定）
    const shopRes = await api.post('/shops', {
      data: {
        name: `${E2E_PREFIX}Shop-${label}`,
        city: '杭州',
        typeId: 1,
        images: '[]',
        address: `${E2E_PREFIX}addr-${label}`,
        x: 120.1,
        y: 30.2,
        sold: 0,
        comments: 0,
        score: 50,
      },
    });
    const shopBody = (await shopRes.json()) as { success?: boolean; data?: string | number };
    const shopId = String(shopBody?.data ?? '');
    if (!shopBody?.success || !shopId) {
      throw new Error(`e2e 建店失败: ${JSON.stringify(shopBody)}`);
    }

    // 5) 建普通券（type=0）
    const voucherRes = await api.post('/vouchers', {
      data: {
        shopId,
        title: `${E2E_PREFIX}券-${label}`,
        payValue: 1000,
        actualValue: 2000,
        type: 0,
      },
    });
    const voucherBody = (await voucherRes.json()) as { success?: boolean; data?: string | number };
    const voucherId = String(voucherBody?.data ?? '');
    if (!voucherBody?.success || !voucherId) {
      throw new Error(`e2e 建券失败: ${JSON.stringify(voucherBody)}`);
    }

    // 6) 消费者真实下单 + 支付
    const orderId = await createPaidOrder(consumerToken, voucherId);

    return { phone, userId: merchant.userId, token: merchant.token, shopId, voucherId, orderId };
  } finally {
    await api.dispose();
  }
}

/**
 * 清理 e2e 商家造数：按 phone 关联 owner→shop→voucher→order 逐层删除，
 * 最后删账号并清 Redis 验证码键。不触碰任何种子数据。
 */
export async function cleanupMerchants(phones: string[]): Promise<void> {
  if (phones.length === 0) return;
  const list = phones.map((p) => `'${p}'`).join(',');

  mysql(
    `DELETE o FROM ${MYSQL_DB}.tb_voucher_order o` +
      ` JOIN ${MYSQL_DB}.tb_voucher v ON o.voucher_id = v.id` +
      ` JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id = s.id` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list});`
  );
  mysql(
    `DELETE v FROM ${MYSQL_DB}.tb_voucher v` +
      ` JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id = s.id` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list});`
  );
  mysql(
    `DELETE s FROM ${MYSQL_DB}.tb_shop s` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list});`
  );
  mysql(`DELETE FROM ${MYSQL_DB}.tb_user WHERE phone IN (${list});`);

  for (const phone of phones) {
    redisCli(['del', `login:code:${phone}`]);
  }
}

/** 读订单真实 DB 行（用于核销后的后端状态验证） */
export function getOrderRow(orderId: string): { status: number; useTime: string | null } {
  const out = mysql(
    `SELECT status, IFNULL(use_time,'') FROM ${MYSQL_DB}.tb_voucher_order WHERE id='${orderId}';`
  ).trim();
  const [status, useTime] = out.split('\t');
  return { status: Number(status), useTime: useTime ? useTime : null };
}

/** 仅提升为商家（不建店/建券/下单），供 Merchant Shop E2E 使用 */
export async function ensureMerchantRole(
  phone: string
): Promise<{ token: string; userId: string; role: number }> {
  // 1) 首次登录：不存在则自动注册（role=0）
  await loginUser(phone);
  // 2) 唯一的一步 DB 写入：提升 role=1（无接口可用，见文件头注释）
  mysql(`UPDATE ${MYSQL_DB}.tb_user SET role=1 WHERE phone='${phone}';`);
  // 3) 重新登录：旧 token 的 Redis 会话里仍缓存 role=0，必须换一个 role=1 的会话
  const merchant = await loginUser(phone);
  if (merchant.role !== 1) {
    throw new Error(`role 提升未生效 phone=${phone} role=${merchant.role}`);
  }
  return merchant;
}

export interface ShopRow {
  name: string;
  typeId: string;
  address: string;
  openHours: string;
  x: string;
  y: string;
  ownerId: string;
  images: string;
  sold: number;
  comments: number;
  score: number;
}

/** 读店铺真实 DB 行（Merchant Shop E2E 的落库/更新验证） */
export function getShopRow(shopId: string): ShopRow {
  const out = mysql(
    `SELECT name, type_id, address, IFNULL(open_hours,''), x, y, owner_id, images, sold, comments, score` +
      ` FROM ${MYSQL_DB}.tb_shop WHERE id='${shopId}';`
  ).trim();
  const [name, typeId, address, openHours, x, y, ownerId, images, sold, comments, score] =
    out.split('\t');
  return {
    name,
    typeId,
    address,
    openHours,
    x,
    y,
    ownerId,
    images,
    sold: Number(sold),
    comments: Number(comments),
    score: Number(score),
  };
}

/** E2E 残留（shops/users/vouchers/orders 限定 phones）+ 全表店铺总数（种子完整性核对） */
export function countE2eFullResidue(phones: string[]): {
  shops: number;
  users: number;
  vouchers: number;
  orders: number;
  totalShops: number;
} {
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_shop s JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_user WHERE phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher v JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher_order o JOIN ${MYSQL_DB}.tb_voucher v ON o.voucher_id=v.id JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_shop);`
  ).trim();
  const [shops, users, vouchers, orders, totalShops] = out.split('\t');
  return {
    shops: Number(shops),
    users: Number(users),
    vouchers: Number(vouchers),
    orders: Number(orders),
    totalShops: Number(totalShops),
  };
}

/** 统计当前库中 e2e 造数残留（供最终清理核对） */
export function countE2eResidue(phones: string[]): { shops: number; users: number } {
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_shop s JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_user WHERE phone IN (${list}));`
  ).trim();
  const [shops, users] = out.split('\t');
  return { shops: Number(shops), users: Number(users) };
}

// ------------------------------------------------------------------
// Phase 4.7 Merchant Voucher E2E 辅助（仅新增，不改既有函数）
// ------------------------------------------------------------------

/** redis-cli 变体：把 stdout 当作返回值（用于 GET 读取） */
function redisCliOut(args: string[]): string {
  return execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', ...REDIS_AUTH_ARGS, ...args], {
    encoding: 'utf-8',
  }).trim();
}

/** 读优惠券真实 DB 行（tb_voucher） */
export interface VoucherRow {
  title: string;
  subTitle: string;
  rules: string;
  payValue: number;
  actualValue: number;
  type: number;
  status: number;
  shopId: string;
}
export function getVoucherRow(voucherId: string): VoucherRow {
  const out = mysql(
    `SELECT title, IFNULL(sub_title,''), IFNULL(rules,''), pay_value, actual_value, type, status, shop_id` +
      ` FROM ${MYSQL_DB}.tb_voucher WHERE id='${voucherId}';`
  ).trim();
  const [title, subTitle, rules, payValue, actualValue, type, status, shopId] = out.split('\t');
  return {
    title,
    subTitle,
    rules,
    payValue: Number(payValue),
    actualValue: Number(actualValue),
    type: Number(type),
    status: Number(status),
    shopId,
  };
}

/** 读秒杀券真实 DB 行（tb_seckill_voucher，voucher_id 为主键；时间为本地 datetime 字符串） */
export interface SeckillRow {
  stock: number;
  beginTime: string;
  endTime: string;
}
export function getSeckillRow(voucherId: string): SeckillRow {
  const out = mysql(
    `SELECT stock, DATE_FORMAT(begin_time,'%Y-%m-%d %H:%i:%s'), DATE_FORMAT(end_time,'%Y-%m-%d %H:%i:%s')` +
      ` FROM ${MYSQL_DB}.tb_seckill_voucher WHERE voucher_id='${voucherId}';`
  ).trim();
  const [stock, beginTime, endTime] = out.split('\t');
  return { stock: Number(stock), beginTime, endTime };
}

/** 读 Redis 秒杀库存 seckill:stock:{voucherId}（不存在返回 null） */
export function getRedisSeckillStock(voucherId: string): string | null {
  const v = redisCliOut(['get', `seckill:stock:${voucherId}`]);
  return v === '' ? null : v;
}

/** 删除指定券的 Redis 秒杀库存 key */
export function deleteSeckillStock(voucherIds: string[]): void {
  for (const id of voucherIds) redisCli(['del', `seckill:stock:${id}`]);
}

/** 删除「指定商家名下所有券」对应的 Redis 秒杀库存 key（幂等预清理/残留清理） */
export function deleteSeckillStockByPhones(phones: string[]): void {
  if (phones.length === 0) return;
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT v.id FROM ${MYSQL_DB}.tb_voucher v` +
      ` JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id = s.id` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list});`
  ).trim();
  const ids = out ? out.split('\n') : [];
  for (const id of ids) {
    const t = id.trim();
    if (t) redisCli(['del', `seckill:stock:${t}`]);
  }
}

// ------------------------------------------------------------------
// Phase 4.8 Step 2 Merchant Dashboard E2E 辅助（纯新增，只读统计，不改既有函数）
// ------------------------------------------------------------------

/** 按 phone 统计商家名下店铺数（tb_shop.owner_id ← tb_user.id ← phone） */
export function countShopsByPhones(phones: string[]): number {
  if (phones.length === 0) return 0;
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT COUNT(*) FROM ${MYSQL_DB}.tb_shop s` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list});`
  ).trim();
  return Number(out);
}

/** 按 phone 统计商家名下券：总数 / 上架(status=1) / 秒杀(type=1) */
export function countVouchersByPhones(phones: string[]): {
  total: number;
  published: number;
  seckill: number;
} {
  if (phones.length === 0) return { total: 0, published: 0, seckill: 0 };
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher v JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher v JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list}) AND v.status=1),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher v JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list}) AND v.type=1);`
  ).trim();
  const [total, published, seckill] = out.split('\t');
  return { total: Number(total), published: Number(published), seckill: Number(seckill) };
}

/**
 * 按 phone 统计商家名下订单数（tb_voucher_order ← tb_voucher ← tb_shop ← phone）。
 * status 为 null/undefined 时统计全部，否则按 status 过滤（2=待核销，3=已核销）。
 */
export function countMerchantOrdersByPhones(phones: string[], status?: number | null): number {
  if (phones.length === 0) return 0;
  const list = phones.map((p) => `'${p}'`).join(',');
  const statusCond =
    status === null || status === undefined ? '' : ` AND o.status=${Number(status)}`;
  const out = mysql(
    `SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher_order o` +
      ` JOIN ${MYSQL_DB}.tb_voucher v ON o.voucher_id = v.id` +
      ` JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id = s.id` +
      ` JOIN ${MYSQL_DB}.tb_user u ON s.owner_id = u.id` +
      ` WHERE u.phone IN (${list})${statusCond};`
  ).trim();
  return Number(out);
}

/** 按 phone 查商家 userId（店铺 owner_id 断言用） */
export function getMerchantUserIdByPhone(phone: string): string {
  return mysql(`SELECT id FROM ${MYSQL_DB}.tb_user WHERE phone='${phone}';`).trim();
}

/** 统计指定商家的 e2e 券/秒杀券/店铺/用户/订单残留（供最终清理核对） */
export function countE2eVoucherResidue(phones: string[]): {
  vouchers: number;
  seckillVouchers: number;
  shops: number;
  users: number;
  orders: number;
} {
  if (phones.length === 0) return { vouchers: 0, seckillVouchers: 0, shops: 0, users: 0, orders: 0 };
  const list = phones.map((p) => `'${p}'`).join(',');
  const out = mysql(
    `SELECT (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher v JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_seckill_voucher sv JOIN ${MYSQL_DB}.tb_voucher v ON sv.voucher_id=v.id JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_shop s JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_user WHERE phone IN (${list})),` +
      ` (SELECT COUNT(*) FROM ${MYSQL_DB}.tb_voucher_order o JOIN ${MYSQL_DB}.tb_voucher v ON o.voucher_id=v.id JOIN ${MYSQL_DB}.tb_shop s ON v.shop_id=s.id JOIN ${MYSQL_DB}.tb_user u ON s.owner_id=u.id WHERE u.phone IN (${list}));`
  ).trim();
  const [vouchers, seckillVouchers, shops, users, orders] = out.split('\t');
  return {
    vouchers: Number(vouchers),
    seckillVouchers: Number(seckillVouchers),
    shops: Number(shops),
    users: Number(users),
    orders: Number(orders),
  };
}
