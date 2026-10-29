-- V4: 最终 JSON 清洗与结构确认
-- 移除不支持的 IF NOT EXISTS 语法，仅保留核心转换

-- 1. 笔记表改造
ALTER TABLE `tb_blog` MODIFY COLUMN `images` JSON;

-- 2. 数据清洗 (逗号转 JSON 数组)
UPDATE tb_blog 
SET images = JSON_ARRAY(REPLACE(images, ',', '","')) 
WHERE images IS NOT NULL 
  AND images != '' 
  AND images NOT LIKE '[%';

-- 3. 补齐逻辑删除字段（如果之前的 V3 已执行成功部分，这里会通过，若失败请手动检查）
-- 我们移除 ADD COLUMN 部分，改为在 V3 中已经声明好的逻辑。
-- 如果此处仍然报错，说明 V3 没跑完，建议手动在容器内补齐。
