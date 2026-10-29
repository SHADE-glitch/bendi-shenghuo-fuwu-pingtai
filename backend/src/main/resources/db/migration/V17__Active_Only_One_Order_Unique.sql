-- ============================================================
-- V17: 「一人一单」唯一键收敛为仅活跃订单（P1-4）
-- 说明：
--   * 背景：V2 建的 uk_user_voucher(user_id, voucher_id) 约束「同一用户对同一张券
--     只能有一条订单」，不区分订单状态。取消/退款后库存与 Redis 占位都已回补，
--     但唯一名额仍被终态订单占着，用户再次购买拿到 ORDER_DUPLICATE（30002），
--     与 rollbackStock 「保证用户可再次抢购」的语义矛盾。
--   * 方案：新增生成列 active_voucher_id —— 状态处于取消(4)/退款(6) 时为 NULL，
--     其余状态为 voucher_id；唯一索引 (user_id, active_voucher_id) 只约束
--     「活跃订单」，MySQL 唯一索引允许多个 NULL，终态订单不再占名额。
--     订单记录本身保留（历史/对账/「已取消」列表可见），不做物理删除。
--   * 与代码的对应：com.hmdp.utils.OrderStatus.UNIQUENESS_RELEASED_STATUSES
--     （CANCELLED + REFUNDED）；seckillVoucher / createVoucherOrder /
--     createOrderByMessage 的一人一单 count 查询同步排除这两个终态。
--   * 生成列用 VIRTUAL 而非 STORED：tb_voucher_order 上有 V8 建的两个外键
--     （fk_order_user / fk_order_voucher），加 STORED 列会触发表重建，MySQL 8
--     重放外键时报 1215 Cannot add foreign key constraint，迁移直接失败
--     （2026-10-25 实测：EXECUTE stmt_col 报 1215）。VIRTUAL 列只改元数据、
--     不重建表，且 MySQL 5.7+ 支持在虚拟列上建（唯一）二级索引，
--     唯一性语义与 STORED 完全一致。
--   * 全部使用 information_schema 守卫，可重复执行（幂等），风格同 V14~V16。
-- ============================================================

-- 1. 生成列：终态（取消/退款）为 NULL，其余为 voucher_id
SET @cnt_col = (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name   = 'tb_voucher_order'
                  AND column_name  = 'active_voucher_id');
SET @sql_col = IF(@cnt_col = 0,
  'ALTER TABLE `tb_voucher_order`
     ADD COLUMN `active_voucher_id` bigint(20) UNSIGNED
       GENERATED ALWAYS AS (CASE WHEN `status` IN (4, 6) THEN NULL ELSE `voucher_id` END) VIRTUAL
       COMMENT ''一人一单活跃名额：取消/退款终态为 NULL（释放名额），其余为 voucher_id'';',
  'SELECT "active_voucher_id already exists";');
PREPARE stmt_col FROM @sql_col;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 2. 新的唯一索引：只约束活跃订单（NULL 不参与唯一性冲突）
SET @cnt_idx = (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name   = 'tb_voucher_order'
                  AND index_name   = 'uk_user_voucher_active');
SET @sql_idx = IF(@cnt_idx = 0,
  'ALTER TABLE `tb_voucher_order` ADD UNIQUE INDEX `uk_user_voucher_active` (`user_id`, `active_voucher_id`);',
  'SELECT "uk_user_voucher_active already exists";');
PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;

-- 3. 删除旧的全量唯一键（它仍在约束终态订单，是 P1-4 的直接根因）。
--    守卫式删除：不存在则跳过。非唯一索引 idx_user_voucher(user_id, voucher_id)
--    保留不动（查询覆盖与旧键部分重叠，但删索引不属于本次语义修正范围）。
SET @cnt_old = (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name   = 'tb_voucher_order'
                  AND index_name   = 'uk_user_voucher');
SET @sql_old = IF(@cnt_old > 0,
  'ALTER TABLE `tb_voucher_order` DROP INDEX `uk_user_voucher`;',
  'SELECT "uk_user_voucher already dropped";');
PREPARE stmt_old FROM @sql_old;
EXECUTE stmt_old;
DEALLOCATE PREPARE stmt_old;
