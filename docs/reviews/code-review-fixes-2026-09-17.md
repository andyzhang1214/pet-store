# pet-store 代码审查问题修复报告（2026-09-17 轮）

- 修复日期：2026-09-17
- 对应报告：`code-review-report-2026-09-17.md`（4 P1 / 4 P2 / 11 P3）
- 修复范围：**P1 全部 4 条、P2 全部 4 条、P3 全部 11 条，共 19 条全部修复**
- 验证结果：**后端 133 tests 全绿（124 → 133，新增 9 条 / 改造 3 条）、BUILD SUCCESS**；
  小程序 `tsc --noEmit` 零错误、`check-wxml.mjs` 0 问题；后台 `check-manage-dangling.mjs` 0 处悬空

---

## 一、P1（4 条，全部修复）

### P1-1 次卡类型「新建 / 编辑」完全不可用 —— 已修复

两处独立缺陷叠加，缺一不可：

| 缺陷 | 原因 | 修复 |
|------|------|------|
| 弹层打不开 | `renderWalletCfg` 里引用的 `services` 其实是 `renderServices` 内的局部变量 → `ReferenceError` | `renderWalletCfg` 改为 `Promise.all` 一并拉 `/admin/services` 与 `/admin/specs`，并把两者作为参数传给 `openCardTypeSheet(t, services, specs)` |
| 勾选态永不回显 | 库里存的是 `"[101,102]"` JSON 数组文本，前端用 `String(t.specIds).split(',')` 直接切 → 首尾元素带方括号 | 新增 `idListOf()`：先剥 `[ ]`、再切逗号、再剥引号；`specIds`/`svcIds` 都走它 |

同时补齐了「适用服务」复选框组（原先只有规格），并在保存前强制两者非空 ——
后端 `normalizeJsonArray` 会把空串归一化成 `null`，等于把适用范围清空，必须在提交前拦住。

### P1-2 默认 profile 为 `dev` + 安全守卫 fail-open —— 已修复

- `StartupSecurityGuard`：`devLike` 由 `profiles.length == 0 || anyMatch(...)` 改为
  `Arrays.stream(profiles).anyMatch(DEV_PROFILES::contains)`。
  **空 profile 不再被当作 dev**，归入 prod 走 fail-closed 校验，缺失凭据直接拒绝启动。
- `application.yml`：`spring.profiles.active: ${SPRING_PROFILES_ACTIVE:prod}`（原 `dev`）。
  漏配环境变量时走生产校验，而不是裸奔上线。

### P1-3 次卡担保结算：超出卡面额的部分被静默漏收 —— 已修复

原实现里 `extra > 0` 的补收被 `if ("BALANCE".equals(payType))` 挡住 ——
操作员选 CASH 时差额直接消失，账上少收钱且没有任何提示。

修复后（`SettleService`）：

| 情形 | 行为 |
|------|------|
| `total > 担保额` 且 `payType=BALANCE` | `consumeAvailable` 自动补收差额；余额不足抛 `BIZ_BALANCE_NOT_ENOUGH`，**整单回滚**，店员当场知道还差钱 |
| `total > 担保额` 且 `payType=CASH` | 差额作为 `cashDue` 随结果返回并在 `pay_detail` 留痕，前台显式提示「⚠️ 现金应收」 |
| `total <= 担保额` | 不产生任何应收，照常核销 1 次 —— 次卡冻的是「一次服务」不是「一个金额」，改价不该让顾客损失次数 |

`ConfirmResult` 新增 `cashDue` 字段；`app.js` 结算结果卡在 `cashDue > 0` 时渲染红色警示行。

### P1-4 取消 / 爽约是非 CAS 迁移 → NONE 担保下重复扣减名额 —— 已修复

`cancel` 与 `adminNoShow` 原先「先读状态 → 做副作用 → 最后 `updateById` 改状态」。
BALANCE/CARD 担保靠下游 `unfreeze`/`release` 的条件更新碰巧安全，
但 **NONE 担保下 `releaseGuarantee` 是空操作**，并发双击会重复 `slotMapperDecr`。

改为**先 CAS 抢占状态**（`PENDING → CANCELLED / NO_SHOW`），返回 0 即抛 `BIZ_STATE_CONFLICT` 回滚，
副作用不可能执行两次。

---

## 二、P2（4 条，全部修复）

### P2-1 人工退款无幂等键 —— 已修复

- `RefundApply` 新增 `idempotencyKey` + 唯一索引 `uk_refund_idem(store_id, idempotency_key)`
  （NULL 可重复，不带键的抽查类退款互不冲突）。
