# 退款只入余额 · 实现说明

> 口径：**退款一律退入会员的可用余额，不提供任何退微信 / 退现金的出口。**
> 对应实现：`petshop-settle` 的 `RefundService` + `petshop-wallet` 的账务与次卡能力。
>
> **v1.2 增补（本次）**：退款来源新增 `GOODS` 商品订单；新增「申请 → 后台提示 → 管理员确认 → 到账」的
> 提示链路（后台角标 + 微信订阅消息）；新建 `/manage/` 后台管理端；修复分页插件缺失与雪花 ID 截断两个实现层缺陷。

---

## 0. 用户要的那条链路（先看这个）

```
会员在小程序点「申请退款」
        │
        ▼
POST /api/v1/refunds        ── 服务端算金额，落 refund_apply(status=APPLYING)
        │
        ├──► 后台角标：GET /admin/refunds/pending-count   → +1（轮询，30s）
        └──► 微信订阅消息推给店长（若已授权且有配额；失败静默降级为仅角标）
        │
        ▼
管理员在 /manage/ 退款审核页看到待审单，点「通过并退款」
        │
        ▼
POST /admin/refunds/{id}/audit {approved:true, operator:"店长A"}
        │
        ▼
同一事务：① 退款单 CAS APPLYING→REFUNDED ② 业务侧核销 ③ 加款入可用余额 + 写 REFUND_IN 流水
        │
        ▼
会员余额立即到账（无「处理中」中间态，通过即到账）
```

---

## 1. 先把三个动作分清（这是最容易混的地方）

系统里有三个方向不同、名字都像"退款"的动作，混用会直接做错账：

| 动作 | 方向 | 账本类型 | 当前是否启用 | 说明 |
|---|---|---|---|---|
| 充值入账 | 钱从微信 → 余额 | `RECHARGE` | 已启用 | 充值成功后入账（`PayService`） |
| **退款入账** | **钱从业务单据 → 余额** | **`REFUND_IN`** | **已启用，唯一退款出口** | 次卡退款（`CARD`）、商品订单退款（`GOODS`）、人工退款（`MANUAL`）都走这条 |
| 余额出账 | 钱从余额 → 离开账户体系 | `REFUND` | **不启用**（保留） | 将来若开通微信原路退款，落地点在这里 |

`BalanceService.refund()`（余额出账）**没有生产调用方**，仅被回归测试锁定"不得把余额扣成负数"这条约束；
真正在跑的是本次新增的 `BalanceService.refundToBalance()`。

---

## 2. 状态机

```
                 ┌──────────── 顾客撤回 ──────────►  CANCELLED（终态）
                 │
APPLYING ────────┼──────────── 店长驳回 ──────────►  REJECTED（终态）
（待审核）        │
                 └──────────── 店长通过 ──────────►  REFUNDED（终态，已退入余额）
```

- 每条迁移都是 CAS（`WHERE status = 前置态`），没有"处理中"的中间态 —— **通过即到账**（同一事务内加款 + 写流水）。
- 审核通过的执行顺序固定为：**抢占退款单状态 → 业务侧核销（次卡置 `REFUNDED` / 订单置 `REFUNDED` 并回补库存）→ 账务入余额**，
  三步同一事务，任一步失败整体回滚。
- 并发双击时后到的事务阻塞在退款单行锁上，待前者提交后 CAS 返回 0 被拒绝，从根上杜绝重复入账。

---

## 3. 端点

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| `POST` | `/api/v1/refunds` | 发起退款申请（只传 bizType/bizNo，**金额由服务端算**） | JWT（会员） |
| `GET` | `/api/v1/refunds` | 我的退款单（按会员隔离） | JWT（会员） |
| `POST` | `/api/v1/refunds/{id}/cancel` | 撤回待审申请 | JWT（会员，校验归属） |
| `GET` | `/api/v1/wallet/cards` | 我的次卡（含卡名、购买实付、可退标识） | JWT（会员） |
| `GET` | `/admin/refunds?status=APPLYING` | 退款单列表 / 待审队列（分页） | AdminToken |
| `GET` | `/admin/refunds/pending-count` | **待审数量（后台角标数据源）** | AdminToken |
| `POST` | `/admin/refunds/{id}/audit` | 审核（`approved=false` 走驳回，必须填理由） | AdminToken |
| `POST` | `/admin/refunds/manual` | 店长人工退款（客诉补偿等，直接生效） | AdminToken |

