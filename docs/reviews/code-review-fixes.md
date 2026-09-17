# pet-store 代码审查问题修复报告

- 修复日期：2026-09-15
- 对应报告：`code-review-report.md`（2 CRITICAL / 9 MAJOR / 12 MINOR / 6 NIT）
- 验证结果：**后端 28 个测试全绿（含新增 12 个负向/并发用例），小程序 `tsc --noEmit` 零错误，7 个 Maven 模块 BUILD SUCCESS**

---

## 验证证据

```
[INFO] Tests run: 2,  Failures: 0, Errors: 0 -- cn.petshop.SettleConcurrencyTest   (新增)
[INFO] Tests run: 5,  Failures: 0, Errors: 0 -- cn.petshop.WalletFlowTest
[INFO] Tests run: 7,  Failures: 0, Errors: 0 -- cn.petshop.DiscountEngineTest      (新增)
[INFO] Tests run: 10, Failures: 0, Errors: 0 -- cn.petshop.SecurityRegressionTest  (新增)
[INFO] Tests run: 4,  Failures: 0, Errors: 0 -- cn.petshop.AppointmentFlowTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## CRITICAL

### C-1 充值 mock-notify 可无中生有铸币 —— 已修复

**修复内容**（三重校验，缺一不可）：

| 层 | 位置 | 做法 |
|----|------|------|
| 门控 | `RechargeController.mockNotify` | `petshop.wx.mock` 默认值由 `true` 改为 **`false`**；关闭时直接抛 `BIZ_PERMISSION_DENIED` |
| 归属 | `PayService.onPaySuccess(…, operatorMemberId, …)` | 新增操作者参数，非本人支付单一律拒绝；生产微信回调传 `null`（内部调用） |
| 对账 | 同上 | 强制 `principal + gift == order.getAmount()`，且两者非负，不符即拒绝入账 |

`RechargeController.create` 同时补上赠送金校验：不得为负、**不得超过本金**（避免客户端随意放大赠送额度）。

**新增回归用例**（`SecurityRegressionTest`）：
- `payCallbackRejectsAmountMismatch` —— 订单 100 上报 1000 → 拒绝，余额不变
- `payCallbackRejectsPrincipalPlusGiftMismatch` —— 60+60≠100 → 拒绝
- `payCallbackRejectsNegativeAmounts` —— 负数入账 → 拒绝
- `payCallbackRejectsOtherMembersOrder` —— 攻击者触发他人订单 → 拒绝，双方余额均不变
- `payCallbackIsIdempotentOnReplay` —— 回调重放 → 返回 `false`，不重复入账

### C-2 生产敏感配置硬编码 —— 已修复

- `application.yml`：数据库、JWT 密钥、后台令牌、`wx.mock` **全部改为环境变量占位**（`${PETSHOP_JWT_SECRET:…}` 等），默认 profile 明确为 `dev`。
- 新增 `application-prod.yml`：敏感项默认值一律为空，`wx.mock` 强制 `false`。
- 新增 `StartupSecurityGuard`（`@PostConstruct`）：
  - 非 `dev/local/test` profile 下检测到任一默认凭据 → **抛异常拒绝启动**，并逐条列出缺失项；
  - dev/test 下打印显著告警（提醒不要带到生产）。
- 顺带修复 MINOR-9：JWT TTL 由 **168h 缩短为 24h**（`petshop.jwt.ttl-hours` 可配）。

---

## MAJOR

| 编号 | 问题 | 修复方式 | 文件 |
|------|------|---------|------|
| M-1 | 结算确认无状态 CAS，可重复扣款 | 进入事务先 `casStatus(ARRIVED → SETTLING)` 抢占，失败即拒绝；`ApptStatus` 新增 `SETTLING` 中间态 | `SettleService` / `AppointmentMapper` / `ApptStatus` |
| M-2 | 前后端契约大面积断裂 | 前端类型全部以后端 VO 为准重写（见下） | `api/member.ts` `api/booking.ts` `api/wallet.ts` `utils/format.ts` |
| M-3 | 钱包流水双重负号 | 改为取绝对值 + 由数值实际符号决定正负；FREEZE/UNFREEZE 不再显示 `¥0.00`，改显示冻结总额 | `subpages/wallet/wallet.ts` `.wxml` |
| M-4 | 预约列表 N+1（每条 3 次查询） | 新增 `toVOList()`：service/pet/slot 各批量预取一次；`toVO()` 走同一路径避免逻辑分叉 | `AppointmentService` + 两个 Controller |
| M-5 | 限流只覆盖 2 个端点 | 改为 **写方法默认全量限流**：按「IP + 资源根路径」归一计数（20 次/10s），敏感接口（登录/充值/核销/结算）收紧到 5 次/10s，另加单 IP 全局 100 次/10s；IP 优先取 `X-Forwarded-For` 首段；**核销连续失败 10 次锁定 10 分钟** | `RateLimitFilter` |
| M-6 | 后台鉴权静态 Token + 非常量时间比较 | 改用 `MessageDigest.isEqual` 常量时间比较；令牌为空/未配置一律拒绝 | `AdminTokenInterceptor` |
| M-7 | 宠物硬删除破坏引用完整性 | 改为 **逻辑删除**（MyBatis-Plus `@TableLogic`），新增 `pet.deleted` 列；`PetMapper.selectNamesIgnoreDeleted` 供历史回显宠物名 | `Pet` `PetMapper` `PetService` `01_schema.sql` `03_migration.sql` |
| M-8 | 次卡购买无支付环节 | 补显著 TODO 警示（严禁直接挂会员接口，需复用 `PayService` 走「下单→支付→回调发卡」闭环） | `CardService.purchase` |
| M-9 | 商城页空壳 + 分类键不一致 | 补 TODO 说明前端自拟分类（FOOD/SNACK/TOY/SALE）与库中 `goods.category`（中文）不一致，要求以后端分类字典为唯一准绳 | `pages/mall/mall.ts` |

### M-2 契约对齐明细

| 位置 | 修复前（前端） | 修复后（对齐后端） |
|------|--------------|------------------|
| `ArchiveVO` | `vaccineDue` / `rabiesDue` / `dewormDue` / `logs` | `vaccine` / `worm` / `weightTrend` / `lastBath` / `lastGroom` / `recentLogs` |
| `DueItem.status` | `'OK' \| 'SOON' \| 'OVERDUE'` | `'SCHEDULED' \| 'DUE_SOON' \| 'OVERDUE'` |
| 健康页卡片 | 疫苗 / 狂犬疫苗 / 驱虫 三张 | 疫苗 / 驱虫 两张（后端无狂犬独立类型） |
| `UsableCard` | `cardName` / `remaining` | `name` / `remainTimes`（+ `cardNo` / `validUntil`） |
| `WalletVO` | 多出 `principalBalance` / `giftBalance` | 已删除（后端无此字段） |
| `SERVICE_CATEGORY` | `DEWORM` | `WORM` |
| `HEALTH_LOG` | 含不存在的 `RABIES` | 改为 `VACCINE/WORM/WEIGHT/BATH/GROOM` |
| `APPT_STATUS` | 无 `SETTLING` | 补 `SETTLING: 结算中` |

---

## MINOR（12 项全部处理）

1. **门店 ID 硬编码 `1L`**：新增 `StoreContext.DEFAULT_STORE_ID`，收敛 7 处生产代码（BookingController / PriceMatchService / PetService / HealthArchiveService / AdminAppointmentController / AuthController / RechargeController）。
2. **`inSql` 字符串拼接**：`listByDate` 改为先查 slotId 列表再参数化 `IN`（新增 `TimeSlotMapper.selectIdsByDate`）。
3. **枚举反序列化未捕获**：`GuaranteeType.valueOf`、`ServiceCategory.valueOf` 包 try-catch 转为 `BIZ_PARAM_INVALID`（400 而非 500）；`AdminSettleController.verify` 补 `code` 空值校验，消除 NPE。
4. **`findOrCreate` 无并发保护**：捕获 `DuplicateKeyException` 后重查，保证幂等。
5. **单号 6 位随机数**：提升为 **10 位**（碰撞概率 ~1e-6 → ~1e-10），并注明仍需重试兜底的场景由调用方负责。
6. **`refund()` 可把余额扣成负数**：改为条件更新（`balance - frozen >= amt`）的 `MemberAccountMapper.refund`，超额直接拒绝并整体回滚；`cum_principal/cum_gift` 同步向下扣减且不低于 0。
7. **`lockOrCreate` 容量回写不落库**：新增 `TimeSlotMapper.syncCapacity` 真正落库。
8. **分页 size 无上限**：`WalletController.ledger` cap 到 100，page 至少 1。
9. **JWT TTL 168h**：→ 24h（见 C-2）。
10. **自研 JWT 字符串手术解析**：改为正则提取 `mid`/`exp`，**与 payload 字段顺序解耦**；新增 `mid > 0` 校验。
11. **`.DS_Store` 提交进仓库**：清理 3 个残留文件，新增 `.gitignore`（含 target、密钥文件、node_modules、IDE 目录）。
12. **`project.config.json` `urlCheck: false`**：属发版清单项，保持开发配置不变，**发布前必须开启并配置 https 合法域名**（详见文末待办）。

---

## NIT（6 项全部处理）

- `format.ts` 误导性注释「分转显示」→ 更正为「后端单位即为元」。
- `booking.ts initDates` 起点由「明天」改为「今天」（`i=0..13`），与后端可约范围（只拒绝早于今天）一致，并补注释说明今天内已过时段由 `SlotService` 标记 `past`。
- `mine.ts` 重复导入 `showApiError` → 合并为一处。
- `HealthArchiveService.append` 归属校验逻辑反了（IDOR 隐患）→ **改为一律校验宠物归属**，店员代录应走独立内部入口。
- `SlotService.gridStarts` while 条件简化为 `!cursor.plusMinutes(step).isAfter(close)`。
- `subpages/` 未配置分包 → `app.json` 新增 `subPackages`（root: `subpages`）+ `preloadRule`（进入「我的」预加载）。

---

## 新增测试（12 个用例）

| 测试类 | 用例数 | 覆盖 |
|--------|-------|------|
| `DiscountEngineTest` | 7 | 零/负金额、折扣边界 0 与 1、折扣 >1 不生效、HALF_UP 舍入、人工改价必填原因、改价不可为负 |
| `SecurityRegressionTest` | 10（含 JWT 1 + 宠物 1 + 退款 2） | mock-notify 对账/越权/重放/负数、退款超额拒绝、宠物软删保留历史、JWT 签名与过期 |
| `SettleConcurrencyTest` | 2 | 顺序双击拒绝、**双线程真并发只成功一次**（结算单/扣款/健康流水均为 1） |

注：`SecurityRegressionTest` 实际 10 个用例，其中 4 个属 mock-notify，2 个属退款，1 个宠物，1 个 JWT，2 个入账正向。

---

## 需要的运维动作（上线前必做）

1. **数据库增量升级**：已有库执行 `petshop-server/petshop-admin/src/main/resources/db/03_migration.sql`
   （`ALTER TABLE pet ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;` + 重建 `idx_pet_member`）。
   全新安装直接跑 `01_schema.sql` 即可。
2. **注入生产凭据**：`SPRING_PROFILES_ACTIVE=prod`，并设置 `PETSHOP_JWT_SECRET`（≥32 字节强随机）、`PETSHOP_ADMIN_TOKEN`、`DB_URL/DB_USERNAME/DB_PASSWORD`、`WX_APPID/WX_SECRET`；确认 `PETSHOP_WX_MOCK=false`。
3. **微信后台**：小程序发布前把 `project.config.json` 的 `urlCheck` 改回 `true`，并配置 https 合法域名。
4. **限流部署前提**：`X-Forwarded-For` 仅在**会剥离外部 XFF 的可信网关**后可信；若直接暴露公网，应移除该分支（代码内已注明）。

---

## 仍未解决（需产品/架构决策，非本次代码缺陷）

1. **次卡购买闭环**（M-8）：需要新增 `PayOrder.bizType = CARD_PURCHASE` 与支付回调发卡逻辑。
2. **商城模块**（M-9）：需要后端补商品列表 + 分类字典接口，前端移除自拟分类。
3. **后台账号体系**（M-6 的根治）：当前仍是单令牌占位，`settle_bill.operator` 由请求体自报，审计能力不足。建议接入若依 Token + 角色体系，并在结算单上落真实操作者 ID。
4. **JWT 换库**（MINOR-10 的根治）：已消除顺序依赖，但仍建议迁移到 jjwt / nimbus-jose，以获得标准 JWT 语义与更完备的校验。

[wbs-reply-done]: #
