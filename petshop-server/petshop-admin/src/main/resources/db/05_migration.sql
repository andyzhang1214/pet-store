-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更：① 商品订单链路（自提核销码）② 退款到账提醒（店长订阅消息额度与推送留痕）
--
-- 可重放约定见 03_migration.sql 顶部说明（对应审查 P2-2）。

-- ================================================================ ① 商品订单：自提核销码

-- 先加可空列，回填后再收紧为 NOT NULL —— 已有库中存在历史订单，直接加 NOT NULL 列会失败。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE goods_order ADD COLUMN verify_code VARCHAR(8) NULL',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_order' AND COLUMN_NAME = 'verify_code'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史订单补码。8 位 = 6 位序号 + 2 位校验位，必须满足「校验位 == 序号 % 97」，
-- 否则会被应用层 VerifyCodeUtil.validate 判为非法码、无法核销。
-- body 在派生表里只算一次再 JOIN，保证同一个 id 的序号前后一致（直接写两遍 RAND() 会取到不同值）。
-- WHERE verify_code IS NULL：重放时只补还没码的行，不会覆盖已发出的核销码。
UPDATE goods_order o
JOIN (SELECT id, FLOOR(RAND() * 1000000) AS body FROM goods_order) t ON o.id = t.id
SET o.verify_code = CONCAT(LPAD(t.body, 6, '0'), LPAD(t.body % 97, 2, '0'))
WHERE o.verify_code IS NULL;

-- 若历史库确实没有订单数据，上面这条为空操作，可直接收紧约束。
-- 已经是 NOT NULL 时跳过，避免重放时做一次无意义的全表 MODIFY（大表上会锁很久）。
SET @ddl := (
  SELECT IF(IS_NULLABLE = 'YES',
            'ALTER TABLE goods_order MODIFY COLUMN verify_code VARCHAR(8) NOT NULL',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_order' AND COLUMN_NAME = 'verify_code'
  LIMIT 1
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一索引：同一核销码不会被核销两次的最终防线。
-- 回填若因随机数碰撞失败（概率极低），重跑一次本文件即可。
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_verify_code ON goods_order (verify_code);

-- ================================================================ ② 店长订阅消息（退款到账提醒）

-- 微信订阅消息是一次性授权：店长每点一次「允许通知」才得到一次推送配额。
-- remain_count 归零后通知自动降级为「只发后台角标」，不影响退款主流程。
CREATE TABLE IF NOT EXISTS wx_subscribe_quota (
  id BIGINT PRIMARY KEY,
  openid VARCHAR(64) NOT NULL,
  template_id VARCHAR(64) NOT NULL,
  remain_count INT NOT NULL DEFAULT 0,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_sub_openid_tpl UNIQUE (openid, template_id)
);

-- 推送留痕，(biz_no, template_id) 唯一 —— 同一笔退款只会推给店长一次。
CREATE TABLE IF NOT EXISTS notify_log (
  id BIGINT PRIMARY KEY,
  openid VARCHAR(64),
  template_id VARCHAR(64) NOT NULL,
  biz_no VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  err_msg VARCHAR(255),
  created_at DATETIME,
  CONSTRAINT uk_notify_biz_tpl UNIQUE (biz_no, template_id)
);
CREATE INDEX IF NOT EXISTS idx_notify_created ON notify_log (created_at);

