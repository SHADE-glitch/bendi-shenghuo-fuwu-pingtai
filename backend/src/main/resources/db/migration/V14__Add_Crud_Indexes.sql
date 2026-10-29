-- ============================================================
-- V14: 为新增普通 CRUD 查询补齐缺失索引
-- 说明：全部使用 information_schema 守卫，可重复执行（幂等），不影响 V1~V11 既有迁移。
-- ============================================================

-- 1. tb_blog_comments.blog_id：按笔记查评论 / 级联删除笔记评论
SET @cnt_blog_comment = (SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE()
                           AND table_name = 'tb_blog_comments'
                           AND index_name = 'idx_blog_comments_blog');
SET @sql_blog_comment = IF(@cnt_blog_comment = 0,
  'CREATE INDEX idx_blog_comments_blog ON tb_blog_comments(blog_id);',
  'SELECT "idx_blog_comments_blog already exists";');
PREPARE stmt_blog_comment FROM @sql_blog_comment;
EXECUTE stmt_blog_comment;
DEALLOCATE PREPARE stmt_blog_comment;

-- 2. tb_follow.follow_user_id：查粉丝列表 / 判断「我是否已关注 TA」
--    注意：uk_user_follow(user_id, follow_user_id) 的最左前缀是 user_id，
--    单独按 follow_user_id 过滤时用不上该唯一索引。
SET @cnt_follow_target = (SELECT COUNT(*) FROM information_schema.statistics
                          WHERE table_schema = DATABASE()
                            AND table_name = 'tb_follow'
                            AND index_name = 'idx_follow_target');
SET @sql_follow_target = IF(@cnt_follow_target = 0,
  'CREATE INDEX idx_follow_target ON tb_follow(follow_user_id);',
  'SELECT "idx_follow_target already exists";');
PREPARE stmt_follow_target FROM @sql_follow_target;
EXECUTE stmt_follow_target;
DEALLOCATE PREPARE stmt_follow_target;

-- 说明：tb_voucher.shop_id 已由外键 fk_voucher_shop 自动建立索引，无需重复添加。
