# pet-store 全量代码审查报告（2026-09-17）

- 审查范围：`petshop-server`（7 个 Maven 模块，164 个 Java 文件 / 约 14,400 行）、`petshop-admin` 后台前端（app.js + config.js + app.css，约 2,900 行）、`petshop-miniapp`（TS-only 小程序，37 个源文件）、SQL 脚本与配置。
- 前置：仓库**不是 git 仓库**，因此本次是「全量代码审查」而非 diff 审查。2026-09-15 的上一轮报告（`code-review-report.md` / `code-review-fixes.md`）已修项不重复报，但已逐项复核其是否真的生效。
- 方法：6 路并行深读 + 关键结论逐条回读源码复核 + 实跑静态检查脚本 + 全量测试。
- 结论：**有 4 个 P1 需要修**。其中 2 个是「功能完全不可用/资金口径错误」的确定性缺陷，2 个是并发与部署安全缺口。测试全绿并不能覆盖它们（见文末测试结论）。

---

## 总览

| 等级 | 数量 | 说明 |
|------|------|------|
| P1 | 4 | 功能不可用 / 资金漏收 / 并发超容 / 部署安全护栏失效 |
| P2 | 4 | 幂等缺口、迁移不可重放、限流可绕过、检查工具失真 |
| P3 | 11 | 校验缺失、报错信息、死代码、索引与约束、前端小问题 |
| 已复核为**误报** | 3 | 见「误报」章节，避免后人重复排查 |

值得肯定的既有设计（本轮复核确认成立）：
- 库存扣减是真正的 CAS（`UPDATE ... WHERE stock >= n`），订单状态机全 CAS，关单与退款回补配对且不重复回补。
- 钱包「余额 = 流水累加」不变量成立；`freeze/unfreeze/deductAvailable/refund` 全部是带条件的原子更新，无负余额路径。
- 支付单幂等（CAS + `uk_pay_no` / `uk_pay_wx_tx`）、退款状态机 CAS、退款金额一律服务端重算（客户端金额被忽略）。
- JWT 会员域/后台域隔离正确，且不读 `alg` 头（`none` 算法攻击无效）；口令是 PBKDF2 21 万次 + 随机盐 + 常量时间比较。
- 全项目无 MyBatis `${}` 拼接；`esc()` 在前端所有用户数据渲染点上无遗漏（未发现真实 XSS）。
- 定时任务（关单 / 自动确认）扫描与 CAS 共用同一条件，多实例下不会重复执行。

---

## P1（建议优先修）

### [P1-1] 次卡类型「新建 / 编辑」完全不可用（两处独立缺陷叠加）

**文件**：`petshop-admin/.../static/manage/config.js:727`、`:732`、`:845`（相关：`:454`、`:791`）

**缺陷 A —— 点击即 `ReferenceError`，弹层打不开**

```js
// :454  这个 services 是 renderServices 的【函数内局部变量】
async function renderServices(view) { let services = []; ... }

// :791  而弹层的签名只接受 (t, specs)
function openCardTypeSheet(t, specs) { ... }

// :727 / :732  却在另一个函数里引用了 services，并多传一个参数
if ($('#ctNew')) $('#ctNew').onclick = () => openCardTypeSheet(null, services, specs);
b.onclick = () => openCardTypeSheet(types.find(...), services, specs);
```

`services` 全项目只在 `renderServices` 内以 `let` 声明，**不是全局变量**（`app.js:43` 那个 `services` 是导航栏条目的 `id` 字段，不是变量）。因此 `renderWalletCfg` 里的 `services` 是一个未绑定的自由变量 —— 点「＋ 新建卡型」或任意「编辑」按钮时抛 `ReferenceError: services is not defined`，弹层永远不会打开。

即便参数修好，因为签名是 `(t, specs)`，实参 `services` 会落到 `specs` 形参上，**规格勾选框会渲染错误的数据源**。