- `manualRefund`：**先插单再入账**（唯一键必须在动钱之前生效）；重放走 `findByIdemKey` 快路径，
  并发则捕获 `DuplicateKeyException` 回放首次结果，本次不入账。
- `app.js` 每次打开表单生成一次 `idemKey` 并复用，提交期间禁用按钮。

### P2-2 迁移脚本不可重放 —— 已修复

- `01_schema.sql`：18 条裸 `CREATE INDEX` 全部改 `CREATE INDEX IF NOT EXISTS`；
  新增 `uk_refund_idem`、`uk_health_biz`；文件头写明为什么不能写裸 `CREATE INDEX`。
- `03~05_migration.sql`：`ADD COLUMN` / `CREATE INDEX` 改 `information_schema` 守卫的
  预处理语句，重复执行不再中断。
- `06/07_migration.sql`：索引统一 `IF NOT EXISTS`。
- **编号撞车**：`03_demo.sql` 与 `03_migration.sql` 撞号 → demo 数据改名为 `09_demo.sql`，
  同步更新 `application.yml` / `application-demo.yml` 引用与文件头注释。
- 新增 `08_migration.sql`：幂等列 / 索引 + `schema_version` 账本，让"哪些迁移跑过"有据可查。

### P2-3 限流与核销锁定信任 `X-Forwarded-For` —— 已修复

`RateLimitFilter` 默认**只信 `remoteAddr`**；`X-Forwarded-For` / `X-Real-IP` 仅在
`petshop.security.trust-forwarded-headers=true`（默认 `false`）时才使用。
直连部署时攻击者换一个头就是一份新配额，这个开关必须由部署方显式打开。

### P2-4 两个静态检查脚本「0 问题」不可信 —— 已修复

- **`check-manage-dangling.mjs` 改为按作用域链解析**（原来是把所有声明合并成一张全局表，
  所以「在 A 函数里引用 B 函数的局部变量」会被判为已声明 —— P1-1 的 `services` 正是这种形态，脚本报 0 而实际必报错）。
  新实现用 TS AST 建 scope tree：function / block / catch 三类作用域 + `var`/`function` 提升，
  标识符从内向外逐层查找，最后落内置白名单。
- **`check-wxml.mjs` 新增两项检查**：`wx:key` 写成数据绑定 `{{ }}`（官方禁止）、
  `wx:key="index"`（取 `item.index`，字符串数组应写 `*this`）。

---

## 三、P3（11 条，全部已修）

| # | 问题 | 处理 |
|---|------|------|
| 1 | 卡担保结算单 `pay_type` 被强制写成 `CARD` | **已修**。`settle.payType` 改为记录「这笔钱实际从哪来」：余额担保固定 `BALANCE`，次卡担保固定 `CARD`（超额部分渠道看 `pay_detail`），无担保时才用操作员选择。原实现会出现 `payType=CASH` 而 `balancePaid=total` 的自相矛盾，按渠道对账必错 |
| 2 | 人工改价后仍标记「会员折扣已生效」 | **已修**。`DiscountEngine` 走 `manualPrice` 分支时把 `applied` 置 `null`，`settle_bill.discount_desc` 不再谎称折扣已生效 |
| 3 | `pet_health_log.biz_no` 无唯一约束 | **已修**。加 `uk_health_biz(pet_id, log_type, biz_no)`；`HealthArchiveService.appendInternal` 加速路径查重 + 捕获 `DuplicateKeyException` 静默跳过（带 `bizNo` 时），结算重试 / 消息重放不再重复写档案 |
| 4 | 新建会员等级 `max+1` 非原子 | **已修**。改为 5 次重试循环，每轮重新读取等级表，捕获 `DuplicateKeyException` 重新取号，不再把并发冲突打成 500 |
| 5 | `SaleActivity` / `SaleActivityItem` 死代码 | **已修（产品决策：删除）**。确认除自身定义外**零引用**（无 Service/Mapper/Controller），订单计价从未读过它们。已删除两个实体类、`01_schema.sql` 里的 `sale_activity` / `sale_activity_item` 建表语句，并在原位留注释说明"为什么删、将来怎么重建"。**不写删表迁移** —— 没有任何生产库依赖它们（demo 数据里也一条都没有）；写了反而会在别人的库上留下一串无人能解释的 DROP。同步更新 `petshop-tech-design.html`：ER 图去掉两条关系边，表清单里把这一行标为「未实现，已下线」并保留原设想（起止时间 / 特价或折扣率 / 同商品多活动取低价），避免文档声称存在实际没有的表 |
| 6 | 预约 `create` 不校验 `startTime` | **已修**。新增三重校验：非空 → 必须落在 `slotService.gridStarts(...)` 网格上（否则会懒创建"列表看不见却真占容量"的孤儿时段）→ 拒绝早于当前时刻。`availableSlots` 只在前端标 `past`，后端现在同等拒绝 |
| 7 | `verifyByCode` 报错信息误导 | **已修**。新增 `verifyBlockReason(status)`，按 VERIFIED / CANCELLED / REFUNDED / CREATED 分别给原因，不再统一提示「已核销或尚未支付」 |
| 8 | 结算 `confirm` 端点无 `@RequireRole` | **已修**。按 `AdminRole` 口径分级：结算本身是店员日常动作**不加限制**，只在请求体带 `manualPrice` 时要求店长。判定写在方法体里（注解无法按请求体内容分级），并在类注释写明理由。前端 `app.js` 同口径：非店长不渲染改价字段 |
| 9 | 小程序 `wx:key="name"` 可碰撞 | **已修**。`ItemRow` 保留 `skuId`，`toRow` 映射，`orders.wxml` 改 `wx:key="skuId"` |
| 10 | 小程序资金操作无防重复提交 | **已修**。订单页加 `busyNo`、钱包页加 `busyId` 守卫，`await` 期间按钮禁用并加 `.busy` 样式（下单与预约页已有，此处补齐一致） |
| 11 | 小程序请求层无超时 | **已修**。`config.ts` 加 `timeoutMs: 15000`；`wx.request` / `callContainer` 都传 `timeout`；`fail` 里识别 `timeout` 给「请求超时，请检查网络后重试」而不是笼统失败 |

