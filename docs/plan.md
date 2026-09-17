# 宠物店小程序文档套件计划

四份自包含 HTML 报告：`petshop-plan`（项目规划）、`petshop-prd`（产品需求文档）、`petshop-tech-design`（技术设计文档）、`petshop-ui`（UI 设计稿）。

Color preset: Warm Editorial — cue: 宠物店温暖生活服务主题，陶土橙暖色系
Intro mode: contained — cue: 正式规划/需求/技术文档，无 Hero 图，走无图 Intro 路径

## 各报告结构

- petshop-plan：概述与目标、用户与角色、功能范围与优先级（MVP/P1/P2 表）、关键决策记录、里程碑排期（6 周表）、风险与对策。表格主导，无图表、无图。
- petshop-prd：产品概述（背景/角色/术语）→ 信息架构 → C 端 6 个功能模块（3.1–3.6）→ B 端 9 个功能模块（4.1–4.9，合计 15 个；每个模块嵌入交互原型 Demo）→ 关键业务流程（Mermaid 流程图 4 张）→ 状态机（Mermaid 状态图 3 张）→ 非功能需求。原型 Demo 与 Mermaid 主导。
- petshop-tech-design：总体架构（Mermaid 架构图）、技术选型、数据库设计（核心关系图 25 张表 + 关键表字段表）、核心技术方案（余额冻结、次卡预扣、时段并发、折扣引擎、核销码、支付幂等，含 2 张时序图）、API 设计（分组端点表）、工程结构、安全、部署。Mermaid 与表格主导。
- petshop-ui：三主题（标准 / 喵喵 / 汪汪）小程序页面稿，含首页、价目表、预约、商城、我的与设计规范对比。页面元素全部标注一期范围；与 PRD 3.2 不一致的形态差异（首页、价目表、门店信息位置）在稿首列为「待产品拍板」，未定前以 PRD 为准。

## 单一事实来源（重要）

**价格、档位、折扣、演示数值不再内嵌在各文档中，唯一归属地是 `petshop-data/pricing.json`。**

| 内容类型 | 唯一归属 | 其他文档的做法 |
|---|---|---|
| 业务规则、字段规则、状态机 | PRD | 技术设计只引用条目编号，不复述 |
| 价格、档位、折扣、示例数值 | `petshop-data/pricing.json` | 三份文档全部从中取值渲染 |
| 表结构、SQL、并发方案 | 技术设计 | PRD 不复述实现 |

配套件：

- `petshop-data/pricing.json` — 数据集本体（服务 5 项 / 规格档 8 项 / 价格矩阵 40 项 / 次卡 2 项 / 充值档位 3 项 / 会员等级 3 级 / 退款政策）。
- `petshop-data/validate.mjs` — 三方一致性断言：**数据集 ↔ `02_seed.sql` 种子数据 ↔ PRD 文档展示值**。运行 `node petshop-data/validate.mjs`，退出码非 0 即存在漂移。**改价格必须同时改数据集与种子数据，否则校验失败。**
- `petshop-data/CHANGELOG.md` — 变更记录（含仍未处理 / 待产品拍板清单）。

PRD 中三张被校验的表格带稳定锚点 id：`price-matrix`（分档价格矩阵）、`card-type-table`（次卡类型）、`recharge-tier-table`（充值档位）。改动这三张表的结构时需同步 `validate.mjs` 的解析逻辑。

## 资源约定

- Mermaid：`petshop-prd` 与 `petshop-tech-design` 各自 `_shared/js/mermaid.min.js`，初始化在各自 `assets/diagrams.js`，主题色使用 Warm Editorial 已解析色值（#B8551D / #F59E42 / #D97757 / #8E6E63 及暖中性色）。
- PRD 原型 Demo 交互脚本：`petshop-prd/assets/demos.js`（事件委托 + 各 Demo 状态切换）。
- 字体：InstrumentSans（拉丁 Sans）+ GeistMono（Mono），中文走系统 PingFang SC 回退。
- 无 ECharts（无定量图表）。
- 金额展示统一人民币；示例店名「爪印宠物生活馆」为占位名（种子数据中的店名为「爪爪星球宠物生活馆」，属演示配置差异）。
- 金额口径：`DECIMAL(10,2)`、`HALF_UP` 两位小数；退款一律退入可用余额，不提供退微信 / 退现金出口。