**缺陷 B —— 保存永远被拦截**

```js
// :844-846
const pickedSpecs = $$('[data-spec]:checked').map((c) => c.dataset.spec);
const pickedSvcs  = $$('[data-svc]:checked').map((c) => c.dataset.svc);   // ← 全项目没有 data-svc
if (!pickedSvcs.length) { toast('请至少选择一个适用服务'); return; }
```

`data-svc` 在整个前端**不存在**（`openCardTypeSheet` 只渲染了 `data-spec` 复选组，没有服务勾选组；`$$` 默认作用域是 `document`，页面其他区域也没有）。所以 `pickedSvcs` 恒为空数组，**即使缺陷 A 修好，保存也 100% 被「请至少选择一个适用服务」拦死**。

后端并不要求 `serviceIds` 必填（`AdminCardTypeService.validate` 未校验），但 `card.service_ids` 是次卡可用范围的判定依据（`[101]` 文本包含匹配）—— 前端那句提示「没选规格的次卡在核销时会找不到可用范围」说明设计意图就是必填。

**影响**：店长无法新建次卡类型、无法编辑已有卡型的名称/次数/售价/有效期/适用范围。次卡配置功能整体不可用（停售/开售可用）。这是一个纯前端缺陷，后端与测试都覆盖不到。

**修复方向**：① `renderWalletCfg` 内增加 `services` 数据源（和 `specs` 一起 `Promise.all` 取 `/admin/services`），并把两个调用点统一成 `openCardTypeSheet(t, specs)` 的两参形式；② 在弹层里补「适用服务」复选组，或明确该卡型只按规格限定后删掉 `pickedSvcs` 这一段校验。

---

### [P1-2] 默认 profile 为 `dev` 且启动安全守卫是 fail-open：漏配环境变量即「裸奔」

**文件**：`petshop-admin/src/main/resources/application.yml:7,33,39,40,51`；`petshop-admin/.../config/StartupSecurityGuard.java:59-73`

```yaml
# application.yml:7
spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}
# :33  :39                 :40                          :51
jwt.secret:   petshop-dev-secret-change-me-in-production   (默认值，仓库公开)
admin.token:  dev-admin-token                              (默认值，仓库公开)
shared-token-enabled: true
wx.mock:      true
```

```java
// StartupSecurityGuard.java:59-73
boolean devLike = profiles.length == 0 || Arrays.stream(profiles).anyMatch(DEV_PROFILES::contains);
if (devLike) { ...log.warn(...); return; }      // ← 只告警，直接放行
// :95-103  只有「显式指定了非 dev profile」时才会拒绝启动
```

守卫本身写得很认真（prod 下共享令牌/默认密钥/mock 都会拒绝启动），但**判据是「profile 是不是 dev-like」，而不是「配置危不危险」**。而默认值恰好就是 dev。于是：生产部署只要忘记设 `SPRING_PROFILES_ACTIVE=prod`（这是最常见的漏配），应用就以 dev 态启动，且只打 4 行 WARN：

- `X-Admin-Token: dev-admin-token` 可直接调用全部 `/admin/**` —— 结算、人工退款、改价、账号管理。这个令牌字面量就写在仓库里。
- `petshop.jwt.secret` 是仓库里的公开默认值 —— 可伪造任意会员或后台 JWT。
- `wx.mock=true` —— 可伪造任意 openid 登录。

**影响**：一条漏配的环境变量 = 后台与会员体系完全失守。属于典型的 fail-open 部署陷阱，建议按 P1 处理。

**修复方向**：把判据反过来 —— 显式声明「允许不安全配置」才放行（例如只认 `dev`/`test` 白名单，其余一律按生产校验；或对 `devLike && (sharedTokenEnabled || jwtDefault || wxMock)` 也直接拒绝启动，强制本地开发者显式设 `PETSHOP_ALLOW_INSECURE=true`）。至少要把默认 profile 改成 `prod`，让 dev 变成显式 opt-in。

