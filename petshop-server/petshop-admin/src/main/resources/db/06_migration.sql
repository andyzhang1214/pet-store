-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更：后台账号体系 —— 从「部署级共享令牌」升级为「账号 + 角色」，
--           使退款审核、人工退款、次卡调整、资金配置这些动作有可追溯的操作人。
--
-- 为什么必须升级：共享令牌下请求里没有"谁在操作"的信息，后端只能让前端在请求体里
-- 自报 operator。那不是留痕，是让操作者自己声明自己是谁，出了资金纠纷没有证据价值。

CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT PRIMARY KEY,
  store_id BIGINT NOT NULL DEFAULT 1,
  username VARCHAR(32) NOT NULL,
  display_name VARCHAR(32) NOT NULL,
  -- PasswordUtil 产出的自描述串：pbkdf2-sha256$<迭代次数>$<盐>$<摘要>
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'STAFF',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  must_change_pwd TINYINT NOT NULL DEFAULT 0,
  last_login_at DATETIME,
  created_at DATETIME,
  updated_at DATETIME,
  CONSTRAINT uk_admin_username UNIQUE (username)
);
CREATE INDEX IF NOT EXISTS idx_admin_status ON admin_user (status);

-- 本脚本刻意不插入任何账号行。
-- 初始店长账号由启动引导（AdminAccountBootstrap）在表为空时按配置创建：
--   PETSHOP_ADMIN_BOOTSTRAP_USERNAME / PETSHOP_ADMIN_BOOTSTRAP_PASSWORD
-- 原因：把口令哈希写进版本库等于把一个所有人都能看到的初始口令发给全世界，
-- 而且改起来还得再发一次迁移脚本。配置化之后，口令由部署者自己定、留在环境变量里，
-- 且新账号被标记 must_change_pwd=1，首次登录必须改掉。
--
-- 引导后的第一件事：登录后台 → 「账号」页 → 改掉初始口令、按需增删店员账号。