`/api/v1/refunds` 与 `/admin/refunds` 已纳入 `RateLimitFilter` 的**严格配额**（10s / 5 次），与登录、充值、核销同级；
商品核销根 `/admin/orders` 同样纳入（8 位自提码与 6 位核销码一样可被枚举）。
`/admin/refunds/pending-count` 本身不受限流约束 —— `RateLimitFilter.shouldNotFilter` 只拦写方法（POST/PUT/PATCH/DELETE），
GET 一律放行，所以后台每 30s 的角标轮询不会撞上配额。

> `pending-count` 返回的计数值经 Jackson 的 `Long → String` 序列化后是**字符串**（见 §8 的取舍说明），
> 前端需 `Number(data)` 再比较。

### 申请报文示例

```json
POST /api/v1/refunds
{ "bizType": "CARD", "bizNo": "CD202609151234567890", "reason": "整卡未使用，申请退款" }
```

商品订单退款同理：

```json
POST /api/v1/refunds
{ "bizType": "GOODS", "bizNo": "GD20260915000123", "reason": "买错规格" }
```

**报文里没有 amount 字段，这是有意的**：金额由服务端按来源业务单据计算。客户端能指定金额，就等于能给自己印钱。
`bizNo` 对 `GOODS` 是订单号（`order_no`，业务可读字符串），不是雪花主键 —— 订单号才是对外契约，雪花 id 只在库内流转。

---

## 4. 数据模型变更

| 表 | 变更 | 原因 |
|---|---|---|
| `card` | 新增 `paid_amount DECIMAL(10,2)` | 退款必须按**购买实付**退，而不是 `card_type.price`。卡类型改价后用现行价退会退错金额（老卡多退、新卡少退） |
| `refund_apply` | 新增 `principal_amt`、`gift_amt`、`finished_at` | 回写入余额时的金额拆分与终态时间，退款单自身即可完成对账 |
| `refund_apply` | 新增索引 `(member_id,status)`、`(status,id)`、`(biz_type,biz_no)` | 会员侧筛选、后台待审队列、按来源单据反查 |
| `goods_order` | 新增 `principal_paid`、`gift_paid` | 与 `card` 同源的**支付构成快照**：下单支付时按会员账户累计比例拆分并写入，退款按原比例原样退回 |
| `goods_order` | 新增 `verify_code VARCHAR(8)` + 唯一索引 | 自提核销码（6 位序号 + 2 位校验位）。唯一索引是「同一码不会被核销两次」的最后防线 |
| `wx_subscribe_quota` | 新表 | 微信订阅消息是**一次性授权**，必须按 `(openid, template_id)` 累计剩余次数；归零后降级为仅角标 |
| `notify_log` | 新表，`(biz_no, template_id)` 唯一 | 推送留痕；唯一索引保证同一笔退款不会把店长轰炸两次 |

**为什么 `principal_paid`/`gift_paid` 必须快照而不能事后按余额比例算**：会员两次充值的赠送比例可能不同
（充 200 送 20 是 9.09%，充 500 送 100 是 16.67%）。如果退款时按「当前余额里本金占比」反推，
在余额构成变化后（比如中间又收了退款、又消费了几笔）算出的本金/赠送金拆分将与顾客实际支付的构成不符，
账本上的累计本金/赠送金会缓慢漂移 —— 变现口径下就是「赠送金被洗成本金」。

增量脚本：`petshop-admin/src/main/resources/db/04_migration.sql`（退款只入余额）、
`05_migration.sql`（商品订单核销码 + 店长订阅消息两张表）；全新安装直接跑 `01_schema.sql`。

> `04_migration.sql` 里有一条**回填**：历史卡的 `paid_amount` 只能按当时的卡类型现行价近似。
> 如果卡类型期间改过价，这批卡的退款金额需要人工核对一遍。

---

## 5. 不变量与防线

