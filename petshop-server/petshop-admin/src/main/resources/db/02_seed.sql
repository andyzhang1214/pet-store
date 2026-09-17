-- 种子数据（store_id = 1）

INSERT INTO store_config (id, store_id, name, cancel_hours, no_guarantee_enabled, no_show_policy, slot_capacity, bookable_days, business_hours, created_at, updated_at)
VALUES (1, 1, '爪爪星球宠物生活馆', 4, 0, 'RECORD', 3, 14, '09:00-19:00', NOW(), NOW());

INSERT INTO spec (id, store_id, pet_type, size, hair, name) VALUES
(11, 1, 'CAT', NULL, 'SHORT', '猫·短毛'),
(12, 1, 'CAT', NULL, 'MEDIUM', '猫·中长毛'),
(13, 1, 'CAT', NULL, 'LONG', '猫·长毛'),
(14, 1, 'DOG', 'SMALL', 'SHORT', '狗·小型·短毛'),
(15, 1, 'DOG', 'SMALL', 'LONG', '狗·小型·长毛'),
(16, 1, 'DOG', 'MEDIUM', 'SHORT', '狗·中型·短毛'),
(17, 1, 'DOG', 'MEDIUM', 'LONG', '狗·中型·长毛'),
(18, 1, 'DOG', 'LARGE', 'SHORT', '狗·大型·短毛');

INSERT INTO service (id, store_id, name, category, duration_slots, capacity, status, created_at) VALUES
(101, 1, '基础洗护套餐', 'WASH', 1, 3, 'ENABLED', NOW()),
(102, 1, '精致美容', 'GROOM', 2, 2, 'ENABLED', NOW()),
(103, 1, '药浴 SPA', 'SPA', 2, 3, 'ENABLED', NOW()),
(104, 1, '疫苗接种', 'VACCINE', 1, 3, 'ENABLED', NOW()),
(105, 1, '体内外驱虫', 'WORM', 1, 3, 'ENABLED', NOW());

INSERT INTO service_price (id, service_id, spec_id, price) VALUES
(10011, 101, 11, 120.00), (10012, 101, 12, 150.00), (10013, 101, 13, 180.00),
(10014, 101, 14, 130.00), (10015, 101, 15, 150.00), (10016, 101, 16, 160.00),
(10017, 101, 17, 180.00), (10018, 101, 18, 200.00),
(10211, 102, 11, 260.00), (10212, 102, 12, 320.00), (10213, 102, 13, 380.00),
(10214, 102, 14, 280.00), (10215, 102, 15, 340.00), (10216, 102, 16, 360.00),
(10217, 102, 17, 420.00), (10218, 102, 18, 480.00),
(10311, 103, 11, 200.00), (10312, 103, 12, 240.00), (10313, 103, 13, 280.00),
(10314, 103, 14, 220.00), (10315, 103, 15, 260.00), (10316, 103, 16, 280.00),
(10317, 103, 17, 320.00), (10318, 103, 18, 360.00),
(10411, 104, 11, 60.00), (10412, 104, 12, 60.00), (10413, 104, 13, 60.00),
(10414, 104, 14, 80.00), (10415, 104, 15, 80.00), (10416, 104, 16, 80.00),
(10417, 104, 17, 80.00), (10418, 104, 18, 80.00),
(10511, 105, 11, 40.00), (10512, 105, 12, 40.00), (10513, 105, 13, 40.00),
(10514, 105, 14, 50.00), (10515, 105, 15, 50.00), (10516, 105, 16, 50.00),
(10517, 105, 17, 50.00), (10518, 105, 18, 50.00);

INSERT INTO member_level (id, store_id, level, name, threshold, general_discount, service_discount, benefits, enabled) VALUES
(1, 1, 1, '普通会员', 0, 1.00, '{}', '{}', 1),
(2, 1, 2, '银爪会员', 1000.00, 0.95, '{"GROOM":0.92}', '{"birthday_gift":true}', 1),
(3, 1, 3, '金爪会员', 3000.00, 0.88, '{"GROOM":0.80,"WASH":0.85}', '{"birthday_gift":true,"free_deworming":1}', 1);

INSERT INTO recharge_tier (id, store_id, amount, gift, enabled) VALUES
(1, 1, 300.00, 30.00, 1),
(2, 1, 500.00, 80.00, 1),
(3, 1, 1000.00, 200.00, 1);

INSERT INTO card_type (id, store_id, name, service_ids, spec_ids, times, price, valid_mode, valid_months, status) VALUES
(201, 1, '猫洗护次卡·10 次', '[101]', '[11,12,13]', 10, 980.00, 'MONTHS', 12, 'ENABLED'),
(202, 1, '狗美容次卡·10 次', '[102]', '[14,15,16,17,18]', 10, 2000.00, 'MONTHS', 12, 'ENABLED');

INSERT INTO goods (id, store_id, name, category, images, description, price_from, status, created_at, updated_at) VALUES
(301, 1, '宠物香氛沐浴露 500ml', '洗护', NULL, '温和低敏，留香 7 天', 89.00, 'ON_SALE', NOW(), NOW()),
(302, 1, '冻干鸡肉粒 100g', '零食', NULL, '纯鸡肉冻干，零添加，训练奖励首选', 39.00, 'ON_SALE', NOW(), NOW()),
(303, 1, '豆腐猫砂 6L', '用品', NULL, '可冲厕所，低粉尘，结团快', 45.00, 'ON_SALE', NOW(), NOW()),
(304, 1, '宠物洁耳液 120ml', '护理', NULL, '温和清洁耳道，缓解异味', 56.00, 'ON_SALE', NOW(), NOW()),
(305, 1, '狗狗磨牙棒 3 支装', '零食', NULL, '耐咬洁齿，牛皮与鸡肉双拼', 32.00, 'ON_SALE', NOW(), NOW()),
(306, 1, '宠物益生菌 60 粒', '保健', NULL, '调理肠胃，缓解软便', 128.00, 'ON_SALE', NOW(), NOW());

INSERT INTO sku (id, goods_id, name, price, stock) VALUES
(3011, 301, '燕麦香型 500ml', 89.00, 100),
(3012, 301, '洋甘菊香型 500ml', 89.00, 60),
(3021, 302, '原味 100g', 39.00, 200),
(3022, 302, '三文鱼味 100g', 45.00, 150),
(3031, 303, '原味 6L', 45.00, 120),
(3041, 304, '通用型 120ml', 56.00, 80),
(3051, 305, '中型犬 3 支装', 32.00, 90),
(3061, 306, '通用款 60 粒', 128.00, 40);
