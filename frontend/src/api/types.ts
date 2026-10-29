export enum ErrorCode {
  SUCCESS = '00000',
  PHONE_REQUIRED = '10000',
  AUTH_REQUIRED = '10001',
  PHONE_INVALID = '10002',
  LOGIN_CODE_INVALID = '10003',
  LOGIN_CODE_EXPIRED = '10004',
  FOLLOW_SELF_NOT_ALLOWED = '10005',
  CODE_SEND_TOO_FREQUENT = '10006',
  FOLLOW_INVALID_TARGET = '10007',
  PARAM_INVALID = '10008',
  IDEMPOTENCY_REPLAY = '10009',
  BLOG_NOT_FOUND = '20001',
  BLOG_COMMENT_NOT_FOUND = '20002',
  VOUCHER_NOT_FOUND = '30000',
  STOCK_NOT_ENOUGH = '30001',
  ORDER_DUPLICATE = '30002',
  ORDER_CREATE_FAILED = '30003',
  ORDER_CONFLICT = '30004',
  SECKILL_NOT_STARTED = '30005',
  SECKILL_ENDED = '30006',
  VOUCHER_TYPE_MISMATCH = '30007',
  ORDER_NOT_FOUND = '30008',
  ORDER_STATUS_INVALID = '30009',
  VOUCHER_EXPIRED = '30010',
  SHOP_NOT_FOUND = '40001',
  SHOP_COMMENT_NOT_FOUND = '40002',
  SYSTEM_ERROR = '99999',
}

export interface Result<T = null> {
  success: boolean;
  errorCode: string | null;
  errorMsg: string | null;
  data: T;
  total: number | null;
  traceId: string | null;
}

export interface LoginFormDTO {
  phone: string;
  code: string;
  password?: string;
}

export interface LoginResultDTO {
  token: string;
  user: UserDTO;
}

export interface UserDTO {
  id: number;
  nickName: string;
  icon: string;
  /** 角色：0 普通用户 / 1 商家（后端 UserDTO.role，登录与 /users/me 均返回） */
  role?: number;
}

export interface UserInfoDTO {
  userId: number;
  city: string | null;
  introduce: string | null;
  fans: number | null;
  followee: number | null;
  gender: boolean | null;
  birthday: string | null;
  credits: number | null;
  /** 会员等级 0~9，0 表示未开通会员 */
  level: number | null;
}

/** 他人主页聚合信息 */
export interface UserProfileDTO {
  id: number | string;
  nickName: string | null;
  icon: string | null;
  introduce: string | null;
  gender: boolean | null;
  credits: number | null;
  level: number | null;
  followee: number | null;
  fans: number | null;
  /** 该用户发布的笔记数量（后端 Long 以字符串序列化） */
  blogCount: number | string | null;
  /** 当前登录用户是否已关注该用户 */
  followedByMe: boolean;
}

export interface UpdateUserInfoDTO {
  nickName?: string;
  icon?: string;
  gender?: boolean | null;
  city?: string;
  birthday?: string | null;
  introduce?: string;
}

export interface Shop {
  id: number;
  name: string;
  typeId: number;
  images: string | string[] | null;
  area: string | null;
  address: string;
  x: number;
  y: number;
  avgPrice: number | null;
  sold: number | null;
  comments: number | null;
  score: number | null;
  openHours: string | null;
  distance?: number | null;
}

export interface ShopType {
  id: number;
  name: string;
  icon: string;
  sort: number;
}

/**
 * 商家店铺（Merchant「我的店铺」，对应后端 GET /shops/my，Phase 4.6）
 *
 * 为什么与消费者 `Shop` 分开建模：
 * - 消费者 `Shop` 的 `id`/`typeId` 声明为 `number`，且被
 *   `ShopCard.vue` 的 `select: [id: number]` 与 Home / Map / Shop Detail 消费；
 * - 后端 `JacksonConfig` 会把所有 `Long` 序列化为 **String**，商家侧按真实契约
 *   声明 `number | string`，避免为了本模块去放宽消费者类型而牵连消费者页面。
 *
 * 字段类型已按 Phase 4.6 Step 1 的**真实响应**核对：
 * Long（id / typeId / avgPrice）→ String；Integer（sold / comments / score）
 * 与 Double（x / y）→ number；其余 string / string | null。
 *
 * 刻意不包含的字段：
 * - `ownerId`：后端权威归属字段，前端**不得**据此判断权限（一律交后端 assertOwner）；
 * - `distance`：消费者 GEO 专用，本接口恒为 `null`；
 * - `createTime` / `updateTime`：商家店铺 UI 不展示。
 */