---

### [P1-3] 次卡担保结算：超出卡面额的部分在 payType ≠ BALANCE 时被静默漏收

**文件**：`petshop-settle/.../service/SettleService.java:97`、`:113-122`、`:144`

```java
// :97   payType 默认 CASH —— 店员不显式选择时就是 CASH
String payType = cmd.getPayType() == null ? "CASH" : cmd.getPayType();

// :113-122
} else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())) {
    cardService.confirm(appt.getCardId(), settleNo);                 // 卡只覆盖担保额
    if ("BALANCE".equals(payType) && ...) {                          // ← 整个补收被 payType 门控
        BigDecimal extra = bill.getTotal().subtract(appt.getGuaranteeAmount());
        if (extra.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.consumeAvailable(appt.getMemberId(), extra, settleNo);
            balancePaid = extra;
        }
    }
}
```

担保额 = 服务原价快照（`AppointmentService.java:140`）；结算单 `total` 是按实际明细算出来的金额（含加项）。当 `total > 担保额` 时，差额只有 `payType == "BALANCE"` 才会补收。而 `payType` 默认就是 `CASH`，且 `settle.setPayType(...)` 在卡担保分支还会**强制写成 CARD**（`:144`）—— 于是差额既没扣钱，账面上也看不出来。

**影响**：门店系统性少收钱。`settle_bill.total` 记的是全额，实收只有卡面额，对账时差额会被当成"已收"。
反向也有问题：当 `total < 担保额` 时（例如只做了部分项目），卡仍然被整次确认核销，**顾客被多扣一次**，代码里没有任何 `total <= guaranteeAmount` 的前置校验。

**修复方向**：卡担保分支不允许把补收挂在 `payType` 上 —— `extra > 0` 时强制走余额扣款（不足则拒绝结算并明确报错）；同时补上 `total <= guaranteeAmount` 或「差额退还」的二选一规则，并把实收金额落进 `settle_bill` 供对账。

---

### [P1-4] 预约取消 / 爽约是非 CAS 状态迁移，NONE 担保下并发双点击会重复扣减 `booked_count` → 时段超容

**文件**：`petshop-booking/.../service/AppointmentService.java:156-172`（cancel）、`:175-201`（adminNoShow）、`:300-306`（releaseGuarantee）、`:308-315`（updateStatus）、`:334`（slotMapperDecr）

```java
// :156-172  cancel
Appointment appt = apptMapper.selectById(apptId);
if (!ApptStatus.PENDING.name().equals(appt.getStatus())) { throw ...; }   // 纯 Java 判断
releaseGuarantee(appt, appt.getApptNo() + "-C");                          // NONE 时是空操作
slotMapperDecr(appt.getSlotId());                                         // ← 并发下执行两次
updateStatus(appt, ApptStatus.CANCELLED);                                 // updateById，无 WHERE status

// :300-306  NONE 担保走不进任何分支，等于没有任何守卫
void releaseGuarantee(Appointment appt, String bizNo) {
    if (BALANCE...) { unfreeze(...) } else if (CARD...) { release(...) }
}   // ← 没有 else
```

`checkin` 路径用的是 `casStatus(id, PENDING, ARRIVED)`，但 `cancel` / `adminNoShow` 不是 —— 它们「先读状态 → 做副作用 → 最后才改状态」。

BALANCE / CARD 担保**碰巧**是安全的：下游 `unfreeze` / `card.release` 本身的 `WHERE` 条件在第二次调用时会不满足而抛错，整个事务回滚，`decr` 也被回滚。但门店把 `noGuaranteeEnabled` 打开后，NONE 担保的 `guaranteeAmount = 0`、`releaseGuarantee` 是空操作，**没有任何一层会抛错** → 两个并发事务都读到 `PENDING`、都执行 `slotMapperDecr`、都提交 → `booked_count` 被减两次，比真实占用更小。