| 风险 | 防线 | 位置 |
|---|---|---|
| 重复入账 | 退款单状态 CAS（主）；账本同号 `REFUND_IN` 流水查重（兜底） | `RefundApplyMapper.casAudit`、`BalanceService.refundToBalance` |
| 同一张卡被退两次 | 卡 CAS：`status='USING' AND remain_times=total_times AND pre_deduct_count=0` | `CardMapper.refundCascade` |
| 同一笔订单被退两次 | 订单 CAS：`status='PAID'`；同时唯一索引挡「同一订单重复提交申请」 | `GoodsOrderMapper.markRefunded`、`idx_refund_biz` |
| 库存重复回补 | 库存回补与订单置位在**同一事务**，CAS 失败即整体回滚，不会「补了库存但订单没改」 | `MallOrderService.restoreStock` |
| 自提码被撞码 | 8 位码带 2 位校验位（`校验位 == 序号 % 97`），且 `verify_code` 有唯一索引 | `VerifyCodeUtil`、`uk_order_verify_code` |
| 客户端自定金额 | 申请报文无金额字段，服务端按 `paid_amount` 算 | `RefundService.resolveRefundableAmount` |
| 越权退款 / 撤回他人单 | 会员侧一律按 `memberId` 过滤，越权与不存在返回同一错误码 | `RefundService.apply/cancel/requireOwn` |
| 审核时业务状态已变（卡被用了 / 订单已核销） | 审核时**重算**可退金额并重新校验来源单据，不沿用申请时的快照 | `RefundService.audit` → `resolveSource` |
| 无操作人留痕 | 审核 / 人工退款的 `operator` 必填，驳回理由必填 | `RefundService.audit/manualRefund` |
| 账实不符 | 账本记录 `+principal/+gift`，余额恒等式 `balance == Σ(principalAmt+giftAmt)` 继续成立 | 回归用例 ⑥ |

### 一处口径决定（产品确认后可一行翻转）

**过期但一次没用的卡，同样允许申请退款。**（`CardService.isRefundable` 有意不含 `valid_until` 判断）

理由：顾客没消费的钱应当可退，且退款本身就是审核制 —— 门店若认定"过期作废"，在审核环节驳回即可；
把过期卡直接挡在门外，顾客连申请入口都没有，在单用途预付卡的监管口径下更容易被认定为无效格式条款。
若产品最终决定"过期一律不退"，加回 `valid_until` 判断即可，前端展示会自动跟随。

### 另一处有意不做的联动

`member.total_paid`（累计实付）与会员等级在本次**没有**被退款冲减。原因：`total_paid` 这个字段全项目
**目前没有任何写入路径**，等级体系尚未落地；在没有写入方的情况下改它只是制造"半实现"。
PRD 那句"退款相应冲减累计实付并可能触发降级"应随等级体系那一期一起做。

---

## 6. 已接入 / 未接入的退款来源

| 来源 | 状态 | 说明 |
|---|---|---|
| `CARD` 次卡退款 | ✅ 已接入 | 未使用（`remain_times = total_times`）且无预扣的卡，按 `paid_amount` 整卡退回余额 |
| `GOODS` 商品订单退款 | ✅ 已接入（v1.2） | 仅 `status = PAID`（已支付未核销）可申请，按订单快照 `principal_paid`/`gift_paid` 原比例退回；审核通过时库存回补、订单置 `REFUNDED` |
| `MANUAL` 店长人工退款 | ✅ 已接入 | 直接生效并留痕，用于客诉补偿等无法走单据的场景 |
| `APPOINT` 预约退款 | ❌ 未接入 | 当前担保只有 `BALANCE / CARD / NONE`，取消走解冻即可，无资金需退 |
| `SETTLE` 结算多收退款 | ❌ 未接入 | 结算链路目前只有扣款方向，无微信渠道分支 |

`APPOINT` / `SETTLE` 两类**在后端是显式拒绝**（返回"该类型退款尚未开放线上申请，请联系门店人工处理"），**不是静默忽略**，
避免出现"提交成功但没人处理"的假象。这两项要真正开放，前置依赖是各自业务模块先落地。

### 商品订单为什么只允许「整单退」

不做部分退是**有意的**：部分退需要按 `order_item` 分摊订单级优惠（满减、活动价）到行，
再把退款金额按行反推回本金/赠送金构成。这套分摊规则一旦与结算侧的优惠分摊不一致，
就会出现「各笔部分退加起来 ≠ 整单金额」的对不上账。当前没有优惠分摊引擎，先按整单退，规则简单且可验证。
要开放部分退，前置是先把优惠分摊下沉为可复用的引擎（见 `petshop-data/CHANGELOG.md` 待产品拍板 #5）。

### 商品订单退款与「待自提」的关系

