-- Flyway migration: Add foreign key constraints to enforce referential integrity
-- Generated: 2026-08-15T07:57:00Z

-- Preconditions: ensure schema data is consistent before applying in production/staging
-- This migration assumes related rows already exist (no orphan rows).

-- tb_user_info.user_id -> tb_user.id
ALTER TABLE tb_user_info
  ADD CONSTRAINT fk_user_info_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- tb_voucher.shop_id -> tb_shop.id
ALTER TABLE tb_voucher
  ADD CONSTRAINT fk_voucher_shop FOREIGN KEY (shop_id) REFERENCES tb_shop(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- tb_voucher_order.user_id -> tb_user.id
ALTER TABLE tb_voucher_order
  ADD CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- tb_voucher_order.voucher_id -> tb_voucher.id
ALTER TABLE tb_voucher_order
  ADD CONSTRAINT fk_order_voucher FOREIGN KEY (voucher_id) REFERENCES tb_voucher(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- tb_seckill_voucher.voucher_id -> tb_voucher.id
ALTER TABLE tb_seckill_voucher
  ADD CONSTRAINT fk_seckill_voucher_voucher FOREIGN KEY (voucher_id) REFERENCES tb_voucher(id) ON DELETE CASCADE ON UPDATE CASCADE;

-- tb_sign.user_id -> tb_user.id
ALTER TABLE tb_sign
  ADD CONSTRAINT fk_sign_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE ON UPDATE CASCADE;