export interface MerchantShopDTO {
  id: number | string;
  name: string;
  city: string | null;
  typeId: number | string;
  /** 图片 JSON 数组字符串（如 `"[]"`）；后端 NOT NULL */
  images: string;
  area: string | null;
  address: string;
  x: number;
  y: number;
  /** 人均（元）；后端 Long → String */
  avgPrice: number | string | null;
  sold: number;
  comments: number;
  score: number;
  openHours: string | null;
}

/**
 * 商家新建 / 更新店铺的请求体（= 商家可编辑字段集合）。
 *
 * 刻意不包含：
 * - `ownerId`：由后端从登录态注入（`ShopServiceImpl.createShop`），前端传了也会被覆盖；
 * - `sold` / `comments` / `score`：服务端管理字段，更新时不提交可保持后端原值；
 * - `images`：由 `merchantShop.ts` 固定为 `'[]'`（本阶段不做图片上传）。
 */
export interface MerchantShopWriteRequest {
  name: string;
  typeId: number | string;
  address: string;
  x: number;
  y: number;
  city?: string | null;
  area?: string | null;
  avgPrice?: number | null;
  openHours?: string | null;
}

/**
 * 商家优惠券（对应后端 GET /vouchers/my，Phase 4.7）
 *
 * 与消费者 `Voucher` 分开建模：后端 `JacksonConfig` 把所有 Long 序列化为 String，
 * 因此 id/shopId/payValue/actualValue 以 `number | string` 承载，避免为了本模块
 * 放宽消费者类型而牵连消费者页面。金额单位与后端一致为「分」。
 *
 * 类型已按 GET /vouchers/my 真实响应核对：
 * - Long（id/shopId/payValue/actualValue）→ String → `number | string`；
 * - Integer（type/status/stock）→ number；
 * - LocalDateTime（beginTime/endTime/createTime/updateTime）→ string（ISO `T`）。
 *
 * 秒杀券的 stock/beginTime/endTime 由后端 fillSeckillInfo() 从 tb_seckill_voucher
 * 回填；普通券这三个字段为 null。
 */
export interface MerchantVoucherDTO {
  id: number | string;
  shopId: number | string;
  title: string;
  subTitle: string | null;
  rules: string | null;
  payValue: number | string | null;
  actualValue: number | string | null;
  type: 0 | 1;
  status: number;
  stock: number | null;
  beginTime: string | null;
  endTime: string | null;
  createTime: string | null;
  updateTime: string | null;
}

/**
 * 商家券创建请求体（覆盖普通券与秒杀券，类型由调用方显式构造）。
 * 金额单位为「分」（UI 录入元，提交前 ×100）。秒杀券额外携带 stock/beginTime/endTime。
 */
export interface MerchantVoucherCreateRequest {
  shopId: number | string;
  title: string;
  subTitle?: string | null;
  rules?: string | null;
  payValue: number;
  actualValue: number;
  type: 0 | 1;
  stock?: number;
  beginTime?: string;
  endTime?: string;
}

/**
 * 商家券更新请求体（对应后端 VoucherUpdateRequest 白名单）。
 * 显式不含 shopId/type/stock/beginTime/endTime — 这些字段后端 update 不支持，
 * UI 也不允许编辑。title 后端 @NotBlank，因此 status-only 更新也要带当前 title。
 */
export interface MerchantVoucherUpdateRequest {
  title: string;
  subTitle?: string | null;
  rules?: string | null;
  payValue?: number;
  actualValue?: number;
  status?: number;
}