已核销（`VERIFIED`）的订单**不支持**线上退款 —— 货已经拿走了。这类客诉走 `MANUAL` 人工退款并留痕。
未支付（`CREATED`）的订单不需要退款，取消即可（取消即时回补库存）。

---

## 7. 后台提示机制（「后台会有提示」是怎么做的）

会员提交申请后，后台侧有**两条**提醒路径，互为兜底：

| 通道 | 实现 | 触发方式 | 失败时 |
|---|---|---|---|
| **后台角标**（主） | `GET /admin/refunds/pending-count` 返回 `APPLYING` 计数 | 后台前端每 30s 轮询一次，侧栏/底栏「退款审核」菜单上显示红色数字 | 不会失败（纯本地查询） |
| **微信订阅消息**（辅） | `WxSubscribeService` 推给店长的 openid | `RefundAppliedEvent` → `@TransactionalEventListener(AFTER_COMMIT)` → 推送 | 静默降级为只有角标，**不影响退款主流程** |

### 为什么用 `AFTER_COMMIT` 而不是直接调

推送必须在退款单**事务提交之后**才发。若在事务内发，事务稍后回滚就会出现「店长收到通知、后台却查不到这笔退款」的幽灵消息。
`@TransactionalEventListener(phase = AFTER_COMMIT)` 保证只有真正落库的申请才会触发推送。

### 微信订阅消息是「一次性授权」，必须配额记账

微信的订阅消息不同于模板消息：**店长每点一次「允许通知」，只换到一次推送额度**。
所以不能假设「配了就永远能推」，必须按 `(openid, template_id)` 记剩余次数（表 `wx_subscribe_quota`），推成功扣一次。
额度归零后自动降级为只发角标 —— 这是**设计上的降级**，不是故障：

```
remain_count > 0 ──推送成功──► remain_count - 1
remain_count = 0 ──► notify_log 记 SKIPPED_NO_QUOTA，跳过发送（角标照常）
未配置 appid/secret ──► notify_log 记 SKIPPED_NOT_CONFIGURED（本地/测试环境常态）
```

`notify_log` 的 `(biz_no, template_id)` 唯一索引是**防重复推送**的防线：
即便事件被重复投递，第二次 INSERT 撞唯一索引即跳过，店长不会被同一笔退款轰炸两次。

### 店长授权入口

小程序端 `POST /api/v1/notify/subscribe` 记录店长本次授权；后台管理端 `/manage/` 的「通知自检」页
显示当前推送配置状态（是否配了 appid/secret/模板 ID、剩余额度、最近推送记录）。

---

## 8. 后台管理端（`/manage/`）

原项目只有小程序端，管理员审核退款没有落脚点。本次新建了后台管理端，形态选择如下：

| 维度 | 选择 | 理由 |
|---|---|---|
| 技术栈 | **纯静态 HTML/CSS/JS，由 Spring Boot 托管于 `/manage/`** | 零构建、零 Node 依赖。后台只有店长用，不值得为一个内部工具引入前端构建链 |
| 鉴权 | `AdminToken`（`X-Admin-Token` 头，与既有 `/admin/*` 端点同源） | 沿用现有约定，不新造一套 |
| 主题 | 三套（标准 / 喵喵 / 汪汪），色值**与小程序同源** | 避免后台与小程序两套视觉语言漂移 |
| 响应式 | 三档：手机底栏 / 平板抽屉 / 桌面侧栏，断点 1024px | 店长会在手机上处理退款审核，不能只做桌面 |

功能页面：看板（待审数、今日数据）、**退款审核**（列表 + 审核弹层 + 通过/驳回）、订单管理（含自提核销）、
预约管理、核销台、到店结算、通知自检。

**审核交互的一条硬约束**：点「通过并退款」后，前端**不能**乐观更新 UI 后再等响应 ——
必须等服务端返回才刷新列表与角标。因为退款是资金动作，乐观更新会造成「界面显示已退款、实际失败」的假象。

三主题与三端截图见 `/Users/andy/Workbuddy/wbWorkSpaces/pet-store/petshop-admin-ui/`（17 张）。

---

1. **执行增量脚本** `04_migration.sql`（新增列 + 索引 + 卡价回填）。
2. **人工核对回填的 `paid_amount`**：期间改过价的卡类型，按现行价回填的金额可能不等于顾客实付。
3. **后台账号体系**：`audit` / `manual` 的 `operator` 目前靠请求体自报（沿用 `settle_bill.operator` 的现状），
   接入若依账号体系后必须改为从登录态取，否则退款留痕不具备审计效力。
