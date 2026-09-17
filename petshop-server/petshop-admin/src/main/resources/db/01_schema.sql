-- 宠物店小程序 · 数据库结构（MySQL 8.0；H2 MySQL 兼容模式用于测试）
-- 账务表铁律：balance_ledger / card_ledger / inventory_log / pay_order 只允许 INSERT 与状态列 UPDATE
--
-- 幂等约定（对应审查 P2-2）：本文件必须能被重复执行。
-- 表一律用 CREATE TABLE IF NOT EXISTS，索引一律用 CREATE INDEX IF NOT EXISTS。
-- 裸 CREATE INDEX 会让"同一进程里第二次加载 schema"在第一条索引处中断
-- （症状被折叠成 ApplicationContext failure threshold exceeded，本项目已踩过两次）。
-- CREATE INDEX IF NOT EXISTS 需要 MySQL 8.0.32+ / H2 2.x；生产库走 03~08_migration.sql 增量升级，
-- 不会自动执行本文件（spring.sql.init.mode=never），所以老版本 MySQL 不受影响。

CREATE TABLE IF NOT EXISTS member (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  openid VARCHAR(64) NOT NULL,
  phone VARCHAR(20),
  nickname VARCHAR(64),
  level TINYINT NOT NULL DEFAULT 1,
  balance DECIMAL(10,2) NOT NULL DEFAULT 0,
  frozen_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  cum_principal DECIMAL(10,2) NOT NULL DEFAULT 0,
  cum_gift DECIMAL(10,2) NOT NULL DEFAULT 0,
  total_paid DECIMAL(10,2) NOT NULL DEFAULT 0,
  status CHAR(1) NOT NULL DEFAULT '0',
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_member_openid UNIQUE (openid)
);

