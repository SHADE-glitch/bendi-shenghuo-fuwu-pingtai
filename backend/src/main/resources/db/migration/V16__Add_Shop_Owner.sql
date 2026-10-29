-- ============================================================
-- V16: Merchant MVP 第二阶段 —— tb_shop 增加店铺归属字段 owner_id
-- 说明：
--   * 语义：owner_id 表示该店铺归属的商家用户 id，指向 tb_user.id。
--     关系为 1 个商家(role=1) : N 家店铺；一个店铺只有一个 owner。
--   * 类型与 tb_user.id 严格保持一致：bigint(20) UNSIGNED（见 V1 建表）。
--   * 允许为 NULL：存量 1290 家历史店铺不做归属填充，
--     避免把历史数据强行归属给某个商家而制造虚假业务事实。
--     新店铺的 owner_id 将在后续阶段由创建逻辑写入（本阶段不改创建逻辑）。
--   * 不建立外键：本项目仅 V8 一次性为既有核心表补过 FK，
--     V9 之后的新迁移（含 V13 的 tb_feedback.user_id、tb_message.sender_id
--     等用户引用列）一律不建 FK，此处保持同一风格。
--   * 使用 information_schema 守卫，可重复执行（幂等），不影响 V1~V15。
-- ============================================================

-- 1. tb_shop 增加 owner_id 列（可空，历史数据保持 NULL）
SET @cnt_shop_owner = (SELECT COUNT(*) FROM information_schema.columns
                       WHERE table_schema = DATABASE()
                         AND table_name   = 'tb_shop'
                         AND column_name  = 'owner_id');
SET @sql_shop_owner = IF(@cnt_shop_owner = 0,
  'ALTER TABLE `tb_shop`
     ADD COLUMN `owner_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT ''归属商家用户id，指向 tb_user.id；NULL 表示未归属（历史店铺）'';',
  'SELECT "tb_shop.owner_id already exists";');
PREPARE stmt_shop_owner FROM @sql_shop_owner;
EXECUTE stmt_shop_owner;
DEALLOCATE PREPARE stmt_shop_owner;

-- 2. owner_id 索引：供后续「按商家查其名下店铺」与 owner 归属校验使用
--    （与 V13 为 tb_feedback.user_id 建 idx_feedback_user、V14 补齐缺失索引同一风格）
SET @cnt_shop_owner_idx = (SELECT COUNT(*) FROM information_schema.statistics
                           WHERE table_schema = DATABASE()
                             AND table_name   = 'tb_shop'
                             AND index_name   = 'idx_shop_owner');
SET @sql_shop_owner_idx = IF(@cnt_shop_owner_idx = 0,
  'CREATE INDEX idx_shop_owner ON tb_shop(owner_id);',
  'SELECT "idx_shop_owner already exists";');
PREPARE stmt_shop_owner_idx FROM @sql_shop_owner_idx;
EXECUTE stmt_shop_owner_idx;
DEALLOCATE PREPARE stmt_shop_owner_idx;
