-- ============================================================
-- V15: Merchant MVP 第一阶段 —— tb_user 增加商家角色字段
-- 说明：
--   * 使用 information_schema 守卫，可重复执行（幂等），不影响既有迁移与存量数据。
--   * role 默认 0（普通用户），存量用户无需迁移即为普通用户，天然兼容。
--   * 本阶段不改动 tb_shop（owner_id 归属校验留待第二阶段）。
-- ============================================================

-- 1. tb_user 增加 role 列（0=普通用户，1=商家），带默认值，兼容存量数据
SET @cnt_user_role = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE()
                        AND table_name   = 'tb_user'
                        AND column_name  = 'role');
SET @sql_user_role = IF(@cnt_user_role = 0,
  'ALTER TABLE `tb_user`
     ADD COLUMN `role` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT ''角色：0-普通用户 1-商家'';',
  'SELECT "tb_user.role already exists";');
PREPARE stmt_user_role FROM @sql_user_role;
EXECUTE stmt_user_role;
DEALLOCATE PREPARE stmt_user_role;