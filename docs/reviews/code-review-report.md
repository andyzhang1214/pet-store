# pet-store 全量代码审查报告

- 审查日期：2026-09-15
- 审查范围：`petshop-server`（7 个 Maven 模块，99 个文件）、`petshop-miniapp`（原生小程序 + TypeScript，49 个文件）
- 审查方法：三轮审查（结构 → 逐行 → 边界/并发），安全优先
- 结论：**不建议以当前状态上线**。存在 2 个 CRITICAL 资金安全问题与多处前后端契约断裂，需先修复。

---

## 总览

| 等级 | 数量 | 说明 |
|------|------|------|
| CRITICAL | 2 | 资金安全 / 凭据管理，必须修复 |
| MAJOR | 9 | 逻辑错误、契约断裂、并发缺陷、性能 |
| MINOR | 12 | 可维护性、健壮性改进 |
| NIT | 6 | 风格与提示 |

做得好的地方（值得保留的设计）：
- 钱包 SQL 全部使用条件更新（`WHERE balance - frozen >= amt`），从数据库层面杜绝负余额，无 SQL 注入（全部 `#{}` 参数化）。
- 支付单 CAS（`status='CREATED'` → `SUCCESS'`）+ `uk_pay_no` / `uk_pay_wx_tx` 唯一键，幂等设计正确。
- 集成测试质量高：`WalletFlowTest` 断言了"余额 = 流水累加"的账务不变量；`AppointmentFlowTest` 覆盖全链路（创建→取消→核销→结算→健康档案同事务）。
- 预约时段用 `FOR UPDATE` 行锁 + 唯一键兜底的懒创建模式，并发容量控制思路正确。
- 小程序端请求层有 401 自动重登重试、登录 Promise 去重、资金数据不缓存的分层缓存策略，工程素养不错。

---

## CRITICAL（必须修复，阻断上线）

### C-1 充值 mock-notify 端点可无中生有铸造余额

`petshop-server/petshop-wallet/src/main/java/cn/petshop/wallet/controller/RechargeController.java:49-54`

```java
@PostMapping("/{payNo}/mock-notify")
public R<Boolean> mockNotify(@PathVariable String payNo, @RequestBody CreateCmd cmd) {
    MemberContext.require();
    BigDecimal gift = cmd.getGift() == null ? BigDecimal.ZERO : cmd.getGift();
    return R.ok(payService.onPaySuccess(payNo, "MOCK-" + payNo, cmd.getAmount(), gift));
}
```

三个问题叠加：
1. **金额完全由客户端指定**：`amount`/`gift` 直接来自请求体，与订单应付金额（`order.getAmount()`）无任何比对，任意登录用户可给自己入账任意金额；
2. **无归属校验**：`onPaySuccess` 不校验 `payNo` 属于当前登录会员，任何会员可以给**任何人的**支付单触发入账；
3. **未受配置门控**：端点在任何环境（含 `petshop.wx.mock=false`）都生效，没有任何 profile / 开关保护。

`PayService.onPaySuccess`（PayService.java:52-64）本身也缺少 `principal + gift == order.getAmount()` 的对账断言——即使将来换上真实微信回调，这个缺口依然存在。

**修复建议**：
- 将 mock-notify 移到独立的 dev profile（`@Profile("dev")` 或配置开关），生产构建不注册该 Bean；
- `onPaySuccess` 内部对账：`principal + gift` 必须等于 `order.getAmount()`，不符则记告警并拒绝入账；
- 校验 `order.getMemberId()` 与触发方身份（或仅允许回调服务内部调用）。

### C-2 生产敏感配置硬编码在 application.yml

`petshop-server/petshop-admin/src/main/resources/application.yml:6-34`

- 数据库 `root/root`；
- JWT 密钥 `petshop-dev-secret-change-me-in-production`；
- 后台统一令牌 `dev-admin-token`；
- `petshop.wx.mock: true`（登录 openid = `mock-{code}`，任何人可伪造任意身份登录）。

该文件会随 jar 打包发布。一旦此配置进入生产，等于：任何人可伪造 JWT 冒充任意会员（`mid` 自选）、用公开的 `dev-admin-token` 调用全部 `/admin/**` 接口（结算、改价、爽约扣款）。

**修复建议**：密钥改环境变量 / 配置中心注入；增加启动断言（检测到 `change-me` / `dev-admin-token` 且非 dev profile 时拒绝启动）；`wx.mock=true` 时打印显著告警。

---

## MAJOR（应尽快修复）

### M-1 结算确认无状态 CAS，可重复结算

`SettleService.confirm`（SettleService.java:70-131）：先读状态判断 `ARRIVED`，扣款后**最后**才把状态改为 `SETTLED`。两个并发的 confirm（如店员双击）都会通过校验：
- 余额担保路径：第二次 `unfreeze` 会因冻结额不足失败回滚（侥幸受保护）；
- **无担保 + 余额支付路径**（`consumeAvailable`）：只要余额足够，第二次扣款**成功**，插入两张结算单、写两份健康档案，重复收费。