4. **次卡购买支付闭环**：退款金额以 `paid_amount` 为准，而 `purchase()` 目前仍无收款动作
   （见 `CardService.purchase` 的 TODO）。接入支付后应把 `paid_amount` 改为**真实实付**，
   而不是 `card_type.price`。

---

## 9. 上线前必做

1. **执行增量脚本** `04_migration.sql`（退款字段 + 索引 + 卡价回填）、`05_migration.sql`（订单核销码 + 通知两张表）。
   注意 `05` 里订单核销码是「先加可空列 → 回填 → 再收紧 NOT NULL」三步，别跳过中间的回填。
2. **人工核对回填的 `paid_amount`**：期间改过价的卡类型，按现行价回填的金额可能不等于顾客实付。
3. **后台账号体系**：`audit` / `manual` 的 `operator` 目前靠请求体自报（沿用 `settle_bill.operator` 的现状），
   接入账号体系后必须改为从登录态取，否则退款留痕不具备审计效力。
   同时 `AdminToken` 是单一共享密钥、无操作人身份，只能当「内网闸门」用，不能当审计依据。
4. **次卡购买支付闭环**：退款金额以 `paid_amount` 为准，而 `purchase()` 目前仍无收款动作
   （见 `CardService.purchase` 的 TODO）。接入支付后应把 `paid_amount` 改为**真实实付**，
   而不是 `card_type.price`。
5. **配置微信订阅消息**：`PETSHOP_WX_*`（appid / secret / 模板 ID / 店长 openid）未配置时推送会
   落 `SKIPPED_NOT_CONFIGURED`，此时只有后台角标生效。上线前需在后台「通知自检」页确认状态为已配置。

---

## 10. 需同步的设计文档（当前文档口径与实现冲突）

| 文档 | 位置 | 现状 | 应改为 |
|---|---|---|---|
| PRD | 4.5 会员详情 / 会员管理 | "通过后由后台发起微信原路退款，1–3 个工作日到账" | 改为"退回账户余额，即时到账"，并注明余额不支持提现 |
| PRD | 4.5 会员详情（另一处） | "手动原路退回" | 与上一条统一（同一功能文档内两种口径） |
| PRD | 4.3 订单管理 / 4.7 退款规则 | "微信付退微信（1-3 个工作日到账）；余额付退余额" | 改为"一律退回可用余额" |
| PRD | 5.x 余额退款原型页 | "赠送部分 ¥59.45 不折现；审核通过后 1–3 个工作日原路退回微信" | 同上；且该页的"可退本金折算"在不可提现的口径下已无实际意义，建议改为展示"余额可退政策"说明 |
| 技术设计 | 3-6 支付与退款 | "调微信退款 API（或余额即时退回）" | 明确当前只走余额，微信退款列为二期 |
| 技术设计 | 3-3 表清单 | `refund_apply` 无字段定义 | 补字段（含 `principal_amt`/`gift_amt`/`finished_at`）与状态机 |
| 技术设计 | 支付单状态 | `CREATED/SUCCESS/CLOSED/REFUNDED` | 当前口径下 `REFUNDED` 暂不使用；若二期接微信退款，需补"退款处理中/退款失败" |
| 技术设计 | 缺章节 | 无 `/manage/` 后台管理端设计 | 补：托管方式、AdminToken 鉴权、三主题与响应式断点、审核交互不做乐观更新 |
| 技术设计 | 缺章节 | 无商品订单链路设计 | 补：下单锁库存 CAS、支付构成快照、自提核销码生成与校验、整单退款与库存回补 |
| PRD | 缺章节 | 无商城模块（商品列表/详情/下单/我的订单） | 补商城模块需求；商品与价格以 `petshop-data/pricing.json` 为准 |
| UI 稿 | 我的 / 钱包 | 无退款相关界面 | 已补：钱包页"我的次卡（含申请退款）"与"退款记录"（已按此实现）；另新增商城页、商品详情页、我的订单页 |
| UI 稿 | 商城页 | 硬编码 4 个虚构商品、分类为"主粮/玩具/清洁护理" | 已替换为 `pricing.json` 的真实 6 商品与正确分类（洗护/零食/用品/护理/保健），并纳入 `validate.mjs` 断言 |

