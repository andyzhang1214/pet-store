-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更：配置变更审计 —— 给「改价 / 改档位 / 改折扣 / 改门店规则 / 盘点库存」留下可查的痕迹。
--
-- 为什么必须升级：配置域里这些对象有个共同点 —— 改了不会立刻产生一笔账，
-- 却会改变之后每一笔账。库存变动有 inventory_log、余额有 balance_ledger，
-- 而"把猫洗澡从 120 改成 150"这件事此前没有任何落点，出事时只能说"系统里就是 150"。
--
-- 表设计：一行 = 一个字段的一次变化（而不是一对象一 JSON 快照），
-- 这样后台可以直接列成「价格 120.00 → 150.00」，不需要前端解析、也不需要读的人自己找差异。

CREATE TABLE IF NOT EXISTS config_audit_log (
  id BIGINT PRIMARY KEY,
  target_type VARCHAR(24) NOT NULL,
  target_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL,
  field_name VARCHAR(64),
  before_value VARCHAR(255),
  after_value VARCHAR(255),
  -- 操作人由服务端从后台令牌解析（AdminContext），请求体里没有这个字段可填
  operator VARCHAR(32) NOT NULL,
  remark VARCHAR(255),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_config_audit_target ON config_audit_log (target_type, target_id, id);
CREATE INDEX IF NOT EXISTS idx_config_audit_time ON config_audit_log (created_at);

-- 本脚本不插入任何数据。默认值仍在各配置表自身的种子数据里（02_seed.sql），
-- 审计只记录"之后发生的变更"。