**修复**：进入事务先做 `UPDATE appointment SET status='SETTLING' WHERE id=? AND status='ARRIVED'` 式的 CAS 抢占，成功者才继续。

### M-2 前后端契约大面积断裂（小程序页面实际不可用）

| 位置 | 前端期望 | 后端实际 | 后果 |
|------|---------|---------|------|
| `api/member.ts` ArchiveVO | `vaccineDue/rabiesDue/dewormDue/logs` | `vaccine/worm/weightTrend/lastBath/lastGroom/recentLogs` | 健康档案页三张卡全空、时间轴为空（`archive.logs \|\| []` 兜底） |
| `api/member.ts` DueItem.status | `'SOON'/'OVERDUE'` | `'DUE_SOON'/'OVERDUE'` | "即将到期"状态永远匹配不上 |
| `api/booking.ts` UsableCard | `cardName/remaining` | `name/remainTimes` | 次卡选择显示 undefined |
| `utils/format.ts` SERVICE_CATEGORY | `DEWORM` | 种子数据为 `WORM`（02_seed.sql:21） | 驱虫服务显示原文 "WORM" |
| `utils/format.ts` HEALTH_LOG | 含 `RABIES` | 后端枚举无此类型 | 死代码 |
| `api/wallet.ts` WalletVO | 多出 `principalBalance/giftBalance` | 无此字段 | 类型定义与实际不符（页面未用，暂无实害） |

**修复**：以后端 VO 为准统一两份类型定义，`DEWORM→WORM` 二选一。建议引入契约测试或至少把接口返回 JSON 贴进注释。

### M-3 钱包流水金额显示双重负号

`BalanceService.consumeAvailable/refund` 入账时写入的是**负数**（`split[0].negate()`，BalanceService.java:78、98）；小程序 `subpages/wallet/wallet.ts:45-46` 又按 bizType 前置 `+/-` 符号，WXML 渲染 `{{sign}}¥{{amtText}}` → 消费显示 `-¥-120.00`、退款显示 `+¥-20.00`；FREEZE/UNFREEZE 金额恒为 0，显示 `¥0.00` 无信息量。

**修复**：约定流水金额统一存正数 + 用 bizType 表方向，或前端 `Math.abs()` 后再加符号。

### M-4 我的预约列表 N+1 查询

`AppointmentController.list` → `listMine().map(toVO)`，每条预约 3 次额外查询（service、**该会员全部宠物**、slot），且 `toVO(appt)` 里的宠物匹配是把 `petService.listByMember` 整个拉出来 filter（AppointmentService.java:299-305）。历史预约 50 条时产生 150+ 次查询。`AdminAppointmentController.list` 同样问题。

**修复**：批量按 `serviceId IN (...)` / `slotId IN (...)` / `petId IN (...)` 预取后 Map 组装。

### M-5 限流覆盖面不足

`RateLimitFilter.java:33-37` 只覆盖 `POST /api/v1/appointments` 和 `POST /api/v1/auth/login`。以下高频写接口完全无限流：
- `/api/v1/recharges`（创建支付单）；
- `/api/v1/pets` CRUD；
- `/admin/**` 全部（`/admin/verify` 可暴力枚举 6 位核销码，10^6 空间 + mod97 校验位挡不住持续枚举）。

另外 key 用 `getRemoteAddr()`，部署在网关/云托管后所有用户共享同一 IP，会误伤且可被绕过。

**修复**：扩大白名单为"写接口默认限流，白名单豁免"；key 优先取 `X-Forwarded-For` 首段；`/admin/verify` 加失败计数锁定（核销码连续错 N 次锁定该门店端点一段时间）。

### M-6 后台鉴权是全局共享静态 Token

`AdminTokenInterceptor.java:20-27`：单一 `X-Admin-Token` 常量、`equals` 非常量时间比较、无账号体系、无操作者身份（结算单 `operator` 靠请求体自报）。代码注释已声明是占位，但当前它守着**扣款、改价、爽约**等资金操作。C-2 修复前风险被放大。

### M-7 宠物硬删除破坏引用完整性

`PetService.delete`（PetService.java:73-76）物理删除，而 `appointment.pet_id`、`pet_health_log.pet_id` 无外键也无软删标记。删除后：历史预约/健康档案的 `petName` 变 null、`getOwnedPet` 抛"宠物不存在"导致旧预约无法核销结算。

**修复**：软删除（`deleted` 标记）或校验存在进行中预约（PENDING/ARRIVED）时拒绝删除。

### M-8 次卡购买无支付环节

`CardService.purchase` 只插卡记录，不扣任何钱，也没有 controller 调用它——若后续直接暴露为会员接口，等于免费领卡。属于**未完成功能**，但应加注释/TODO 显式标记防止被误接入。

### M-9 商城页为空壳且商品分类前后端不一致