---

## 11. 验证证据

后端全量回归（`mvn test`，profile=test / H2）：

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0  -- RefundFlowTest（退款主链路 + 真并发）
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  -- MallOrderFlowTest（商品订单：下单/支付/取消/核销/退款）
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  -- SecurityRegressionTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0  -- DiscountEngineTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0  -- WalletFlowTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0  -- AppointmentFlowTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0  -- PaginationRegressionTest（本轮新增）
Tests run:  3, Failures: 0, Errors: 0, Skipped: 0  -- SnowflakeIdContractTest（本轮新增）
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0  -- SettleConcurrencyTest
────────────────────────────────────────────────
Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

小程序 `tsc --noEmit`：零错误。接口契约核对脚本（`petshop_mp_contract.py`）逐字段一致。

后台管理端：17 张实机截图（三主题 × 三端 + 审核通过前后 + 通知自检）见 `petshop-admin-ui/`。

端到端脚本 `petshop_e2e.py` 17 步全过（按业务码断言，非 HTTP 状态码 —— 本项目业务异常走 `200 + code`，
鉴权失败走 `401`；把 409 当预期是错误的写法）。


`RefundFlowTest` 里最关键的两条是资金风险点，都做了真并发：

- `concurrentAuditCreditsOnlyOnce` —— 双线程同时审核同一张退款单：一个成功、一个被 CAS 拒绝，余额只加一次、流水只有一条
- `balanceAlwaysEqualsLedgerSumAfterRefund` —— 混合流水（充值 220 + 退款 1080）后，余额恒等于账本累加

---

## 12. 本轮发现并修复的两个实现层缺陷

这两个缺陷的危险之处在于**表现像「后端数据被删了」，实际是前端拿到的数据被静默破坏** ——
如果没有做「浏览器真实点击」这一步验证，只跑接口测试是发现不了的（接口是对的，错在序列化与分页装配）。

### 12.1 MyBatis-Plus 分页插件缺失（分页静默失效）

**症状**：演示数据时 `size=5` 却返回 7 条，且 `total=0`。

**根因**：全项目没有注册 `PaginationInnerInterceptor`。MP 在缺插件时**不报错**：
count 查询不执行（`total` 恒 0）、`LIMIT` 不拼（返回全表）、`size` 上限收敛空转。
所有分页接口都处于「看起来能用、实际没用」的状态。

**修复**：新增 `MybatisPlusConfig`，注册 `PaginationInnerInterceptor(DbType.MYSQL)`，`maxLimit=100`、`overflow=false`。

**防回归**：新增 `PaginationRegressionTest`（4 例）断言 `records.size()==size` 且 `total>0`，
并验证 `size=99999` 被截断到 100。并做了**负向验证** —— 临时移除该 Config 后，3 项断言立即失败（返回 5/9 条而非 2/3 条），
证明测试确实咬得住这个 bug，不是恒真的假绿。

### 12.2 雪花 ID 精度丢失（退款单「不存在」）

**症状**：浏览器真实点「通过并退款」，toast 提示「退款单不存在」。

**根因**：MyBatis-Plus 雪花主键是 19 位十进制，超出 JS `Number.MAX_SAFE_INTEGER`（16 位有效）。
JSON 以数字下发时 JS 会**静默截断**低 3 位 —— 于是前端回传的 id 与库里的 id 不是同一个数，
后端按这个 id 查不到记录。注意：`total`、`pendingCount` 这类计数器同样是 `Long`，也一起变成了字符串。

**修复**：`JacksonConfig` 把 `Long`/`long` 一律以字符串序列化（`ToStringSerializer`）；
前端（后台 `app.js` + 小程序各 api 模块）去掉所有 `Number(id)` 转换，主键全程当字符串传递；
小程序新增 `SnowflakeId = string` 类型别名，让 TS 在编译期就挡住「把 id 当数字算」的写法。

**代价（已知并接受）**：计数器也变字符串，前端算术需显式 `Number()`。已在 `SnowflakeIdContractTest` 固化这一契约，
并用测试注释显式记录这个取舍，避免后人「顺手」把计数器改回数字型。

**防回归**：新增 `SnowflakeIdContractTest`（3 例）：断言 19 位 id 出参是带引号字符串且原样往返，
Long 包装类型与基本类型都走字符串，并把「计数器也是字符串」这条写死为契约。

