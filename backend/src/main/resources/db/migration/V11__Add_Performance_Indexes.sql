-- V11__Add_Performance_Indexes.sql
-- Performance optimization indexes for order and shop queries

-- 1. Order status queries (frequently filtered by status)
CREATE INDEX idx_order_status ON tb_voucher_order(status);

-- 2. Composite index for user's orders (most common query pattern)
CREATE INDEX idx_order_user_status ON tb_voucher_order(user_id, status);

-- 3. Order creation time queries (for pagination)
CREATE INDEX idx_order_create_time ON tb_voucher_order(create_time);

-- 4. Composite index for mine list query
CREATE INDEX idx_order_user_time ON tb_voucher_order(user_id, create_time);

-- 5. Seckill voucher time window queries
CREATE INDEX idx_seckill_time ON tb_seckill_voucher(begin_time, end_time);

-- 6. Shop geo queries (if city column exists)
-- Note: Requires adding city column first (see V10 migration)
CREATE INDEX idx_shop_type_city ON tb_shop(type_id, city);

-- 7. Blog queries (by user or shop)
CREATE INDEX idx_blog_user ON tb_blog(user_id);
CREATE INDEX idx_blog_shop ON tb_blog(shop_id);

-- 8. Message unread count queries
CREATE INDEX idx_message_user_read ON tb_message(user_id, is_read);