**补充项（`NoUtil` 单号无撞键重试）**：未改动。10 位随机位已把单日碰撞概率压到 ~1e-10，
且需要重试的场景（核销码）已有 `insertWithUniqueVerifyCode` 重试 5 次兜底，
`NoUtil` 类注释已写明这一分工。属可接受的保留项。

---

## 四、新增回归用例（报告建议的 3 条 + 顺带 6 条）

### ① 卡担保结算收款口径 —— 新建 `CardGuaranteeSettleTest`（3 条）

- `cardGuaranteeOverpayCollectedAsCash`：`total=200` / 担保额 120 / `payType=CASH`
  → 断言 `cashDue=80`、`balancePaid=0`、**余额原封不动 300**、次卡核销 1 次剩 9、
  结算单 `pay_type=CARD` 且 `pay_detail` 含 `"cashDue":80`。
- `cardGuaranteeOverpayCollectedFromBalance`：同场景选 BALANCE → 只补收 80，余额 220，无现金应收。
- `cardGuaranteeUnderpayStillConsumesOneUse`：`total=100 < 120` → 无任何应收、余额不动、**仍核销 1 次**。

### ② NONE 担保并发双取消 —— `AppointmentFlowTest.concurrentCancelOnNoneGuaranteeDecrementsSlotOnce`

> **关键设计**：必须放**两张**在途预约才观测得到。`decrBooked` 的 SQL 带
> `AND booked_count > 0` 守卫，时段里只有 1 张单时「减两次」会被守卫吃掉（1→0→0），缺陷被掩盖。
> 真实伤害在 `booked_count = 2` 时：非 CAS 写法下两个事务都读到 PENDING，各减一次变成 0，
> 而其中一张预约还在 → 可用名额比实际多一个。用例断言并发后停在 **1**（不是 0）。

用例同时临时打开 `no_guarantee_enabled` 并在 `finally` 恢复，避免污染共享 H2 库里的
`noGuaranteeRejectedWhenDisabled`。

### ③ 人工退款幂等 —— `RefundFlowTest` 新增 3 条

- `manualRefundRetryWithSameIdemKeyCreditsOnce`：同 key 顺序重试 → 返回同一张退款单，
  `MANUAL` 单只 1 条、`REFUND_IN` 流水只 1 条、余额只 +50。
- `concurrentManualRefundWithSameIdemKeyCreditsOnce`：并发双击 → 两次都拿到结果（一次新建、一次回放），
  但落单 / 入账 / 余额增长都只有一次。
- `manualRefundWithoutIdemKeyStaysNonIdempotent`：不带 key 保持旧行为，抽查类补偿不受影响。

### ④ 顺手补的 3 条

- `AdminConfigFlowTest.cardTypeServiceIdsStoredAsJsonArrayTextForFrontend`：
  锁住「逗号分隔进 → `"[101,102]"` 出」的存储格式契约，以及 JSON 数组文本进也能收敛到同一形态。
  这是 P1-1 在本机**无法做浏览器验证**时唯一能固化的部分（理由见第六节）。