**影响**：`create` 的容量闸门是 `slot.getBookedCount() >= slot.getCapacity()`，依赖的正是这个被减小的计数 → 后续下单会误判成有空位，**时段最终超容**（容量 10 的时段可以约出 11 单）。同理 `cancel` 与 `adminNoShow` 并发也会各减一次。

**修复方向**：与 `checkin` 对齐 —— 进入事务先 `casStatus(id, PENDING, CANCELLED/NO_SHOW)` 抢占，返回 0 直接抛异常整事务回滚，成功后再做 `releaseGuarantee` / `slotMapperDecr`。另外建议让 `updateStatus` 在关键迁移上都走 CAS，避免同类问题再次以「先副作用后改状态」的形式出现。

---

## P2

### [P2-1] 店长「人工退款」无幂等键，双击 / 网络重试会重复入账

**文件**：`petshop-settle/.../service/RefundService.java:224-260`；`petshop-wallet/.../service/BalanceService.java:153-168`

`manualRefund` 每次调用都 `NoUtil.refundNo()` 生成新单号，并以该单号作 `bizNo` 调 `refundToBalance`。而 `refundToBalance` 的防重是「按 `bizNo` + `REFUND_IN` 查已有流水」，单号每次都新 → **防重永远不命中**。`refund_apply` 的唯一键也只有 `refund_no`。全链路没有幂等键（无 clientToken、无「同操作人+同会员+同金额」时间窗去重），也不存在可 CAS 抢占的前置态（直接写成终态 `REFUNDED`）。

**影响**：店长手抖双击或浏览器重试 → 同一会员余额加两份。**修复方向**：加入管理员操作幂等键（前端生成一次性 token 或后端按 `operator + memberId + amount + 时间窗` 去重）。

### [P2-2] `01_schema.sql` 用裸 `CREATE INDEX`（18 条），迁移脚本 `ADD COLUMN` 不可重放

**文件**：`db/01_schema.sql`（18 处 `CREATE INDEX`，如 `:35`、`:55`、`:357`）；`db/03_migration.sql:6`、`04:10`、`05:25`、`06`、`07`

表都是 `CREATE TABLE IF NOT EXISTS`（幂等），但索引是裸 `CREATE INDEX`。**任何对已有库重跑 schema 的路径都会在第一条索引处中断**并报 `Index "xxx" already exists`（这个坑本项目已经踩过一次，症状被折叠成 `ApplicationContext failure threshold (1) exceeded`）。迁移脚本同理：`ALTER TABLE pet ADD COLUMN deleted ...` 在已含该列的库上直接报 1060，脚本注释写的是「属预期、忽略即可」，但一旦被按文件名排序的迁移工具或失败重放接管就会真的失败。

另外 `03_migration.sql`（DDL）与 `03_demo.sql`（业务数据）**撞号**。当前 `application-demo.yml` 显式指定了 data-locations，两者不会同时加载，所以暂无实际故障，但命名陷阱和未知加载顺序是真实隐患。

**修复方向**：索引统一改 `CREATE INDEX IF NOT EXISTS`（MySQL 8.0.32+ 支持）；迁移加 `IF NOT EXISTS` / `DROP IF EXISTS` 或引入版本锁表；迁移序号与演示数据解耦（迁移统一排到新建号段）。

### [P2-3] 限流与核销锁定信任 `X-Forwarded-For` 首段，可被伪造绕过

**文件**：`petshop-admin/.../filter/RateLimitFilter.java:132-146`、`:86-91`

限流 key 取 `X-Forwarded-For` 的第一段，但没有任何「可信网关」开关。在直连部署（或网关不剥离外部 XFF）时，攻击者每次请求换一个 XFF 即可绕过 `/admin/verify`、登录、充值的 5 次/10s 配额与「核销连续失败 10 次锁定 10 分钟」，对 6/8 位核销码与自提码做分布式枚举。