export interface Voucher {
  id: number;
  shopId: number | string;
  title: string;
  subTitle: string | null;
  rules: string | null;
  payValue: number;
  actualValue: number;
  type: 0 | 1;
  status?: number | null;
  stock?: number | null;
  beginTime?: string | null;
  endTime?: string | null;
}

export interface CreateVoucherOrderResultDTO {
  orderId: number | string;
  voucherId: number | string;
  orderStatus: OrderStatus;
  duplicateBlocked: boolean;
}

export interface MyVoucherItemDTO {
  orderId: number | string;
  orderStatus: OrderStatus;
  orderCreateTime: string | null;
  payTime: string | null;
  voucherId: number | string;
  title: string | null;
  subTitle: string | null;
  rules: string | null;
  payValue: number | null;
  actualValue: number | null;
  type: 0 | 1 | null;
  beginTime: string | null;
  endTime: string | null;
  shopId: number | null;
  shopName: string | null;
  shopImage: string | null;
}

export interface VoucherOrderDetailDTO {
  orderId: number | string;
  userId: number | string;
  voucherId: number | string;
  status: OrderStatus;
  payType: number | null;
  createTime: string | null;
  payTime: string | null;
  useTime: string | null;
  refundTime: string | null;
  voucherTitle: string | null;
  voucherSubTitle: string | null;
  voucherRules: string | null;
  payValue: number | null;
  actualValue: number | null;
  beginTime: string | null;
  endTime: string | null;
  shopId: number | null;
  shopName: string | null;
  shopImage: string | null;
}

/**
 * 商家订单列表项（对应后端 MerchantVoucherOrderItemDTO，Phase 3.4-B）
 *
 * 与消费者侧 MyVoucherItemDTO / VoucherOrderDetailDTO 是不同业务边界：
 * 商家视图额外需要买家 userId 与核销时间 useTime，故独立建模、不复用。
 *
 * 类型约定与既有订单 DTO 保持一致：
 * - 订单/用户/券/店铺 ID 为 Long，JSON 中为字符串 → `number | string`；
 * - 金额（分）沿用既有金额约定 `number | null`，可直接交给 formatMoney()；
 * - 时间字段可空（未支付/未核销为 null）→ `string | null`。
 */
export interface MerchantVoucherOrderItemDTO {
  /** 订单 ID（核销接口入参） */
  orderId: number | string;
  /** 订单状态：1待支付 / 2已支付 / 3已核销 / 4已取消 / 5已过期 / 6已退款 */
  status: OrderStatus;
  /** 下单时间 */
  createTime: string | null;
  /** 支付时间（未支付为 null） */
  payTime: string | null;
  /** 核销时间（未核销为 null） */
  useTime: string | null;
  /** 买家用户 ID */
  userId: number | string;
  /** 优惠券 ID */
  voucherId: number | string;
  /** 优惠券标题 */
  voucherTitle: string | null;
  /** 支付金额（分） */
  payValue: number | null;
  /** 抵扣金额（分） */
  actualValue: number | null;
  /** 所属店铺 ID */
  shopId: number | string;
  /** 所属店铺名称 */
  shopName: string | null;
}

export type MyVoucherTab = 'unused' | 'used' | 'expired' | 'invalid';

/**
 * 优惠券订单状态（数值型枚举，与后端约定的 int 状态码一一对应）
 * -1 排队中 / 1 待支付 / 2 已支付 / 3 已核销 / 4 已取消 / 5 已过期 / 6 已退款
 */
export enum OrderStatus {
  QUEUED = -1,
  UNPAID = 1,
  PAID = 2,
  USED = 3,
  CANCELLED = 4,
  REFUNDING = 5,
  REFUNDED = 6,
}

/** 支付方式 */
export enum PayType {
  BALANCE = 1,
  WECHAT = 2,
  ALIPAY = 3,
}

/** 通用分页查询参数 */
export interface PageQuery {
  current?: number;
  size?: number;
}

/** 通用分页结果（后端 Result 已携带 total，此处作为约定类型） */
export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

// ============================================
// 博客（探店笔记）
// ============================================

