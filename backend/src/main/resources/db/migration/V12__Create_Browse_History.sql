-- ============================================================
-- V12: 浏览历史表
-- 按 (user_id, target_type, target_id) 唯一去重，update_time 记录最近浏览时间
-- target_type: 1-店铺 2-笔记
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_browse_history` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户id',
  `target_type` TINYINT(1) UNSIGNED NOT NULL COMMENT '目标类型：1-店铺 2-笔记',
  `target_id`   BIGINT UNSIGNED NOT NULL COMMENT '目标id',
  `create_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次浏览时间',
  `update_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近浏览时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_user_time` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户浏览历史表';