**修复方向**：仅在配置显式声明存在可信网关时才取 XFF，否则退回 `request.getRemoteAddr()`；当前默认取 XFF 等于默认不安全。

### [P2-4] 两个静态检查脚本的「0 问题」结论不可信（工具失真，直接导致 P1-1 被漏掉）

- **后台悬空引用扫描**（`petshop-admin/scripts/check-manage-dangling.mjs`）**作用域盲目**：它把所有声明合并成一张全局表，所以「在 A 函数里引用 B 函数的局部变量」会被判为已声明。P1-1 的 `services` 正是这种形态 —— 脚本报 0，实际是个必然报错的引用。
- **小程序 WXML 检查**（`petshop-miniapp/scripts/check-wxml.mjs`）未覆盖「`wx:key` 指向的字段在数据里不唯一」这一形态（见 P3-9）。

**修复方向**：悬空扫描改为按函数作用域判断（用 TS AST 建立作用域链）；两份脚本都补一条「自测用例必须包含已知缺陷样本」的约定 —— 本项目已经两次因为脚本假绿而误判。

---

## P3

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 1 | 卡担保结算单 `pay_type` 被强制写成 `CARD` | `SettleService.java:144` | 余额担保时也写成 `CASH`，与 `pay_detail.balancePaid` 矛盾，按渠道分流对账会错。与 P1-3 同族，建议一起修 |
| 2 | 人工改价后仍标记「会员折扣已生效」 | `DiscountEngine.java:44-59` | `manualPrice` 已完全覆盖 `total`，但返回的 `levelDiscountApplied` 仍非空 → `settle_bill.discount_desc` 口径失真（不影响实收） |
| 3 | `pet_health_log.biz_no` 无唯一约束 | `01_schema.sql:66` | 结算重试 / 消息重放会重复写健康档案，看板与趋势失真（其他单据表都有唯一键兜底） |
| 4 | 新建会员等级 `max+1` 非原子 | `AdminMemberLevelService.java:79-104` | 两个店长同时新增会撞 `uk_member_level`，异常未捕获 → 直接 500 |
| 5 | `SaleActivity` / `SaleActivityItem` 是死代码 | `petshop-mall/.../entity/` | 两个实体无任何 Service/Mapper/Controller 引用，优惠活动**完全未参与计价**（`MallOrderService.java:135` 只按 SKU 现价累加）。要么接入计价，要么删表删实体，别留着让人以为配了促销就生效 |
| 6 | 预约 `create` 不校验 `startTime` | `AppointmentService.java:99` | 未校验落在时段网格上、也未拒绝已过去的时间。客户端可构造网格外「孤儿时段」（列表看不见却占容量）或预约过去的时段。`availableSlots` 仅在前端标 `past`，后端无同等拒绝 |
| 7 | `verifyByCode` 报错信息误导 | `MallOrderService.java:254-262` | 已取消 / 已退款订单统一提示「已核销或尚未支付」，运营排查会误判 |
| 8 | 结算 `confirm` 端点无 `@RequireRole` | `AdminSettleController.java:44` | 该接口暴露 `manualPrice` 人工改价，店员即可改价。若策略要求改价需店长，属漏注解；否则应注释写明「店员可改价属预期」 |
| 9 | 小程序 `wx:key="name"` 可碰撞 | `subpages/orders/orders.wxml:29` | `itemRows` 只带 `{name, qty, priceText}`（`orders.ts:100-104`），同一订单含两个同名商品时 key 重复。建议 `toRow` 里保留 `skuId` 并改用它 |
| 10 | 小程序资金操作无防重复提交 | `orders.ts:127`、`wallet.ts:126` | 支付 / 退款 / 撤回在 `await` 期间按钮未禁用、无 `submitting` 标志。下单与预约页已做，此处不一致（服务端 CAS/状态机仍兜底，故列 P3） |
| 11 | 小程序请求层无超时 | `utils/request.ts:74-94` | `wx.request` 未传 `timeout`，后端卡住时页面 loading 永不停、用户无反馈 |

