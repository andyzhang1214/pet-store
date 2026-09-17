# DESIGN.md — 爪爪星球使用说明

## 画布与母版（1280×720，A/B/C 三区）
- A 标题块：0–120px，主标题 34px bold，左侧 60px padding。
- B 内容区：120–660px。
- C 页脚条：660–720px，左「爪爪星球 · 使用说明」14px，右页码 NN / 10。
- 页面 padding：上下 20px，左右 60px。

## 色彩池（4 hex，与产品品牌一致）
| 角色 | hex | 面积 |
|---|---|---|
| 主色 陶土橙 | #B8551D | ≤30%（标题点缀、焦点块、CTA） |
| 辅色 奶油底 | #F5EBDD | ≤40%（卡片底、分区底） |
| 强调 松绿 | #4E7A62 | ≤10%（成功/安全语义、锚点数字旁标签） |
| 文本 深咖 | #2B241F | 文本与页脚（中性色不计入比例） |
页面背景：#FFFFFF。Hero 页强调色可至 15%。

## 字体
- 标题：PingFang SC，bold。
- 正文：PingFang SC，regular。
- 锚点数字：bold 加大（56–72px），与正文同族不同重。

## 配图清单（全部 P0 材料图 + 1 张生图，扁平插画风统一）
| 文件 | 来源 | 内容 | 用页 | 核对 |
|---|---|---|---|---|
| cover_hero.png | ImageGen | 店员扫顾客手机二维码+柯基橘猫 | P1 L1 | ✅ |
| verify_desk.png | 产品截图 | 核销台（含新扫码按钮） | P6 L1 | ✅ |
| qr_sheet.png | 产品截图 | 出码弹层（二维码+8位码） | P6 L2 | ✅ |
| verify_ok.png | 产品截图 | 核销成功结果卡 | P5 L2 | ✅ |
| settle_done.png | 产品截图 | 余额结算完成 | P5 L1 | ✅ |
| audit_log.png | 产品截图 | 配置变更审计 | P8 L1 | ✅ |
| force_pwd.png | 产品截图 | 首次登录强制改密 | P8 L2 | ✅ |
| goods.png | 产品截图 | 商品与库存 | P4 L2 | ✅ |
| members.png | 产品截图 | 会员与健康看板 | P4 L2 | ✅ |
| pricing.png | 产品截图 | 服务价目矩阵 | P9 L2 | ✅ |

## 页面映射表
| # | 文件 | 类型 | 角色 | 版式 | L1 | 字数 | 留白 | 色彩 | 关键约束 |
|---|---|---|---|---|---|---|---|---|---|
| 01 | 01.slide | cover | hero | 全幅插画+骑线标题 | cover_hero.png | 30 | 30% | 主色15% | 标题压图需蒙层 |
| 02 | 02.slide | content | supporting | 三列端卡 | — | 320 | 25% | 辅色35% | 三卡等高对齐 |
| 03 | 03.slide | content | supporting | 2×3 功能卡组 | — | 380 | 22% | 辅色30% | 每卡≥60字 |
| 04 | 04.slide | content | supporting | 左列表+右双截图 | goods/members | 300 | 22% | 辅色30% | 图占右侧55% |
| 05 | 05.slide | content | hero | 横向流程条+双截图 | settle_done | 260 | 25% | 强调15% | 流程节点5个 |
| 06 | 06.slide | content | hero | 左大截图+右方式卡 | verify_desk | 280 | 25% | 强调15% | 三种方式卡 |
| 07 | 07.slide | content | supporting | 上流程下数字 | — | 300 | 25% | 强调10% | 锚点数字330→200 |
| 08 | 08.slide | content | supporting | 左截图+右要点 | audit_log | 280 | 24% | 辅色28% | 截图占55% |
| 09 | 09.slide | content | supporting | 左架构列表+右截图 | pricing | 320 | 22% | 辅色30% | 模块清单7行 |
| 10 | 10.slide | ending | supporting | 命令块+要点列 | — | 220 | 28% | 主色12% | 代码块样式 |
