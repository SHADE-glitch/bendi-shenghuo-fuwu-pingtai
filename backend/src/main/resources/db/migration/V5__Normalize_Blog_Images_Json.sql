-- V5: 修复历史错误清洗导致的 images JSON 异常形态
-- 场景：部分历史数据可能被写成 ["a.jpg,b.jpg"]（单元素+逗号串），
-- 需要规范为 ["a.jpg","b.jpg"]。

UPDATE tb_blog
SET images = CAST(
    CONCAT(
        '["',
        REPLACE(JSON_UNQUOTE(JSON_EXTRACT(images, '$[0]')), ',', '","'),
        '"]'
    ) AS JSON
)
WHERE images IS NOT NULL
  AND JSON_TYPE(images) = 'ARRAY'
  AND JSON_LENGTH(images) = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(images, '$[0]')) LIKE '%,%';
