-- V3: 逻辑删除字段 + tb_blog.images 改 JSON 类型 + 数据清洗
-- 目标：统一 JSON 契约，引入逻辑删除

-- 1. 笔记表改造
SET @cnt1 = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_blog' AND column_name='is_deleted');
SET @sql1 = IF(@cnt1=0, 'ALTER TABLE `tb_blog` ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT "逻辑删除: 0正常 1已删";', 'SELECT "is_deleted in tb_blog already exists";');
PREPARE stmt1 FROM @sql1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

ALTER TABLE `tb_blog` MODIFY COLUMN `images` JSON COMMENT '图片列表JSON数组（最多9张）';

-- 2. 数据清洗：将旧有的逗号分隔字符串 "a.jpg,b.jpg" 转换为 JSON 数组 ["a.jpg","b.jpg"]
UPDATE tb_blog 
SET images = JSON_ARRAY(REPLACE(images, ',', '","')) 
WHERE images IS NOT NULL 
  AND images != '' 
  AND images NOT LIKE '[%';

-- 3. 关注表改造
SET @cnt2 = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_follow' AND column_name='is_deleted');
SET @sql2 = IF(@cnt2=0, 'ALTER TABLE `tb_follow` ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT "逻辑删除: 0正常 1已删";', 'SELECT "is_deleted in tb_follow already exists";');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- 4. 订单表改造
SET @cnt3 = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND column_name='is_deleted');
SET @sql3 = IF(@cnt3=0, 'ALTER TABLE `tb_voucher_order` ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT "逻辑删除: 0正常 1已删";', 'SELECT "is_deleted in tb_voucher_order already exists";');
PREPARE stmt3 FROM @sql3; EXECUTE stmt3; DEALLOCATE PREPARE stmt3;

SET @cnt4 = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_user_voucher');
SET @sql4 = IF(@cnt4=0, 'ALTER TABLE `tb_voucher_order` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);', 'SELECT "idx_user_voucher already exists";');
PREPARE stmt4 FROM @sql4; EXECUTE stmt4; DEALLOCATE PREPARE stmt4;
