-- ============================================================================
-- 演示数据（仅本地联调 / 截图用，默认不加载）
--
-- 为什么单独一个文件而不是塞进 02_seed.sql：
--   02_seed.sql 是配置域的基础数据（服务、规格、价目、卡型、档位、门店规则），
--   测试用例会断言它造成的初始状态。这里放的是「会员 / 宠物 / 健康档案 / 次卡」
--   这类会随使用不断增长的<b>业务数据</b>——混在一起后，任何一次跑测试都会被
--   这几位虚构会员干扰（比如"没有已逾期的宠物"这条断言会直接失败）。
--
-- 加载方式（配合 spring.sql.init.data-locations 覆盖）：
--   PETSHOP_SQL_DATA=classpath:db/02_seed.sql,classpath:db/09_demo.sql
--
-- 日期用 DATEADD 相对今天计算，而不是写死字面量 ——
-- 写死的话，这份数据过两周就全部变成「已逾期」，看板的三种状态就只剩一种了。
-- 因此本文件依赖 H2 的内置函数，不保证能在 MySQL 上直接执行。
-- ============================================================================

-- ------------------------------------------------------------------ 会员
INSERT INTO member (id, store_id, openid, phone, nickname, level, balance, frozen_amount,
                    cum_principal, cum_gift, total_paid, status, created_at, updated_at) VALUES
(301, 1, 'demo-openid-301', '13800000001', '林小满', 2, 550.00,   0.00, 500.00,  50.00, 1580.00, '0', DATEADD('DAY', -420, CURRENT_DATE), CURRENT_DATE),
(302, 1, 'demo-openid-302', '13800000002', '陈大伟', 3, 1150.00, 120.00, 1000.00, 150.00, 3260.00, '0', DATEADD('DAY', -310, CURRENT_DATE), CURRENT_DATE),
(303, 1, 'demo-openid-303', '13800000003', '苏晴',   1, 200.00,   0.00,  200.00,   0.00,  480.00, '0', DATEADD('DAY', -150, CURRENT_DATE), CURRENT_DATE),
(304, 1, 'demo-openid-304', '13800000004', '何东',   1,   0.00,   0.00,    0.00,   0.00,   90.00, '0', DATEADD('DAY',  -60, CURRENT_DATE), CURRENT_DATE);

-- ------------------------------------------------------------------ 宠物
INSERT INTO pet (id, store_id, member_id, name, type, size, hair, gender, birthday, neutered, weight, deleted, created_at, updated_at) VALUES
(401, 1, 301, '团子', '猫', '小型', '长毛', '母', DATEADD('YEAR', -3, CURRENT_DATE), 1, 4.20, 0, DATEADD('DAY', -400, CURRENT_DATE), CURRENT_DATE),
(402, 1, 301, '布丁', '猫', '小型', '短毛', '公', DATEADD('YEAR', -1, CURRENT_DATE), 0, 3.10, 0, DATEADD('DAY', -200, CURRENT_DATE), CURRENT_DATE),
(403, 1, 302, '大黄', '狗', '大型', '短毛', '公', DATEADD('YEAR', -5, CURRENT_DATE), 1, 28.60, 0, DATEADD('DAY', -300, CURRENT_DATE), CURRENT_DATE),
(404, 1, 303, '雪球', '猫', '中型', '长毛', '母', DATEADD('YEAR', -2, CURRENT_DATE), 1, 5.40, 0, DATEADD('DAY', -140, CURRENT_DATE), CURRENT_DATE),
(405, 1, 304, '豆豆', '狗', '小型', '短毛', '母', DATEADD('MONTH', -8, CURRENT_DATE), 0, 6.80, 0, DATEADD('DAY', -55, CURRENT_DATE), CURRENT_DATE);

