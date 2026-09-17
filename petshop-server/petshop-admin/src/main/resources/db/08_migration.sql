-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更对应 2026-09-17 代码审查的 P2-1 / P2-2 / P3-3：
--   ① 人工退款幂等键（refund_apply.idempotency_key + 唯一约束）
--   ② 健康档案幂等兜底（pet_health_log 按 宠物+类型+来源单号 唯一）
--   ③ 迁移版本账本（schema_version），让"哪些迁移已经跑过"有据可查
--
-- ============================ 可重放模板（新迁移照抄） ============================
-- ① 加列 / 改列（MySQL 8.0 无 IF NOT EXISTS）：
--      SET @ddl := (SELECT IF(COUNT(*) = 0,
--                    'ALTER TABLE t ADD COLUMN c INT NOT NULL DEFAULT 0', 'DO 0')
--                   FROM information_schema.COLUMNS
--                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't' AND COLUMN_NAME = 'c');
--      PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- ② 加索引：直接写 CREATE INDEX IF NOT EXISTS（MySQL 8.0.32+ / H2 2.x）。
-- ③ 删索引：先查 information_schema.STATISTICS 判断存在再 DROP。
-- 目标是「跑第二遍不报错、也不做无意义的 DDL」。本项目已经因为脚本不可重放
-- 被索引撞过一次（症状被折叠成 ApplicationContext failure threshold exceeded），别再欠这个债。
-- ==================================================================================

-- ---------------------------------------------------------------- ① 人工退款幂等键
--
-- 为什么要加：店长人工退款没有外部业务单号，每次调用都现生成一个退款单号，
-- 而 refundToBalance 的防重是"按 biz_no 查已有流水"——单号每次都是新的，防重永远不命中。
-- 于是手抖双击 / 浏览器重试 = 同一会员余额加两份，而且账面上完全看不出来。
-- 有了这个键，重放请求会撞 uk_refund_idem 被挡在入账之前（RefundService 里回放首次结果）。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE refund_apply ADD COLUMN idempotency_key VARCHAR(64) NULL',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_apply'
    AND COLUMN_NAME = 'idempotency_key'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一键落在 (store_id, idempotency_key)：幂等键由客户端生成，只在门店内唯一即可。
-- NULL 可重复，历史退款单与不传键的新单互不冲突。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE refund_apply ADD CONSTRAINT uk_refund_idem UNIQUE (store_id, idempotency_key)',
            'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_apply'
    AND INDEX_NAME = 'uk_refund_idem'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- ② 健康档案幂等
--
-- 结算会按服务分类写健康档案。同一张结算单重放（重试 / 消息重放）本不该多写一条，
-- 但 pet_health_log 此前没有任何唯一约束兜底，多写就让看板的"下次到期"与趋势失真。
-- (pet_id, log_type, biz_no) 唯一：biz_no 为 NULL 的手工录入不受影响（NULL 互不相同）。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'CREATE UNIQUE INDEX uk_health_biz ON pet_health_log (pet_id, log_type, biz_no)',
            'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pet_health_log'
    AND INDEX_NAME = 'uk_health_biz'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------- ③ 迁移版本账本
--
-- 存在的意义：迁移脚本按文件名排序执行时，没有任何地方记录"跑过哪些"。
-- 本文件把 01~07 记为已应用（它们都已存在于当前库，属于对新库也无害的幂等脚本），
-- 之后每加一个迁移，就在文件末尾 INSERT 一行自己的版本号。
CREATE TABLE IF NOT EXISTS schema_version (
  version VARCHAR(32) PRIMARY KEY,
  note VARCHAR(255),
  applied_at DATETIME NOT NULL
);

INSERT IGNORE INTO schema_version (version, note, applied_at) VALUES
  ('01', '基线建表（01_schema.sql）', NOW()),
  ('02', '配置域种子数据（02_seed.sql）', NOW()),
  ('03', '宠物逻辑删除', NOW()),
  ('04', '退款只入余额 / 次卡实付金额', NOW()),
  ('05', '自提核销码 / 店长订阅消息', NOW()),
  ('06', '后台账号体系', NOW()),
  ('07', '配置变更审计', NOW()),
  ('08', '人工退款幂等键 / 健康档案幂等 / 迁移版本账本', NOW());

-- 本脚本不插入任何业务数据。