补充：`NoUtil` 的业务单号（10 位随机）无撞唯一键重试，命中即 500，概率极低（约 1e-10/前缀/天），可顺手在写入点包一层重试。

---

## 误报（已复核，不要重复排查）

以下 3 项由审查员提出，经回读源码确认为误报，记录在此避免后人再查一遍：

1. **「多张顶层 `.card` 未包裹 `.grid.cards`，相邻卡片 0 间距紧贴」** —— 不成立。所有出现第二张顶层卡片的页面都写了内联 `style="margin-top:16px"`（`config.js:477`、`:717`、`:1104`、`:1215`），间距是靠内联 margin 撑的。写法不统一（内联 margin vs `.grid.cards`），但视觉结果正确，非缺陷。
2. **「商城订单金额 `total` 累加未 `setScale`，可能出现 3 位小数」** —— 不成立。`sku.price` 与 `order.total` 都是 `DECIMAL(10,2)`，DB 回读必然 scale=2，乘整数后仍是 2 位小数。不存在精度漂移。
3. **「`pay_order` 缺 `member_id` 索引会导致会员支付单列表全表扫」** —— 不成立。`pay_order` 目前只有 `insert`、按 `pay_no` 的 `selectOne`、以及 `casSuccess` 三种访问，**没有任何按 `member_id` 查询的路径**。等真有该查询时再加索引不迟。

另有两项**不列为缺陷但需知情**：`application-demo.yml` 是刻意的不安全配置（弱密钥 + 共享令牌 + `wx.mock`），`_devsession.html`（仅存在于 `target/classes`，不进源码）把 `dev-admin-token` 字面量写进构建产物。两者在 dev/demo profile 下是设计如此，风险已被 P1-2 的 fail-open 问题放大 —— 修好 P1-2 后这两项自然收敛。

---

## 测试结论

- 全量后端测试：`mvn test`（JDK 21）→ **124 tests, 0 failures, 0 errors, BUILD SUCCESS**（8 个模块全 SUCCESS）。
- 小程序：`tsc --noEmit` 零错误；`check-wxml.mjs` 55 个绑定 / 24 个 `wx:for` 通过。
- 后台前端：`check-manage-dangling.mjs` 报 0 处（但见 P2-4，该结论不充分）。

**关键点：测试全绿与本次 4 个 P1 并存，说明存在系统性测试盲区** ——
P1-1 是纯前端（无前端测试）；P1-3（卡担保补收）没有覆盖「担保额 < 结算额 且 payType≠BALANCE」的用例；P1-4（取消并发）没有「NONE 担保下并发双取消」的用例（`AppointmentFlowTest` 覆盖的是串行全链路）；P1-2 是配置/部署问题，测试 profile 本身也是 dev-like，天然测不到。

**建议补的用例**：① 卡担保结算：`total > guaranteeAmount` 且 `payType=CASH` 时断言必须拒绝或补收；② NONE 担保（`noGuaranteeEnabled=true`）下并发两次 `cancel`，断言 `booked_count` 只减一次；③ `manualRefund` 同参数调用两次，断言只入账一次。

---

## 建议修复顺序

1. **P1-1**（次卡类型不可用，纯前端，改动小、影响直接）
2. **P1-2**（把安全守卫从 fail-open 改成 fail-closed，防部署事故）
3. **P1-3**（卡担保结算补收口径，涉及钱）
4. **P1-4**（取消路径改 CAS，涉及超容）
5. **P2-1 / P2-2**（幂等键、迁移可重放）
6. **P2-4**（修检查脚本的作用域判定，否则下次还会漏掉同类 P1）
7. 其余 P3 按需清理，其中 P3-5（死代码）与 P3-8（改价权限）需要产品决策。