CREATE TABLE IF NOT EXISTS balance_ledger (
  id BIGINT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  biz_type VARCHAR(16) NOT NULL,
  principal_amt DECIMAL(10,2) NOT NULL DEFAULT 0,
  gift_amt DECIMAL(10,2) NOT NULL DEFAULT 0,
  balance_after DECIMAL(10,2),
  frozen_after DECIMAL(10,2),
  biz_no VARCHAR(32),
  remark VARCHAR(255),
  operator VARCHAR(32),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_ledger_member ON balance_ledger (member_id, id);
CREATE INDEX IF NOT EXISTS idx_ledger_bizno ON balance_ledger (biz_no);

CREATE TABLE IF NOT EXISTS pet (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  name VARCHAR(32) NOT NULL,
  type VARCHAR(8) NOT NULL,
  size VARCHAR(8),
  hair VARCHAR(8) NOT NULL,
  gender VARCHAR(8),
  birthday DATE,
  neutered TINYINT,
  weight DECIMAL(5,2),
  photos TEXT,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME,
  updated_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_pet_member ON pet (member_id, deleted);

CREATE TABLE IF NOT EXISTS pet_health_log (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  pet_id BIGINT NOT NULL,
  log_type VARCHAR(16) NOT NULL,
  log_date DATE NOT NULL,
  next_due DATE,
  weight_value DECIMAL(5,2),
  source VARCHAR(16) NOT NULL,
  biz_no VARCHAR(32),
  operator VARCHAR(32),
  remark VARCHAR(255),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_health_pet ON pet_health_log (pet_id, log_type, log_date);
-- 幂等兜底：同一来源单号 + 同一宠物 + 同一类型只允许一条（对应审查 P3-3）。
-- 结算重试 / 消息重放时写入端会撞这条唯一键，而不是默默多写一条让看板与趋势失真。
-- biz_no 为 NULL 的手工录入不受影响：唯一索引把 NULL 视为互不相同。
CREATE UNIQUE INDEX IF NOT EXISTS uk_health_biz ON pet_health_log (pet_id, log_type, biz_no);

CREATE TABLE IF NOT EXISTS spec (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  pet_type VARCHAR(8) NOT NULL,
  size VARCHAR(8),
  hair VARCHAR(8),
  name VARCHAR(32) NOT NULL,
  CONSTRAINT uk_spec UNIQUE (store_id, pet_type, size, hair)
);

CREATE TABLE IF NOT EXISTS service (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(64) NOT NULL,
  category VARCHAR(16) NOT NULL,
  duration_slots INT NOT NULL DEFAULT 1,
  capacity INT NOT NULL DEFAULT 3,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME
);

CREATE TABLE IF NOT EXISTS service_price (
  id BIGINT PRIMARY KEY,
  service_id BIGINT NOT NULL,
  spec_id BIGINT NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  CONSTRAINT uk_service_price UNIQUE (service_id, spec_id)
);

CREATE TABLE IF NOT EXISTS store_config (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL,
  name VARCHAR(64),
  cancel_hours INT NOT NULL DEFAULT 4,
  no_guarantee_enabled TINYINT NOT NULL DEFAULT 0,
  no_show_policy VARCHAR(16) NOT NULL DEFAULT 'RECORD',
  slot_capacity INT NOT NULL DEFAULT 3,
  bookable_days INT NOT NULL DEFAULT 14,
  business_hours VARCHAR(32) NOT NULL DEFAULT '09:00-19:00',
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_store_config UNIQUE (store_id)
);

-- 时段行懒创建；capacity = min(门店时段容量, 服务同时接待量) 创建时固化
CREATE TABLE IF NOT EXISTS time_slot (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  service_id BIGINT NOT NULL,
  slot_date DATE NOT NULL,
  start_time TIME NOT NULL,
  capacity INT NOT NULL,
  booked_count INT NOT NULL DEFAULT 0,
  created_at DATETIME,
  CONSTRAINT uk_time_slot UNIQUE (store_id, slot_date, start_time, service_id)
);

CREATE TABLE IF NOT EXISTS appointment (
  id BIGINT PRIMARY KEY,
  appt_no VARCHAR(32) NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  pet_id BIGINT NOT NULL,
  service_id BIGINT NOT NULL,
  spec_id BIGINT,
  slot_id BIGINT NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  guarantee_type VARCHAR(16) NOT NULL,
  guarantee_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  card_id BIGINT,
  status VARCHAR(16) NOT NULL,
  verify_code VARCHAR(8) NOT NULL,
  vaccine_pending TINYINT NOT NULL DEFAULT 0,
  cancel_deadline DATETIME,
  remark VARCHAR(255),
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_appt_no UNIQUE (appt_no),
  CONSTRAINT uk_appt_verify_code UNIQUE (verify_code)
);
CREATE INDEX IF NOT EXISTS idx_appt_member ON appointment (member_id, status);
CREATE INDEX IF NOT EXISTS idx_appt_slot ON appointment (slot_id, status);

CREATE TABLE IF NOT EXISTS card_type (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(64) NOT NULL,
  service_ids TEXT,
  spec_ids TEXT,
  times INT NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  valid_mode VARCHAR(8) NOT NULL DEFAULT 'MONTHS',
  valid_months INT NOT NULL DEFAULT 12,
  valid_until DATE,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
);

CREATE TABLE IF NOT EXISTS card (
  id BIGINT PRIMARY KEY,
  card_no VARCHAR(32) NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  card_type_id BIGINT NOT NULL,
  total_times INT NOT NULL,
  remain_times INT NOT NULL,
  pre_deduct_count INT NOT NULL DEFAULT 0,
  valid_until DATE NOT NULL,
  status VARCHAR(16) NOT NULL,
  paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  CONSTRAINT uk_card_no UNIQUE (card_no)
);
CREATE INDEX IF NOT EXISTS idx_card_member ON card (member_id);

CREATE TABLE IF NOT EXISTS card_ledger (
  id BIGINT PRIMARY KEY,
  card_id BIGINT NOT NULL,
  biz_type VARCHAR(16) NOT NULL,
  times INT NOT NULL,
  biz_no VARCHAR(32),
  operator VARCHAR(32),
  remark VARCHAR(255),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_card_ledger ON card_ledger (card_id, id);

CREATE TABLE IF NOT EXISTS pay_order (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(32) NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  biz_type VARCHAR(24) NOT NULL,
  biz_no VARCHAR(32),
  amount DECIMAL(10,2) NOT NULL,
  channel VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  wx_transaction_id VARCHAR(64),
  callback_raw TEXT,
  notify_count INT NOT NULL DEFAULT 0,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_pay_no UNIQUE (pay_no),
  CONSTRAINT uk_pay_wx_tx UNIQUE (wx_transaction_id)
);
CREATE INDEX IF NOT EXISTS idx_pay_bizno ON pay_order (biz_no);

CREATE TABLE IF NOT EXISTS member_level (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  level TINYINT NOT NULL,
  name VARCHAR(32) NOT NULL,
  threshold DECIMAL(10,2) NOT NULL DEFAULT 0,
  general_discount DECIMAL(3,2),
  service_discount TEXT,
  benefits TEXT,
  enabled TINYINT NOT NULL DEFAULT 1,
  CONSTRAINT uk_member_level UNIQUE (store_id, level)
);

CREATE TABLE IF NOT EXISTS recharge_tier (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  amount DECIMAL(10,2) NOT NULL,
  gift DECIMAL(10,2) NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS goods (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(64) NOT NULL,
  category VARCHAR(16),
  images TEXT,
  description TEXT,
  price_from DECIMAL(10,2),
  status VARCHAR(16) NOT NULL DEFAULT 'ON_SALE',
  created_at DATETIME,
  updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS sku (
  id BIGINT PRIMARY KEY,
  goods_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  stock INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inventory_log (
  id BIGINT PRIMARY KEY,
  sku_id BIGINT NOT NULL,
  biz_type VARCHAR(16) NOT NULL,
  qty INT NOT NULL,
  stock_after INT NOT NULL,
  biz_no VARCHAR(32),
  operator VARCHAR(32),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_inventory_sku ON inventory_log (sku_id, id);

-- 原本这里还有 sale_activity / sale_activity_item（特卖活动与选品）。
-- 2026-09-17 删除（对应审查 P3-5）：两个实体零引用、没有任何 Mapper/Service/Controller，
-- 订单计价也从未读过它们（MallOrderService 只按 SKU 现价累加）—— 留着只会让人以为
-- 「配了促销就生效」。将来要做促销，按当时的叠加/互斥规则重新设计表结构，
-- 而不是被这两张空表钉死。删表不写迁移：没有生产库依赖它们（demo 数据里也一条都没有）。

CREATE TABLE IF NOT EXISTS goods_order (
  id BIGINT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  total DECIMAL(10,2) NOT NULL,
  principal_paid DECIMAL(10,2) NOT NULL DEFAULT 0,
  gift_paid DECIMAL(10,2) NOT NULL DEFAULT 0,
  pay_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  -- 自提核销码（8 位数字：6 位序号 + 2 位校验位）。唯一索引是「同一码不会被核销两次」的最终防线
  verify_code VARCHAR(8) NOT NULL,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_order_no UNIQUE (order_no),
  CONSTRAINT uk_order_verify_code UNIQUE (verify_code)
);
CREATE INDEX IF NOT EXISTS idx_order_member ON goods_order (member_id, status);

CREATE TABLE IF NOT EXISTS order_item (
  id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  name VARCHAR(64),
  qty INT NOT NULL,
  price DECIMAL(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS settle_bill (
  id BIGINT PRIMARY KEY,
  settle_no VARCHAR(32) NOT NULL,
  appt_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  items TEXT,
  discount_desc TEXT,
  total DECIMAL(10,2) NOT NULL,
  manual_price DECIMAL(10,2),
  manual_reason VARCHAR(255),
  pay_type VARCHAR(16) NOT NULL,
  pay_detail TEXT,
  operator VARCHAR(32),
  created_at DATETIME,
  CONSTRAINT uk_settle_no UNIQUE (settle_no)
);

-- 退款单：口径为「退款只入会员可用余额」，审核通过即到账，状态机 APPLYING → REFUNDED / REJECTED / CANCELLED
CREATE TABLE IF NOT EXISTS refund_apply (
  id BIGINT PRIMARY KEY,
  refund_no VARCHAR(32) NOT NULL,
  store_id BIGINT NOT NULL DEFAULT 1,
  member_id BIGINT NOT NULL,
  biz_type VARCHAR(16) NOT NULL,
  biz_no VARCHAR(32) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  principal_amt DECIMAL(10,2) NOT NULL DEFAULT 0,
  gift_amt DECIMAL(10,2) NOT NULL DEFAULT 0,
  reason VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'APPLYING',
  auditor VARCHAR(32),
  audit_remark VARCHAR(255),
  -- 后台资金操作的幂等键（目前只有店长人工退款会填）：
  -- 同一操作人重试 / 双击时带着同一个键，靠唯一键把它挡在入账之前。
  -- 手填原因的人工退款没有外部单号可做幂等依据，只能由调用方给一个。
  idempotency_key VARCHAR(64),
  created_at DATETIME,
  updated_at DATETIME,
  finished_at DATETIME,
  CONSTRAINT uk_refund_no UNIQUE (refund_no),
  -- NULL 可重复（MySQL 与 H2 的唯一索引都把 NULL 视为互不相同），
  -- 所以抽查类退款单不传键也互不冲突，只有显式传了同一个键才算重放。
  CONSTRAINT uk_refund_idem UNIQUE (store_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_refund_member ON refund_apply (member_id, status);
CREATE INDEX IF NOT EXISTS idx_refund_audit ON refund_apply (status, id);
CREATE INDEX IF NOT EXISTS idx_refund_biz ON refund_apply (biz_type, biz_no);

-- ============ 退款到账提醒（店长端） ============

-- 店长订阅授权额度。微信订阅消息是「一次性授权」：店长每点一次「允许通知」才得到一次推送配额，
-- 推成功即消耗一次。所以必须按 openid + 模板累计剩余次数；次数为 0 时通知降级为「只发后台角标」，
-- 不影响退款主流程。
CREATE TABLE IF NOT EXISTS wx_subscribe_quota (
  id BIGINT PRIMARY KEY,
  openid VARCHAR(64) NOT NULL,
  template_id VARCHAR(64) NOT NULL,
  remain_count INT NOT NULL DEFAULT 0,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_sub_openid_tpl UNIQUE (openid, template_id)
);

-- 推送留痕。唯一索引 (biz_no, template_id) 是「同一笔退款不会重复推给店长」的防线：
-- 重复触发时 INSERT 撞唯一索引即跳过发送，避免店长被同一笔退款轰炸两次。
CREATE TABLE IF NOT EXISTS notify_log (
  id BIGINT PRIMARY KEY,
  openid VARCHAR(64),
  template_id VARCHAR(64) NOT NULL,
  biz_no VARCHAR(32) NOT NULL,
  -- SUCCESS / SKIPPED_NO_QUOTA / SKIPPED_NOT_CONFIGURED / FAILED
  status VARCHAR(32) NOT NULL,
  err_msg VARCHAR(255),
  created_at DATETIME,
  CONSTRAINT uk_notify_biz_tpl UNIQUE (biz_no, template_id)
);
CREATE INDEX IF NOT EXISTS idx_notify_created ON notify_log (created_at);

-- ============ 后台账号 ============

-- 后台操作账号。取代此前"一枚部署级共享令牌"的形态：
-- 共享令牌下请求里没有"谁在操作"的信息，退款审核人只能由前端自报，留痕没有审计效力。
-- password_hash 存 PasswordUtil 产出的自描述串（含算法、迭代次数、盐），不存明文也不存裸哈希。
-- 不建"默认账号"种子行：初始账号由启动引导按配置创建（petshop.admin.bootstrap-*），
-- 避免把一个所有人可见的默认口令写进版本库里。
CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  username VARCHAR(32) NOT NULL,
  display_name VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  -- MANAGER 店长 / STAFF 店员
  role VARCHAR(16) NOT NULL DEFAULT 'STAFF',
  -- ENABLED / DISABLED
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  -- 1 = 初始口令未改，此时除改密接口外一律拒绝（服务端强制，不靠前端自觉）
  must_change_pwd TINYINT NOT NULL DEFAULT 0,
  last_login_at DATETIME,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_admin_username UNIQUE (username)
);
CREATE INDEX IF NOT EXISTS idx_admin_status ON admin_user (status);

-- 配置变更审计：一行 = 一个字段的一次变化。
-- 配置对象（价目 / 卡类型 / 充值档位 / 会员等级 / 门店规则 / 商品价格）改动时不会立刻产生账目，
-- 却会改变之后每一笔账；没有这张表，"把猫洗澡从 120 改成 150"就无迹可查。
-- 按字段落行（而不是存 JSON 快照）是为了后台能直接列成「价格 120.00 → 150.00」，
-- 不需要前端解析 JSON，也不需要读的人自己找差异。
CREATE TABLE IF NOT EXISTS config_audit_log (
  id BIGINT PRIMARY KEY,
  -- GOODS / SKU / SERVICE / SERVICE_PRICE / CARD_TYPE / RECHARGE_TIER / MEMBER_LEVEL / STORE_CONFIG
  target_type VARCHAR(24) NOT NULL,
  target_id BIGINT NOT NULL,
  -- CREATE / UPDATE / STATUS / ADJUST
  action VARCHAR(16) NOT NULL,
  field_name VARCHAR(64),
  before_value VARCHAR(255),
  after_value VARCHAR(255),
  operator VARCHAR(32) NOT NULL,
  -- 库存盘点等"数字变了但没有单据"的场景必填
  remark VARCHAR(255),
  created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_config_audit_target ON config_audit_log (target_type, target_id, id);
CREATE INDEX IF NOT EXISTS idx_config_audit_time ON config_audit_log (created_at);

