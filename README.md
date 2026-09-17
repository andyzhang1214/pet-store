# 爪爪星球 · 宠物门店管理系统

面向单店 / 小型连锁宠物门店的经营管理系统。覆盖会员储值、次卡、宠物健康档案、服务预约、
到店结算、商品商城与退款审核，交付形态为**后台管理台**与**微信小程序**两端。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.3.5 · MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8（生产） / H2 内存库（`demo` 零依赖运行） |
| 后台前端 | 原生 JS SPA（无框架、无构建步骤），随 Spring Boot 静态资源发布 |
| 小程序 | 微信小程序 · TypeScript（TS-only，仓库不落编译产物） |

## 仓库结构

```
.
├── petshop-server/            Spring Boot 多模块后端（含后台前端静态资源）
│   ├── petshop-common/          通用：异常、枚举、工具、上下文
│   ├── petshop-member/          会员、宠物、健康档案、会员等级
│   ├── petshop-wallet/          储值余额、次卡、充值档位、退款申请
│   ├── petshop-booking/         服务价目、时段网格、预约
│   ├── petshop-mall/            商品、库存、订单、核销
│   ├── petshop-settle/          到店结算、折扣引擎、退款单
│   └── petshop-admin/           启动模块：Controller、鉴权、定时任务、静态资源
├── petshop-miniapp/          微信小程序（TypeScript 源码）
├── petshop-admin-ui/         后台逐页验收截图
├── petshop-prd/              产品需求文档
├── petshop-plan/             项目计划
├── petshop-tech-design/      技术设计（含 ER 图与链路图）
├── petshop-ui/               UI 设计稿
├── petshop-data/             价目数据与一致性校验脚本
├── docs/                     代码审查报告、方案记录
└── 爪爪星球使用说明/           对外使用说明（含 pptx）
```

## 快速开始

### 1. 后端 + 后台管理台（零外部依赖，建议先跑这个）

不需要装 MySQL：`demo` profile 用 H2 内存库并灌入演示数据。

```bash
# 先 install 一次兄弟模块，否则 -pl petshop-admin 会取到 ~/.m2 里的旧 jar，报跨模块 NoSuchMethodError
mvn -f petshop-server/pom.xml install -DskipTests

# dev 声明"这是开发类环境"，demo 提供 H2 + 演示数据，两者必须一起用
SPRING_PROFILES_ACTIVE=dev,demo \
  mvn -f petshop-server/pom.xml -Pdemo -pl petshop-admin spring-boot:run
```

打开 <http://localhost:8080/manage/index.html>。

- 初始账号 `manager` / `admin12345`，**首次登录强制改密**（服务端拦截，不是前端提示）。
- H2 是内存库，进程重启即清空；演示数据每次启动重新灌入。

> **Java 21 是硬要求。** 若本机 `mvn` 指向更高版本 JDK（如 JDK 26），Lombok 注解处理会静默失效，
> 报出大量"找不到 setter/getter"的假编译错误。构建前把 `JAVA_HOME` 指到 JDK 21 即可。

### 2. 小程序

```bash
cd petshop-miniapp
npm install
```

用**微信开发者工具打开 `petshop-miniapp/`**（项目根，不是 `miniprogram/`）。

```bash
npm run type-check              # tsc --noEmit
node scripts/check-wxml.mjs     # WXML 绑定 / wx:for 静态检查
```

小程序默认连 `http://127.0.0.1:8080`，`project.config.json` 已关闭域名校验（`urlCheck: false`），
模拟器里可直接直连本地后端。

## 测试

```bash
mvn -f petshop-server/pom.xml test
```

测试使用独立的 H2 库与随机端口；`@Scheduled` 任务在测试中初始延迟 1 小时，不会在后台改库。

> **新增测试类后请跑全量 `mvn test`，不要只跑 `-Dtest=<新类>`。**
> 部分测试需要自带独立库名——同一个 JVM 里一旦出现第二个 ApplicationContext，建表脚本会重放。
> 单跑只有一个上下文，会得到"假绿"，全量才暴露问题。

## 环境变量

默认 profile 即 `prod`（fail-closed）：漏配环境变量会**拒绝启动**，而不是裸奔上线。

| 变量 | 说明 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接 |
| `PETSHOP_JWT_SECRET` | 会员令牌签名密钥（建议 ≥32 字节强随机） |
| `PETSHOP_ADMIN_TOKEN` | 后台管理令牌 |
| `PETSHOP_ADMIN_BOOTSTRAP_USERNAME` / `_PASSWORD` | 初始店长账号（仅 `admin_user` 为空时创建一次） |
| `WX_APPID` / `WX_SECRET` | 微信小程序凭据 |
| `PETSHOP_WX_MOCK` | 生产必须 `false` |
| `PETSHOP_ADMIN_SHARED_TOKEN_ENABLED` | 生产必须 `false` |
| `PETSHOP_TRUST_FORWARDED_HEADERS` | 仅在会剥离外部 `X-Forwarded-For` 的可信网关之后才置 `true` |

## 数据库脚本

`petshop-admin/src/main/resources/db/` 下的脚本按用途分段：

- `01_schema.sql` — 建表与索引（`CREATE TABLE/INDEX IF NOT EXISTS`，可重复执行）
- `02_seed.sql` — 配置域基础数据（服务、规格、价目、卡型、档位、门店规则）
- `03`–`08_migration.sql` — 结构演进，用 `information_schema` 守卫写成可重放
- `09_demo.sql` — 业务演示数据，**仅 demo 环境加载**（与迁移号段刻意分开）

## 文档

| 内容 | 位置 |
| --- | --- |
| 产品需求 | `petshop-prd/petshop-prd.html` |
| 技术设计 | `petshop-tech-design/petshop-tech-design.html` |
| 项目计划 | `petshop-plan/petshop-plan.html` |
| 后台逐页截图 | `petshop-admin-ui/` |
| 代码审查与修复记录 | `docs/reviews/` |
| 对外使用说明 | `爪爪星球使用说明/` |