-- ------------------------------------------------------- 健康档案（疫苗 / 驱虫）
-- 覆盖三种状态：已逾期（next_due 在过去）、临近到期（30 天窗口内）、已排期（窗口外）。
-- 豆豆刻意不写任何健康记录，用来验证详情页与看板对「无记录」的处理。
INSERT INTO pet_health_log (id, store_id, pet_id, log_type, log_date, next_due, weight_value, source, operator, remark, created_at) VALUES
-- 团子：疫苗已逾期 40 天，驱虫 5 天后到期
(501, 1, 401, 'VACCINE', DATEADD('DAY', -405, CURRENT_DATE), DATEADD('DAY', -40, CURRENT_DATE), NULL, 'STAFF', '店长', '猫三联加强针', DATEADD('DAY', -405, CURRENT_DATE)),
(502, 1, 401, 'WORM',    DATEADD('DAY',  -95, CURRENT_DATE), DATEADD('DAY',   5, CURRENT_DATE), NULL, 'STAFF', '店长', '体内外驱虫',   DATEADD('DAY',  -95, CURRENT_DATE)),
-- 布丁：疫苗 20 天后到期
(503, 1, 402, 'VACCINE', DATEADD('DAY', -345, CURRENT_DATE), DATEADD('DAY',  20, CURRENT_DATE), NULL, 'STAFF', '店长', '猫三联首免完成', DATEADD('DAY', -345, CURRENT_DATE)),
-- 大黄：疫苗逾期 3 天，驱虫还在窗口外（不该出现在看板上）
(504, 1, 403, 'VACCINE', DATEADD('DAY', -368, CURRENT_DATE), DATEADD('DAY',  -3, CURRENT_DATE), NULL, 'STAFF', '店长', '狂犬疫苗',     DATEADD('DAY', -368, CURRENT_DATE)),
(505, 1, 403, 'WORM',    DATEADD('DAY',  -30, CURRENT_DATE), DATEADD('DAY',  60, CURRENT_DATE), NULL, 'STAFF', '店长', '体内驱虫',     DATEADD('DAY',  -30, CURRENT_DATE)),
-- 雪球：驱虫 2 天后到期（最紧急的一只，应该排在看板最前面）
(506, 1, 404, 'WORM',    DATEADD('DAY',  -88, CURRENT_DATE), DATEADD('DAY',   2, CURRENT_DATE), NULL, 'STAFF', '店长', '体外驱虫',     DATEADD('DAY',  -88, CURRENT_DATE));

-- ------------------------------------------------------------------ 次卡
INSERT INTO card (id, card_no, store_id, member_id, card_type_id, total_times, remain_times,
                  pre_deduct_count, valid_until, status, paid_amount) VALUES
(601, 'C202608010001', 1, 301, 201, 10, 7,  0, DATEADD('MONTH', 10, CURRENT_DATE), 'USING', 980.00),
(602, 'C202607150002', 1, 302, 202, 10, 10, 1, DATEADD('MONTH',  9, CURRENT_DATE), 'USING', 2000.00);

-- ------------------------------------------------------ 次卡流水（与卡内剩余次数自洽）
-- 601：购卡 +10，三次到店核销各 -1 → 剩余 7（total 10，已用 3）。
-- 602：购卡 +10，无核销；但有一次未到店的预约仍处在「预扣」状态 ——
--      预扣不从 remain_times 里扣，而是单独记在 pre_deduct_count，
--      所以这张卡是「剩余 10 次、其中 1 次被预扣」。这正是列表里要同时显示两个数的原因。
INSERT INTO card_ledger (id, card_id, biz_type, times, biz_no, operator, remark, created_at) VALUES
(701, 601, 'PURCHASE',    10, NULL,        '店长', '购买猫洗护次卡·10 次',            DATEADD('DAY', -45, CURRENT_DATE)),
(703, 601, 'CONFIRM',     -1, 'APPT20260810001', '店长',   '到店核销',                DATEADD('DAY', -30, CURRENT_DATE)),
(704, 601, 'CONFIRM',     -1, 'APPT20260820002', '店长',   '到店核销',                DATEADD('DAY', -18, CURRENT_DATE)),
(705, 601, 'CONFIRM',     -1, 'APPT20260901003', '店长',   '到店核销',                DATEADD('DAY',  -6, CURRENT_DATE)),
(706, 602, 'PURCHASE',    10, NULL,        '店长', '购买狗美容次卡·10 次',            DATEADD('DAY', -62, CURRENT_DATE)),
(707, 602, 'PRE_DEDUCT',  -1, 'APPT20260916004', 'SYSTEM', '预约预扣（未到店）',      DATEADD('DAY',  -1, CURRENT_DATE));
