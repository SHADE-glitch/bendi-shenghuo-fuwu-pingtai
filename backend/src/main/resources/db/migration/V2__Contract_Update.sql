-- 确保订单唯一性：一人一单（幂等）
SET @cnt = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_user_voucher');
SET @sql = IF(@cnt=0, 'ALTER TABLE `tb_voucher_order` ADD UNIQUE INDEX `uk_user_voucher` (`user_id`, `voucher_id`);', 'SELECT "uk_user_voucher already exists";');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 确保关注唯一性（幂等）
SET @cnt2 = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_follow' AND index_name='uk_user_follow');
SET @sql2 = IF(@cnt2=0, 'ALTER TABLE `tb_follow` ADD UNIQUE INDEX `uk_user_follow` (`user_id`, `follow_user_id`);', 'SELECT "uk_user_follow already exists";');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
