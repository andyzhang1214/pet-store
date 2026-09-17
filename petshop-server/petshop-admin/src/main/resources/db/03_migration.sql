-- 增量升级脚本（已有数据库使用；全新安装直接跑 01_schema.sql 即可）
-- 本次变更对应代码审查修复：M-7 宠物逻辑删除
--
-- 可重放约定（对应审查 P2-2）：MySQL 8.0 的 ADD COLUMN / DROP INDEX 不支持 IF NOT EXISTS，
-- 重复执行会报 1060 / 1091。早期版本写的是"属预期，忽略即可"——但一旦被按文件名排序的
-- 迁移工具接管、或失败后重跑，脚本会在中途断掉、后面的语句一条都不执行。
-- 现在统一改成「查 information_schema 判断后再动态执行」，重放时该跳过就跳过。
-- 新迁移照抄这个写法即可（模板注释见 08_migration.sql）。

-- pet 表新增逻辑删除标记。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE pet ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0',
            'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pet' AND COLUMN_NAME = 'deleted'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 索引调整为 (member_id, deleted)，配合 MyBatis-Plus 逻辑删除自动追加的 deleted = 0 条件。
-- 判据是「老索引只有 1 列」而不是"索引在不在"：老索引在就删掉重建，
-- 已经是 2 列的新索引整体跳过，重放不会白删白建。
SET @ddl := (
  SELECT IF(COUNT(*) = 1, 'DROP INDEX idx_pet_member ON pet', 'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pet'
    AND INDEX_NAME = 'idx_pet_member'
  GROUP BY INDEX_NAME
  HAVING COUNT(*) = 1
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE INDEX IF NOT EXISTS idx_pet_member ON pet (member_id, deleted);