- `AdminConfigFlowTest.cardTypeEmptyScopeNormalizesToNull`：`"[]"` 归一化成 `null` 而非空数组。
- `DiscountEngineTest` 同步断言 `manualPrice` 路径下 `levelDiscountApplied == null`（P3-2）。

---

## 五、修 P3-6 时炸出的定时炸弹（本轮最重要的意外发现）

给 `create()` 加上「拒绝过去时段」后，**3 个既有用例立刻变红**：

```
AppointmentFlowTest.autoConfirmJobSweepsOverduePendingOnly:216
  » Biz 参数不合法: 预约时段已过，请选择之后的时段
AppointmentFlowTest.selfConfirmArrivalOnAppointmentDay:185
  » Biz 参数不合法: 预约时段已过，请选择之后的时段
```

根因：这些用例把预约建在「**今天** 10:00 / 今天 16:00」。**这套用例只在上午跑得通** ——
09:58 跑全量是 124 全绿，10:0x 再跑就必挂。而且报错完全指向业务代码（`预约时段已过`），
`StatsFlowTest`（今天 16:00）会在下班后以同样方式失败，排查时极易误判成业务回归。

**修法**（已写进项目 MEMORY.md 作为铁律）：先建在未来日期，再把 slot 行挪到目标日期 ——
这个套路本文件里原本就用来模拟「昨天」，只是没意识到「今天」同样需要它。

同时踩到 `time_slot` 的唯一索引 `uk_time_slot(store_id, slot_date, start_time, service_id)`：
挪日期等于给新日期占坑，**每条用例必须用互不相同的时刻**（当前 12:00 当天样例 / 13:00 自助到店 /
11:00 逾期样例 / 17:00 统计，第一次改时 12:00 与 13:00 撞了 `DuplicateKey`）。

---

## 六、验证证据

```
[INFO] Tests run: 133, Failures: 0, Errors: 0, Skipped: 0
[INFO] petshop-server/common/wallet/member/booking/mall/settle/admin ... SUCCESS
[INFO] BUILD SUCCESS
```

| 检查项 | 命令 | 结果 |
|--------|------|------|
| 后端全量测试 | `mvn test`（JDK 21） | **133 tests, 0 failures, 0 errors**（124 → 133） |
| 小程序类型检查 | `tsc --noEmit -p tsconfig.json` | 0 错误 |
| 小程序 WXML 检查 | `check-wxml.mjs` | 55 绑定 / 24 `wx:for`，0 问题 |
| 后台悬空引用 | `check-manage-dangling.mjs` | 0 处（按作用域链解析） |

### 未能完成的验证：端到端 UI

**本机环境的回环 HTTP 不可用**：沙箱注入 `SERVER__PORT`（Spring 宽松绑定，覆盖 `application.yml` 的 8080），
并在前面挂了一层鉴权代理 —— `curl http://127.0.0.1:<port>/manage/index.html` 与 agent-browser
拿到的都是 `X-Powered-By: Express` 的 `{"error":{"code":"AUTH_REQUIRED"}}`（只有 `/` 返回 200）。

因此 **P1-1 的弹层渲染、P1-3 的现金应收展示、P3-8 的改价字段隐藏这三处纯前端行为未做实机验收**。
采用的替代手段：
1. 逐行回读前端改动（`idListOf` / `checkbox data-svc` / `cashDue` 渲染 / `isManager()` 分级）确认逻辑正确；
2. 把前端依赖的**接口契约**写成服务层回归用例（④-1、④-2），让"后端偷偷改格式导致前端静默失效"这件事有测试兜住。

**建议在有真实浏览器访问的环境里补一次验收**：打开 `/manage`，进「次卡类型与充值档位」，
点「新建卡型」确认弹层能打开且服务 / 规格列表有值；编辑一个已有卡型确认勾选态正确回显；
再做一单次卡担保、金额超过卡面额的结算，确认前台出现「⚠️ 现金应收」。

---

## 七、遗留

1. **端到端 UI 验收**：见第六节，需在可访问回环端口的环境补做。
2. **`NoUtil` 撞键重试**：见第三节补充项，评估为可接受，未改动。
3. **`design-doc-review.md` 第 86 行**：该审查快照列出的「25 张表」里仍含 `sale_activity` /
   `sale_activity_item`（现在应为 23 张）。这是历史审查记录的原文，未回改 ——
   如需保持计数一致再单独处理。
