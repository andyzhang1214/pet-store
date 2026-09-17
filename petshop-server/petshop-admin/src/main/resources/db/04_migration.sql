-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更：退款只入余额（refund_apply 补字段 + card 增加购买实付金额）
--
-- 可重放约定见 03_migration.sql 顶部说明（对应审查 P2-2）。

-- ① 次卡增加"购买实付金额"。
--    退款必须按顾客实付金额退，而不是按 card_type.price —— 卡类型改价后两者会不一致，
--    用现行价退老卡会多退、退新卡会少退。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE card ADD COLUMN paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'card' AND COLUMN_NAME = 'paid_amount'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据回填：购卡时未落价格，只能按当时的卡类型现行价近似（若期间改过价，请人工核对这批卡）。
UPDATE card SET paid_amount = COALESCE((SELECT ct.price FROM card_type ct WHERE ct.id = card.card_type_id), 0)
WHERE paid_amount = 0;

-- ② 退款单补字段：入余额的金额拆分与终态时间。
--    三列拆成三条 guarded ALTER：合成一条的话，只要其中一列已存在整条就失败，
--    重放时无法只跳过缺失的那一列。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE refund_apply ADD COLUMN principal_amt DECIMAL(10,2) NOT NULL DEFAULT 0',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_apply' AND COLUMN_NAME = 'principal_amt'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE refund_apply ADD COLUMN gift_amt DECIMAL(10,2) NOT NULL DEFAULT 0',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_apply' AND COLUMN_NAME = 'gift_amt'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE refund_apply ADD COLUMN finished_at DATETIME NULL',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_apply' AND COLUMN_NAME = 'finished_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ 退款单查询索引：会员侧按状态筛、后台按待审队列拉取、以及按来源单据反查是否已申请过。
CREATE INDEX IF NOT EXISTS idx_refund_member ON refund_apply (member_id, status);
CREATE INDEX IF NOT EXISTS idx_refund_audit ON refund_apply (status, id);
CREATE INDEX IF NOT EXISTS idx_refund_biz ON refund_apply (biz_type, biz_no);
