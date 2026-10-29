-- V6__Cleanup_Legacy.sql
-- 草案：清理历史遗留表（必须先备份并人工确认）。
-- 注意：本文件为草稿，执行前请手工核对并在 CI/DEV 环境中先测试。
-- 运行建议：在执行前导出备份（mysqldump），并确保目标为开发数据库（schema = hmdp）。

-- === 说明 ===
-- 下面列出的 DROP 操作默认被注释，解除注释并执行前，必须手动确认并做好数据备份。
-- 删除的表为：tb_blog, tb_blog_comments, tb_follow, tb_shop_comments, tb_message
-- 这些表在当前代码（backend/src/main/java）无实体/Controller引用，且前端对应页面为占位/降级实现。

-- 备份示例（在宿主机上运行）：
-- docker compose exec mysql sh -c 'exec mysqldump -uroot -prootpassword --databases hmdp > /tmp/hmdp_backup_$(date +"%Y%m%d_%H%M").sql'

-- =====================
-- 如果确认要删除，请解除下方 DROP 的注释并在安全环境执行
-- =====================

-- DROP TABLE IF EXISTS `tb_blog`;
-- DROP TABLE IF EXISTS `tb_blog_comments`;
-- DROP TABLE IF EXISTS `tb_follow`;
-- DROP TABLE IF EXISTS `tb_shop_comments`;
-- DROP TABLE IF EXISTS `tb_message`;

-- =====================
-- 兼容性与迁移建议
-- 1) 若需保留历史数据，请先导出上方备份文件并存档（s3 / 本地 / git-lfs）。
-- 2) 若部分字段需迁移到新表或压缩存储，请在删除前写明迁移 SQL（SELECT ... INSERT INTO archive_...）。
-- 3) 执行后，若需要恢复，可使用备份文件恢复。
-- =====================

-- 注意：本文件不包含对保留表（tb_user/tb_user_info/tb_shop/tb_shop_type/tb_voucher/tb_seckill_voucher/tb_voucher_order）结构的修改。
-- 如需对表结构调整（删除字段/新增约束/索引），请在单独的 migration 文件中逐项描述并提供回滚步骤。
