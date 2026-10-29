-- V7__Seed_BaseData.sql
-- 占位：大规模数据请使用 scripts/seed_generator.py 生成的 SQL（scripts/generated_seed.sql），并在执行前确认目标 DB 与备份。
-- 说明：本文件为 Flyway 可执行的轻量级示例，建议将大规模插入放到独立生成的 SQL 文件中以免阻塞迁移。

-- 示例：插入少量基础数据（若使用自动生成器，请忽略本节）
INSERT IGNORE INTO tb_shop_type (id, name, icon, sort, create_time, update_time) VALUES
(1, '美食', '/types/ms.png', 1, NOW(), NOW()),
(2, 'KTV', '/types/KTV.png', 2, NOW(), NOW());

-- 如果使用脚本生成的数据，请在宿主机运行：
-- python3 scripts/seed_generator.py --scale medium --out scripts/generated_seed.sql
-- 然后在目标 DB 上执行：
-- docker compose exec mysql sh -c "mysql -uroot -prootpassword hmdp < /path/to/scripts/generated_seed.sql"