`pages/mall/mall.ts:17-19` 商品列表从未加载（goods 硬编码为空数组），TabBar 却已放出"商城"入口。种子数据 `goods.category='洗护'`（中文）与前端 `FOOD/SNACK/TOY/SALE` 分类键完全对不上。上线前要么补齐要么隐藏入口。

---

## MINOR

1. **门店 ID 硬编码 `1L` 散布多处**：`BookingController.slots`、`PriceMatchService.match`、`PetService.create`、`HealthArchiveService.appendInternal`、`RechargeController.create`（传参 storeId 也是常量 1L）。多店扩展前必须收敛到上下文/配置。
2. **`inSql` 字符串拼接**（`AppointmentService.listByDate:225-226`）：`"SELECT id FROM time_slot WHERE slot_date = '" + date + "'"`——当前 `date` 是 `LocalDate` 类型化参数，无注入风险，但模式危险，换成 `inSql` 参数化或先查 slotId 列表。
3. **枚举反序列化未捕获**：`GuaranteeType.valueOf(cmd.getGuaranteeType())`、`ServiceCategory.valueOf(category)` 传入非法值抛 `IllegalArgumentException` → 全局兜底成 500，应为 400 参数错误。同理 `AdminSettleController.verify` 的 `cmd.getCode().trim()` NPE。
4. **`findOrCreate` 无并发保护**（`MemberService:17-34`）：同一 openid 并发首次登录会撞 `uk_member_openid` 唯一键抛 500，应捕获 `DuplicateKeyException` 后重查。
5. **单号 6 位随机数无重试**（`NoUtil`）：`payNo/apptNo/settleNo` 撞唯一索引直接 500；核销码有 5 次重试机制，其他单号没有。建议统一 8 位以上 + 撞键重试。
6. **`refund()` 可把余额扣成负数**（`BalanceService:94-103`）：`recharge(memberId, principal.negate(), ...)` 无 `balance >= amount` 守卫，退款金额人工录入超余额时会写出负余额账户。
7. **`lockOrCreate` 容量回写不落库**（`SlotService:73-75`）：`locked.setCapacity(capacity)` 只改了内存对象，DB 中旧容量未更新——本次判断正确，但下个请求读到的还是旧容量。
8. **分页 size 无上限**（`WalletController.ledger:32-33`）：客户端可传 `size=100000`，应 cap 到 100。
9. **JWT 令牌 168 小时（7 天）且无吊销机制**：泄露后只能等过期。建议缩到 24h 以内，配合微信 `wx.checkSession`。
10. **自研 JWT 解析靠字符串手术**（`JwtUtil.parseMemberId:34-47`）：`startsWith("{\"mid\":")` + `indexOf` 截取，payload 字段顺序一变即失效。签名校验用了 `MessageDigest.isEqual`（好），但整体建议换 jjwt / nimbus-jose。
11. **`.DS_Store` 提交进仓库**：`petshop-booking/.../entity/.DS_Store`，加 `.gitignore`。
12. **`project.config.json` `urlCheck: false`**：开发必需，但发版清单要记得开启并配置合法域名（`https`）。

## NIT

- `utils/format.ts:1` 注释写"分转显示"，实际后端金额是"元"——误导性注释；金额用 `number` 承载有浮点精度隐患，建议后端序列化为字符串。
- `booking.ts initDates` 从明天开始（i=1），但后端允许约今天——是有意为之请在注释说明。
- `mine.ts` 顶部 `import { showApiError } from '../../utils/request'` 重复导入（第 3 行与第 5 行）。
- `HealthArchiveService.append` 的 source 判断逻辑疑似反了（`SOURCE_STAFF` 才校验归属？），且 `cmd.getSource()` 无 null 防护。
- `SlotService.gridStarts` 的 while 条件可简化为 `!cursor.plusMinutes(step).isAfter(close)`。
- `subpages/` 目录未配置为 `subPackages`，`lazyCodeLoading` 的收益打折。

---

## 测试覆盖评估

现有测试（2 个集成测试类，约 10 个用例）质量不错，但关键缺口明显：
- **无**：`mock-notify` 金额对账 / 越权触发的负向用例（正因如此 C-1 没被发现）；
- **无**：并发场景（同时预约最后一格、双击结算）；
- **无**：`DiscountEngine` 单测——纯函数、零依赖，是性价比最高的测试目标（零/负金额、人工改价缺原因、折扣边界 0/1）；
- **无**：限流器行为测试。

**建议补充的最小测试集**：① mock-notify 金额不符 → 拒绝；② 非本人 payNo → 拒绝；③ 并发双 confirm 只成功一次；④ DiscountEngine 4 个边界。

---

## 修复优先级建议

1. 立即：C-1（下线 mock-notify 或加门控+对账）、C-2（密钥外置）；
2. 本迭代：M-1（结算 CAS）、M-2（契约对齐，否则健康档案/次卡选择等于没上线）、M-3（金额显示）；
3. 下迭代：M-4 ~ M-9 与 MINOR 项按需排入。