/** 博客实体（对应后端 Blog / tb_blog） */
export interface Blog {
  id: number | string;
  shopId: number | string;
  userId: number | string;
  title: string;
  /** 图片 JSON 数组字符串，形如 ["/upload/icon/xxx.jpg"] */
  images: string | null;
  content: string;
  liked: number;
  comments: number;
  createTime: string | null;
  updateTime: string | null;
}

/** 博客详情 DTO（含作者、店铺、评论） */
export interface BlogDetailDTO {
  id: number | string;
  shopId: number | string;
  userId: number | string;
  title: string;
  images: string[] | null;
  content: string;
  liked: number;
  comments: number;
  createTime: string | null;
  author: UserDTO | null;
  shopName: string | null;
  /** 当前登录用户是否已点赞该笔记 */
  likedByMe: boolean | null;
  commentContents: string[] | null;
}

/** 发布博客请求 */
export interface BlogSaveRequest {
  title: string;
  content: string;
  images?: string[];
  shopId: number | string;
}

/** 笔记点赞结果 */
export interface BlogLikeDTO {
  liked: boolean;
  likedCount: number | null;
}

/** 笔记评论 */
export interface BlogCommentDTO {
  id: number | string;
  blogId: number | string;
  /** 笔记标题（「我的评论」列表返回） */
  blogTitle: string | null;
  userId: number | string;
  nickName: string | null;
  icon: string | null;
  /** 关联的一级评论id，0 表示一级评论 */
  parentId: number | string;
  answerId: number | string | null;
  answerNickName: string | null;
  content: string;
  createTime: string | null;
}

/** 发布笔记评论请求 */
export interface BlogCommentCreateRequest {
  content: string;
  parentId?: number | string | null;
  answerId?: number | string | null;
}

// ============================================
// 关注 / 粉丝
// ============================================

/** 关注 / 粉丝列表项 */
export interface FollowUserDTO {
  userId: number | string;
  nickName: string | null;
  icon: string | null;
  /** 当前登录用户是否已关注该用户 */
  followed: boolean;
  createTime: string | null;
}

// ============================================
// 商铺评价
// ============================================

/** 商铺评价列表项 */
export interface ShopCommentDTO {
  id: number | string;
  shopId: number | string;
  shopName: string | null;
  userId: number | string;
  nickName: string | null;
  icon: string | null;
  content: string;
  rating: number;
  createTime: string | null;
}

/** 商铺评价汇总 */
export interface ShopCommentSummaryDTO {
  /** 后端 Long 以字符串序列化，使用 Number() 转换 */
  count: number | string;
  avgRating: number | null;
}

/** 发布商铺评价请求 */
export interface ShopCommentCreateRequest {
  shopId: number | string;
  content: string;
  rating: number;
}

/** 编辑商铺评价请求（不含 shopId，不允许转移所属店铺） */
export interface ShopCommentUpdateRequest {
  content: string;
  rating: number;
}

// ============================================
// 浏览历史
// ============================================

/** 浏览目标类型：1-店铺 2-笔记 */
export enum BrowseTargetType {
  SHOP = 1,
  BLOG = 2,
}

/** 浏览历史列表项 */
export interface BrowseHistoryDTO {
  id: number | string;
  targetType: number;
  targetId: number | string;
  title: string | null;
  image: string | null;
  createTime: string | null;
}

/** 记录浏览历史请求 */
export interface BrowseHistoryCreateRequest {
  targetType: number;
  targetId: number | string;
}

// ============================================
// 意见反馈
// ============================================

/** 反馈列表项 */
export interface FeedbackDTO {
  id: number | string;
  content: string;
  contact: string | null;
  type: number;
  status: number;
  createTime: string | null;
}

/** 提交反馈请求 */
export interface FeedbackCreateRequest {
  content: string;
  contact?: string;
  type?: number;
}

// ============================================
// 消息
// ============================================

/** 消息类型：1 点赞 / 2 关注 / 3 评论 */
export enum MessageType {
  LIKE = 1,
  FOLLOW = 2,
  COMMENT = 3,
}

/** 消息列表项 DTO */
export interface MessageListDTO {
  id: number | string;
  content: string;
  type: number;
  relatedId: number | string | null;
  isRead: number;
  createTime: string | null;
  sender: UserDTO | null;
}
