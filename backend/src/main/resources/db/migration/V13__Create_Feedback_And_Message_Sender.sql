-- ============================================================
-- V13: 意见反馈表 + tb_message 增加发送者列
-- 说明：tb_message 原本只有接收者 user_id，无法展示消息来源；
--       新增 sender_id 以支持「关注/点赞/评论」通知显示触发者。
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_feedback` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '提交用户id',
  `content`     VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '反馈内容',
  `contact`     VARCHAR(128) NULL COMMENT '联系方式（可选）',
  `type`        TINYINT(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '类型：0-其他 1-功能异常 2-体验建议',
  `status`      TINYINT(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0-待处理 1-已处理',
  `create_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_feedback_user` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户意见反馈表';

-- tb_message 增加 sender_id（幂等，参考 V2/V3 的 information_schema 守卫写法）
SET @cnt_sender = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'tb_message' AND column_name = 'sender_id');
SET @sql_sender = IF(@cnt_sender = 0,
  'ALTER TABLE `tb_message` ADD COLUMN `sender_id` BIGINT(20) UNSIGNED NULL COMMENT "发送者用户id（关注/点赞/评论触发者）" AFTER `user_id`;',
  'SELECT "sender_id in tb_message already exists";');
PREPARE stmt_sender FROM @sql_sender; EXECUTE stmt_sender; DEALLOCATE PREPARE stmt_sender;
