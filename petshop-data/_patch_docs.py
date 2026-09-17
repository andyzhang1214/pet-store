#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【一次性变更记录，已于 2026-09-15 执行完毕，请勿重跑】
当时按 petshop-data/pricing.json（唯一事实来源）批量修正四份设计文档，共 70 处替换。
每一处替换都带断言（old 必须精确命中 N 次），任一不命中则该文件整体不写入。

保留此文件是为了让「文档到底改了什么」可被逐条复查；由于文档已改写完毕，
现在重跑必然全部断言失败——这是预期行为，不是脚本坏了。
后续的价格维护请改 petshop-data/pricing.json + 02_seed.sql，再跑 validate.mjs 校验。
用法（仅存档参考）：python3 _patch_docs.py --dry-run
"""
import json
import pathlib
import sys

ROOT = pathlib.Path("/Users/andy/Workbuddy/wbWorkSpaces/pet-store")
DATA = json.loads((ROOT / "petshop-data/pricing.json").read_text(encoding="utf-8"))
DRY = "--dry-run" in sys.argv

PRD = ROOT / "petshop-prd/petshop-prd.html"
TECH = ROOT / "petshop-tech-design/petshop-tech-design.html"
PLAN = ROOT / "petshop-plan/petshop-plan.html"
UI = ROOT / "petshop-ui/petshop-ui.html"
PLAN_MD = ROOT / "plan.md"

all_errors = []


def apply(path, edits, label):
    text = path.read_text(encoding="utf-8")
    errs = []
    out = text
    for i, (old, new, cnt) in enumerate(edits):
        n = out.count(old)
        if n != cnt:
            errs.append(f"  第 {i+1} 处：期望 {cnt} 次，实际 {n} 次 → {old[:100]!r}")
            continue
        out = out.replace(old, new)
    if errs:
        all_errors.extend(f"[{label}] {e}" for e in errs)
        print(f"  FAIL {label}  ({len(errs)}/{len(edits)} 处未命中，未写入)")
    else:
        if not DRY:
            path.write_text(out, encoding="utf-8")
        print(f"  OK   {label}  ({len(edits)} 处){'  [dry-run]' if DRY else ''}")


# ============================ PRD ============================
PRD_EDITS = [
# --- P0-3 模块数 ---
("本文档定义顾客端微信小程序与商家 Web 管理后台共 14 个功能模块的需求：",
 "本文档定义顾客端微信小程序（C 端 6 个模块，3.1–3.6）与商家 Web 管理后台（9 个模块，4.1–4.9）共 15 个功能模块的需求：", 1),

# --- P0-4 猫价目表 ---
("""                      <div class="mp-row"><span class="mp-title">猫咪洗澡 <span class="mp-tag--gray mp-tag">60 分钟</span><span class="anno">5</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">短毛猫</span><span class="mp-price">¥88</span></div>
                      <div class="mp-row"><span class="mp-sub">长毛猫</span><span class="mp-price">¥128</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">猫咪精致洗护 <span class="mp-tag--gray mp-tag">75 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">短毛猫</span><span class="mp-price">¥148</span></div>
                      <div class="mp-row"><span class="mp-sub">长毛猫</span><span class="mp-price">¥198</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">猫咪美容修剪 <span class="mp-tag--gray mp-tag">90 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">短毛猫</span><span class="mp-price">¥168</span></div>
                      <div class="mp-row"><span class="mp-sub">长毛猫</span><span class="mp-price">¥228</span></div>
                    </div>""",
 """                      <div class="mp-row"><span class="mp-title">基础洗护套餐 <span class="mp-tag--gray mp-tag">60 分钟</span><span class="anno">5</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 短毛</span><span class="mp-price">¥120</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 中长毛</span><span class="mp-price">¥150</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 长毛</span><span class="mp-price">¥180</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">精致美容 <span class="mp-tag--gray mp-tag">120 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 短毛</span><span class="mp-price">¥260</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 中长毛</span><span class="mp-price">¥320</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 长毛</span><span class="mp-price">¥380</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">药浴 SPA <span class="mp-tag--gray mp-tag">120 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 短毛</span><span class="mp-price">¥200</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 中长毛</span><span class="mp-price">¥240</span></div>
                      <div class="mp-row"><span class="mp-sub">猫 · 长毛</span><span class="mp-price">¥280</span></div>
                    </div>""", 1),

# --- P0-4 狗价目表 ---
("""                      <div class="mp-row"><span class="mp-title">狗狗洗澡 <span class="mp-tag--gray mp-tag">60 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬</span><span class="mp-price">¥68</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬</span><span class="mp-price">¥98</span></div>
                      <div class="mp-row"><span class="mp-sub">大型犬</span><span class="mp-price">¥138</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">造型修剪 <span class="mp-tag--gray mp-tag">90 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬</span><span class="mp-price">¥158</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬</span><span class="mp-price">¥228</span></div>
                      <div class="mp-row"><span class="mp-sub">大型犬</span><span class="mp-price">¥298</span></div>
                    </div>""",
 """                      <div class="mp-row"><span class="mp-title">基础洗护套餐 <span class="mp-tag--gray mp-tag">60 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬 · 短毛</span><span class="mp-price">¥130</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬 · 长毛</span><span class="mp-price">¥150</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬 · 短毛</span><span class="mp-price">¥160</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬 · 长毛</span><span class="mp-price">¥180</span></div>
                      <div class="mp-row"><span class="mp-sub">大型犬 · 短毛</span><span class="mp-price">¥200</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">精致美容 <span class="mp-tag--gray mp-tag">120 分钟</span></span><span class="mp-btn mp-btn--main mp-btn--mini">预约</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬 · 短毛</span><span class="mp-price">¥280</span></div>
                      <div class="mp-row"><span class="mp-sub">小型犬 · 长毛</span><span class="mp-price">¥340</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬 · 短毛</span><span class="mp-price">¥360</span></div>
                      <div class="mp-row"><span class="mp-sub">中型犬 · 长毛</span><span class="mp-price">¥420</span></div>
                      <div class="mp-row"><span class="mp-sub">大型犬 · 短毛</span><span class="mp-price">¥480</span></div>
                    </div>""", 1),

# --- 3.2 页面布局：规格档口径 ---
("主体为服务项目卡片流：每张卡片含服务名、时长标签、全部规格档价格行与「预约」按钮。",
 "主体为服务项目卡片流：每张卡片含服务名、时长标签、该宠物类型下<strong>全部规格档</strong>价格行与「预约」按钮。规格档口径固定为——猫按毛长分短毛 / 中长毛 / 长毛三档，犬按体型（小 / 中 / 大）× 毛长（短 / 长）组合分档（大型犬仅有短毛档），与宠物档案的毛长选项完全同源。", 1),

# --- 3.3 预约第一步 ---
("""                      <div class="mp-row"><span class="mp-title">猫咪洗澡<span class="anno">1</span></span><span class="mp-price">¥128 起</span></div>
                      <div class="mp-sub">60 分钟 · 长毛猫 ¥128 / 短毛猫 ¥88</div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">猫咪精致洗护</span><span class="mp-price">¥148 起</span></div>
                      <div class="mp-sub">75 分钟 · 含吹拉、梳毛、耳部清洁</div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">猫咪美容修剪</span><span class="mp-price">¥168 起</span></div>
                      <div class="mp-sub">90 分钟 · 造型修剪 + 精致洗护</div>
                    </div>
                    <span class="mp-btn mp-btn--main" data-demo-toggle="bk2">选择「猫咪洗澡」›</span>""",
 """                      <div class="mp-row"><span class="mp-title">基础洗护套餐<span class="anno">1</span></span><span class="mp-price">¥120 起</span></div>
                      <div class="mp-sub">60 分钟 · 猫短毛 ¥120 / 中长毛 ¥150 / 长毛 ¥180</div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">精致美容</span><span class="mp-price">¥260 起</span></div>
                      <div class="mp-sub">120 分钟 · 含吹拉、梳毛、造型修剪</div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title">药浴 SPA</span><span class="mp-price">¥200 起</span></div>
                      <div class="mp-sub">120 分钟 · 药浴浸泡 + 皮肤护理</div>
                    </div>
                    <span class="mp-btn mp-btn--main" data-demo-toggle="bk2">选择「基础洗护套餐」›</span>""", 1),

('<div class="mp-sub">对应规格价：<b style="color:var(--accent)">¥128</b></div>',
 '<div class="mp-sub">对应规格价：<b style="color:var(--accent)">¥180</b></div>', 1),
('<span class="mp-tag">长毛猫洗澡 10 次卡 · 剩 3 次</span>',
 '<span class="mp-tag">猫洗护次卡·10 次 · 剩 3 次</span>', 1),
('<div class="mp-row"><span class="mp-title">咪咪 · 猫咪洗澡（长毛）</span><span class="mp-price">¥128</span></div>',
 '<div class="mp-row"><span class="mp-title">咪咪 · 基础洗护套餐（猫 · 长毛）</span><span class="mp-price">¥180</span></div>', 1),
('<div class="mp-title" style="font-size:12.5px;">微信支付 ¥128</div>',
 '<div class="mp-title" style="font-size:12.5px;">微信支付 ¥180</div>', 1),
('<div class="mp-sub">9月16日 周三 10:00–11:00 · 咪咪 · 猫咪洗澡</div>',
 '<div class="mp-sub">9月16日 周三 10:00–11:00 · 咪咪 · 基础洗护套餐</div>', 1),

# --- P0-2 时段落库口径 ---
("时段不落库，由有效预约的起止时间实时聚合计算，保证改配置即时生效。",
 "时段以 <code>time_slot</code> 表按「门店 + 日期 + 起始时间」自然键<strong>懒创建</strong>落库：首次有预约时固化该时段容量，同一时段行在预约事务内以 <code>SELECT … FOR UPDATE</code> 加行锁并维护 <code>booked_count</code>，从根上杜绝超卖。容量取值遵循「新时段用新配置、已生成时段用固化值」——店长调整容量后仅对<strong>尚未生成的未来时段</strong>生效，已生成的时段保持固化容量；门店若需立即生效，使用后台「重算未来时段容量」操作，无冲突时直接改写、有冲突时列出冲突时段由店长确认。", 1),

# --- 3.5 ---
('<div class="mp-row"><span>长毛猫洗澡 10 次卡</span><span class="mp-tag">剩 3 次</span></div>',
 '<div class="mp-row"><span>猫洗护次卡·10 次</span><span class="mp-tag">剩 3 次</span></div>', 1),

("""                      <div class="mp-row"><span class="mp-title" style="font-size:12.5px;">猫咪洗澡 10 次卡 · 长毛猫</span><span class="mp-price">¥1080</span></div>
                      <div class="mp-row"><span class="mp-sub">单次 ¥128 · 省 ¥200 · 12 个月有效</span><span class="mp-btn mp-btn--main mp-btn--mini">购买</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title" style="font-size:12.5px;">猫咪洗澡 10 次卡 · 短毛猫</span><span class="mp-price">¥760</span></div>
                      <div class="mp-row"><span class="mp-sub">单次 ¥88 · 省 ¥120 · 12 个月有效</span><span class="mp-btn mp-btn--main mp-btn--mini">购买</span></div>
                    </div>""",
 """                      <div class="mp-row"><span class="mp-title" style="font-size:12.5px;">猫洗护次卡 · 10 次</span><span class="mp-price">¥980</span></div>
                      <div class="mp-row"><span class="mp-sub">基础洗护套餐 · 猫全规格通用 · 12 个月有效</span><span class="mp-btn mp-btn--main mp-btn--mini">购买</span></div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-title" style="font-size:12.5px;">狗美容次卡 · 10 次</span><span class="mp-price">¥2,000</span></div>
                      <div class="mp-row"><span class="mp-sub">精致美容 · 犬全规格通用 · 12 个月有效</span><span class="mp-btn mp-btn--main mp-btn--mini">购买</span></div>
                    </div>""", 1),

# --- 退款页重做 ---
("""                      <div class="mp-title" style="margin-bottom:4px;">申请余额退款<span class="anno">7</span></div>
                      <div class="mp-row"><span class="mp-sub">当前余额</span><span>¥456.20</span></div>
                      <div class="mp-row"><span class="mp-sub">累计充值（本金）</span><span>¥1,000.00</span></div>
                      <div class="mp-row"><span class="mp-sub">累计赠送</span><span>¥150.00</span></div>
                      <div class="mp-row"><span class="mp-sub">可退本金（按比例折算）</span><span class="mp-price">¥396.75</span></div>
                      <div class="mp-sub" style="margin-top:4px;">赠送部分 ¥59.45 不折现；审核通过后 1–3 个工作日原路退回微信</div>
                    </div>
                    <div class="mp-card">
                      <div class="mp-sub" style="margin-bottom:4px;">退款金额</div>
                      <div class="mp-price" style="font-size:16px;">¥396.75（全部可退本金）</div>
                    </div>
                    <span class="mp-btn mp-btn--main">提交退款申请</span>""",
 """                      <div class="mp-title" style="margin-bottom:4px;">申请退款<span class="anno">7</span></div>
                      <div class="mp-row"><span class="mp-sub">可线上申请的类型</span><span class="mp-sub">未使用的次卡</span></div>
                      <div class="mp-row"><span class="mp-sub">猫洗护次卡 · 10 次（未使用）</span><span>¥980.00</span></div>
                      <div class="mp-sub" style="margin-top:4px;">审核通过后<b>全额退入可用余额</b>，即时到账；余额不支持提现</div>
                    </div>
                    <div class="mp-card" style="padding:8px 12px;"><div class="mp-sub">退款记录</div></div>
                    <div class="mp-card">
                      <div class="mp-row"><span class="mp-sub">09-12 · 猫洗护次卡</span><span class="mp-tag">已退入余额 ¥980.00</span></div>
                    </div>
                    <span class="mp-btn mp-btn--main">提交退款申请</span>""", 1),

("<li>余额退款：本金按「累计本金 ÷ 累计充值总额」比例折算</li>",
 "<li>退款：一律退入可用余额（即时到账）；余额不可提现，也不原路退回微信</li>", 1),
("充值页为档位宫格 + 自定义金额输入；次卡页分为「我的次卡」与「购买次卡」两个区块；余额退款页展示折算明细与申请入口。",
 "充值页为档位宫格 + 自定义金额输入；次卡页分为「我的次卡」与「购买次卡」两个区块；退款页展示可退项目与退款记录。", 1),
("固定档位含赠送金额（如充 500 送 60）", "固定档位含赠送金额（如充 500 送 80）", 1),
("<li><strong>余额退款</strong>：可退金额 = 当前余额 ×（累计充值本金 ÷ 累计充值含赠送总额），赠送部分不折现；提交后进入店长审核，通过后由后台发起微信原路退款，1–3 个工作日到账；存在未结算预约（冻结中）时不可提交全额退款申请。</li>",
 "<li><strong>退款</strong>：<strong>全场景退款一律退入会员可用余额、即时到账</strong>；不提供退微信 / 退现金出口，余额亦不支持提现，赠送金一并退回余额、不折现。当前可线上申请的类型只有两类：未使用（<code>remain_times = total_times</code>）且无预扣的次卡，按购卡实付 <code>paid_amount</code> 整卡退回；以及店长人工退款（客诉补偿等）。商品订单、预约、结算多收三类来源暂不经线上申请，需门店人工处理。提交后进入店长审核，<strong>通过即到账</strong>（抢占退款单状态 → 业务侧核销 → 账务入余额，三步同一事务完成，没有「处理中」中间态）；存在冻结中的预约时，可退金额需扣除冻结部分。</li>", 1),
("<li><strong>等级</strong>：按累计实付实时计算（余额消费、微信实付、购买次卡计入；充值不计，消费时再计；次卡核销不重复计）。升级即时生效，退款相应冲减累计实付并可能触发降级。",
 "<li><strong>等级</strong>：按累计实付实时计算（余额消费、微信实付、购买次卡计入；充值不计，消费时再计；次卡核销不重复计）。升级即时生效。<em>退款冲减累计实付并可能触发降级，随等级体系那一期一起落地</em>（<code>member.total_paid</code> 当前尚无写入路径，本次不联动）。", 1),
("充值即代表同意《储值协议》：余额可申请退款，赠送金额不折现",
 "充值即代表同意《储值协议》：赠送金额与本金同池消费、不折现，余额不可提现", 1),

# --- 4.x ---
("改价后按新合计多退少补，退回部分解冻回余额或原路退回。",
 "改价后按新合计多退少补，退回部分一律解冻回可用余额。", 1),
("<span>微信付退微信（1-3 个工作日到账）；余额付退余额（即时，含按比例分摊的本金与赠送金）；已核销订单不可线上退款。</span>",
 "<span>一律退回可用余额（即时到账，含按比例分摊的本金与赠送金）；余额不支持提现；已核销订单不可线上退款。</span>", 1),
("<li>退款金额按支付构成原路退回</li>",
 "<li>退款金额一律退回可用余额，不原路退回微信</li>", 1),
("<li>订单详情记录支付构成（本金 / 赠送金分摊、是否特卖价），退款按分摊比例退，累计实付相应冲减并可能触发等级降级。</li>",
 "<li>订单详情记录支付构成（本金 / 赠送金分摊、是否特卖价），退款按分摊比例退入可用余额。累计实付冲减与等级降级随等级体系那一期落地。</li>", 1),

("""                          <tr><td>洗护套餐</td><td>洗护</td><td>1 个时段（60 分钟）</td><td>3 坑位</td><td>¥120 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>精致美容</td><td>美容</td><td>2 个时段（120 分钟）</td><td>2 坑位</td><td>¥260 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>药浴 SPA</td><td>洗护</td><td>2 个时段（120 分钟）</td><td>3 坑位</td><td>¥200 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>寄养（日）</td><td>寄养</td><td>按天计</td><td>10 位</td><td>¥80 / 天</td><td><span class="a-badge a-badge--off">停用</span></td><td><span class="a-btn">启用</span></td></tr>""",
 """                          <tr><td>基础洗护套餐</td><td>WASH</td><td>1 个时段（60 分钟）</td><td>3 坑位</td><td>¥120 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>精致美容</td><td>GROOM</td><td>2 个时段（120 分钟）</td><td>2 坑位</td><td>¥260 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>药浴 SPA</td><td>SPA</td><td>2 个时段（120 分钟）</td><td>3 坑位</td><td>¥200 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>疫苗接种</td><td>VACCINE</td><td>1 个时段（60 分钟）</td><td>3 坑位</td><td>¥60 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>体内外驱虫</td><td>WORM</td><td>1 个时段（60 分钟）</td><td>3 坑位</td><td>¥40 起</td><td><span class="a-badge a-badge--ok">启用</span></td><td><span class="a-btn">编辑</span></td></tr>""", 1),

("""                      <table class="a-table">
                        <thead>
                          <tr><th>规格档</th><th>洗护套餐</th><th>精致美容</th><th>药浴 SPA</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>猫 · 短毛</td><td>¥120</td><td>¥260</td><td>¥200</td></tr>
                          <tr><td>猫 · 中长毛</td><td>¥150</td><td>¥320</td><td>¥240</td></tr>
                          <tr><td>猫 · 长毛</td><td><strong>¥180</strong></td><td>¥380</td><td>¥280</td></tr>
                          <tr><td>狗 · 小型 · 短毛</td><td>¥120</td><td>¥260</td><td>¥200</td></tr>
                          <tr><td>狗 · 中型 · 短毛</td><td>¥150</td><td>¥320</td><td>¥240</td></tr>
                          <tr><td>狗 · 中型 · 长毛</td><td>¥180</td><td>¥380</td><td>¥280</td></tr>
                          <tr><td>狗 · 大型 · 短毛</td><td>¥200</td><td>¥420</td><td>—</td></tr>
                        </tbody>
                      </table>""",
 """                      <table class="a-table" id="price-matrix">
                        <thead>
                          <tr><th>规格档</th><th>基础洗护套餐</th><th>精致美容</th><th>药浴 SPA</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>猫 · 短毛</td><td>¥120</td><td>¥260</td><td>¥200</td></tr>
                          <tr><td>猫 · 中长毛</td><td>¥150</td><td>¥320</td><td>¥240</td></tr>
                          <tr><td>猫 · 长毛</td><td><strong>¥180</strong></td><td>¥380</td><td>¥280</td></tr>
                          <tr><td>狗 · 小型 · 短毛</td><td>¥130</td><td>¥280</td><td>¥220</td></tr>
                          <tr><td>狗 · 小型 · 长毛</td><td>¥150</td><td>¥340</td><td>¥260</td></tr>
                          <tr><td>狗 · 中型 · 短毛</td><td>¥160</td><td>¥360</td><td>¥280</td></tr>
                          <tr><td>狗 · 中型 · 长毛</td><td>¥180</td><td>¥420</td><td>¥320</td></tr>
                          <tr><td>狗 · 大型 · 短毛</td><td>¥200</td><td>¥480</td><td>¥360</td></tr>
                        </tbody>
                      </table>""", 1),

('<div class="a-page-title">分档价格 · 洗护套餐</div>',
 '<div class="a-page-title">分档价格 · 服务 × 规格档</div>', 1),
('<span class="a-badge a-badge--warn">改价即时生效，仅影响新预约</span>',
 '<span class="a-badge a-badge--warn">改价即时生效，仅影响新预约与未生成时段</span>', 1),

("""                      <table class="a-table">
                        <thead>
                          <tr><th>名称</th><th>关联服务 · 规格</th><th>次数</th><th>售价</th><th>有效期</th><th>已售</th><th>状态</th><th>操作</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>猫类洗澡次卡 · 长毛猫</td><td>洗护套餐 · 猫长毛</td><td>10 次</td><td>¥1,440</td><td>12 个月</td><td>23</td><td><span class="a-badge a-badge--ok">在售</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>猫类洗澡次卡 · 短毛猫</td><td>洗护套餐 · 猫短毛 / 中长</td><td>10 次</td><td>¥1,080</td><td>12 个月</td><td>31</td><td><span class="a-badge a-badge--ok">在售</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>狗狗精致美容季卡</td><td>精致美容 · 中型</td><td>4 次</td><td>¥1,088</td><td>6 个月</td><td>8</td><td><span class="a-badge a-badge--ok">在售</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>开业纪念洗护卡</td><td>洗护套餐 · 全规格</td><td>6 次</td><td>¥599</td><td>固定至 2026-12-31</td><td>56</td><td><span class="a-badge a-badge--off">停售</span></td><td><span class="a-btn">编辑</span></td></tr>
                        </tbody>
                      </table>""",
 """                      <table class="a-table" id="card-type-table">
                        <thead>
                          <tr><th>名称</th><th>关联服务 · 规格</th><th>次数</th><th>售价</th><th>有效期</th><th>已售</th><th>状态</th><th>操作</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>猫洗护次卡·10 次</td><td>基础洗护套餐 · 猫全规格</td><td>10 次</td><td>¥980</td><td>12 个月</td><td>23</td><td><span class="a-badge a-badge--ok">在售</span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>狗美容次卡·10 次</td><td>精致美容 · 犬全规格</td><td>10 次</td><td>¥2,000</td><td>12 个月</td><td>8</td><td><span class="a-badge a-badge--ok">在售</span></td><td><span class="a-btn">编辑</span></td></tr>
                        </tbody>
                      </table>""", 1),
('<input class="a-input" value="猫类洗澡次卡 · 长毛猫">', '<input class="a-input" value="猫洗护次卡·10 次">', 1),
('<span class="a-badge a-badge--ok">洗护套餐 ✓</span><span class="a-badge">精致美容</span><span class="a-badge">药浴 SPA</span>',
 '<span class="a-badge a-badge--ok">基础洗护套餐 ✓</span><span class="a-badge">精致美容</span><span class="a-badge">药浴 SPA</span>', 1),
('<span class="a-badge a-badge--ok">猫 · 长毛 ✓</span><span class="a-badge">猫 · 短毛</span><span class="a-badge">猫 · 中长毛</span>',
 '<span class="a-badge a-badge--ok">猫 · 短毛 ✓</span><span class="a-badge">猫 · 中长毛</span><span class="a-badge">猫 · 长毛</span>', 1),
('<div class="a-field"><span class="a-label">次数 / 售价</span><input class="a-input a-input--num" value="10"><span>次</span><input class="a-input a-input--num" value="1440"><span>元</span></div>',
 '<div class="a-field"><span class="a-label">次数 / 售价</span><input class="a-input a-input--num" value="10"><span>次</span><input class="a-input a-input--num" value="980"><span>元</span></div>', 1),
('<input class="a-input" value="限洗护套餐使用，同账号所有猫只通用">',
 '<input class="a-input" value="限基础洗护套餐使用，同账号所有猫只通用（不限毛长档）">', 1),

("""                      <table class="a-table">
                        <thead>
                          <tr><th>充值金额</th><th>赠送金额</th><th>到账合计</th><th>实际折扣</th><th>启用</th><th>操作</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>¥100</td><td>¥10</td><td>¥110</td><td>约 9.1 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>¥300</td><td>¥45</td><td>¥345</td><td>约 8.7 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>¥500</td><td>¥100</td><td>¥600</td><td>约 8.3 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>¥1,000</td><td>¥250</td><td>¥1,250</td><td>约 8.0 折</td><td><span class="a-switch"></span></td><td><span class="a-btn">编辑</span></td></tr>
                        </tbody>
                      </table>""",
 """                      <table class="a-table" id="recharge-tier-table">
                        <thead>
                          <tr><th>充值金额</th><th>赠送金额</th><th>到账合计</th><th>实际折扣</th><th>启用</th><th>操作</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>¥300</td><td>¥30</td><td>¥330</td><td>约 9.1 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>¥500</td><td>¥80</td><td>¥580</td><td>约 8.6 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                          <tr><td>¥1,000</td><td>¥200</td><td>¥1,200</td><td>约 8.3 折</td><td><span class="a-switch is-on"></span></td><td><span class="a-btn">编辑</span></td></tr>
                        </tbody>
                      </table>""", 1),
('<div class="a-field"><span class="a-label">退款规则</span><span>赠送金不折现；退款按本金占累计充值比例退回</span></div>',
 '<div class="a-field"><span class="a-label">退款规则</span><span>退款一律退入可用余额；赠送金一并退回余额、不折现、不可提现</span></div>', 1),
("赠送金计入余额但退款时按本金比例折算、不折现。",
 "赠送金计入余额，与本金同池消费并一并退入余额，不折现、不可提现。", 1),

("""                          <tr><td>普通</td><td>¥0</td><td>无</td><td>—</td><td>—</td><td><span class="a-switch is-on"></span></td></tr>
                          <tr><td>白银</td><td>¥1,000</td><td>95 折</td><td>—</td><td>生日礼包</td><td><span class="a-switch is-on"></span></td></tr>
                          <tr><td>黄金</td><td>¥3,000</td><td>88 折</td><td>宠物美容 8 折</td><td>生日礼包 · 积分双倍</td><td><span class="a-switch is-on"></span></td></tr>
                          <tr><td>铂金</td><td>¥8,000</td><td>85 折</td><td>宠物美容 8 折 · 洗护 9 折</td><td>生日礼包 · 积分双倍 · 优先预约</td><td><span class="a-switch"></span></td></tr>""",
 """                          <tr><td>普通会员</td><td>¥0</td><td>无</td><td>—</td><td>—</td><td><span class="a-switch is-on"></span></td></tr>
                          <tr><td>银爪会员</td><td>¥1,000</td><td>95 折</td><td>精致美容 92 折</td><td>生日礼包</td><td><span class="a-switch is-on"></span></td></tr>
                          <tr><td>金爪会员</td><td>¥3,000</td><td>88 折</td><td>精致美容 8 折 · 基础洗护 85 折</td><td>生日礼包 · 免费驱虫 1 次</td><td><span class="a-switch is-on"></span></td></tr>""", 1),
("权益名称与数值全部由商家自定义（如「黄金会员享宠物美容 8 折」）",
 "权益名称与数值全部由商家自定义（如「金爪会员享精致美容 8 折」）；演示门店实际配置 3 档，等级数量与门槛完全由商家增删", 1),

("<li>余额退款入口在会员详情：顾客发起申请 → 店长审核 → 手动原路退回，赠送金按本金占累计充值比例折算、不折现。</li>",
 "<li>退款入口在会员详情：顾客发起申请 → 店长审核 → 通过后<strong>即时退入可用余额</strong>，不原路退回微信；赠送金不折现、不可提现。</li>", 1),
("<li>等级按累计实付自动升降级：升级即时生效，退款冲减累计实付并可能触发降级；",
 "<li>等级按累计实付自动升降级：升级即时生效（退款冲减累计实付并可能触发降级，随等级体系那一期落地）；", 1),
('<td><span class="a-badge a-badge--warn">黄金</span></td><td>¥3,860</td>',
 '<td><span class="a-badge a-badge--warn">金爪</span></td><td>¥1,460</td>', 1),
('<td><span class="a-badge">白银</span></td><td>¥1,540</td>',
 '<td><span class="a-badge">银爪</span></td><td>¥1,540</td>', 1),
('<div class="mp-row"><span class="mp-sub">距铂金会员还差 ¥1,540（78%）<span class="anno">3</span></span>',
 '<div class="mp-row"><span class="mp-sub">距金爪会员还差 ¥1,540（49%）<span class="anno">3</span></span>', 1),

("    P -->|通过| Q[原路退款 微信或余额]",
 "    P -->|通过| Q[退款一律入可用余额]", 1),
('<div class="figure-cap">图 5-4 充值与次卡流程（次卡仅微信支付购买、不参与会员折扣；赠送金退款按本金比例折算不折现）</div>',
 '<div class="figure-cap">图 5-4 充值与次卡流程（次卡仅微信支付购买、不参与会员折扣；退款一律退入可用余额，赠送金一并退回、不折现）</div>', 1),
("note right of 已取消 : 担保解冻或原路退回或返还次卡",
 "note right of 已取消 : 担保解冻或退款入余额或返还次卡", 1),
('<div class="figure-cap">图 6-2 商品订单状态机（退款原路退回：微信付退微信、余额付退余额含赠送金分摊）</div>',
 '<div class="figure-cap">图 6-2 商品订单状态机（退款一律退回可用余额，含赠送金分摊；微信原路退款为二期能力）</div>', 1),
("<td>退款统一店长审核，操作留痕；余额退款手动原路退回，赠送金按本金比例折算不折现</td>",
 "<td>退款统一店长审核、操作留痕；一律退回可用余额即时到账，赠送金不折现、余额不可提现</td>", 1),
("<td>余额可申请退款、次卡未用可退、账目可导出，三项为产品固定能力</td>",
 "<td>次卡未用可退（退入余额）、退款全链路留痕可查、账目可导出，三项为产品固定能力；余额不提供提现 / 退微信出口</td>", 1),
("<td>预约时锁定的履约保证，三选一：冻结余额、微信全额支付（取消原路退回）、次卡预扣；门店开启免押金模式时可不担保</td>",
 "<td>预约时锁定的履约保证，三选一：冻结余额、微信全额支付（取消时担保原路退回微信）、次卡预扣；门店开启免押金模式时可不担保。<strong>注意</strong>：担保解除原路退回与「业务退款一律入余额」是两件事——前者是担保资源释放，后者是资金出账口径</td>", 1),
]
apply(PRD, PRD_EDITS, "PRD")


# ============================ 技术设计 ============================
TECH_EDITS = [
('<div class="figure-cap">图 3-1 核心 ER 图（14 张核心表；spec 为预置规格档字典，store_id 各表均带，图中省略）</div>',
 '<div class="figure-cap">图 3-1 核心关系图（<strong>局部视图</strong>；全库共 25 张表，本图只画主线关系。recharge_tier、member_level、refund_apply、goods、inventory_log、notice_log 等 6 张表未连线，完整表清单见表 3-5 与表 3-6。spec 为预置规格档字典，store_id 各表均带，图中省略）</div>', 1),

("<tr><td><code>cum_principal / cum_gift</code></td><td>decimal(10,2)</td><td>累计充值本金 / 赠送金，退款折算比例用</td></tr>",
 "<tr><td><code>cum_principal / cum_gift</code></td><td>decimal(10,2)</td><td><strong>历史累计</strong>充值本金 / 赠送金，<em>只增不减</em>（消费与退款均不回冲）。仅用于经营展示与对账，<strong>不得用于计算可退金额</strong> —— 见 4.1 可退本金口径</td></tr>", 1),

("<li><strong>退款折算</strong>：可退本金 = 余额 × (cum_principal ÷ (cum_principal + cum_gift))，赠送金不折现。</li>",
 """<li><strong>可退本金（重要，勿用比例折算）</strong>：<code>可退本金 = Σ balance_ledger.principal_amt</code>（<code>CONSUME</code> / <code>REFUND</code> 类流水记负值，直接相减）。<strong>禁止</strong>使用「余额 × 本金占比」按比例折算——<code>cum_principal / cum_gift</code> 是历史累计值而余额是当前值，两次充值赠送比例不同即失真：充 1000 送 150 → 消费 575 → 再充 100 送 0 时公式给 594.00，真实剩余本金 600.00（顾客少退 6 元）；第二次若为「充 100 送 100」则公式给 631.48（商家多退 31.48 元）——差额方向会翻转。赠送金一并退回余额、不折现。</li>
            <li><strong>分位误差归属</strong>：拆分与折算一律 <code>HALF_UP</code> 保留 2 位小数；拆分后 <code>principal + gift</code> 与总额的差额一律<strong>计入赠送金</strong>（误差由商家承担），保证本金部分不因四舍五入多退。</li>""", 1),

("<li>退款同理：店长审核通过 → 调微信退款 API（或余额即时退回）→ refund_apply 与流水同事务留痕。</li>",
 "<li><strong>退款（当前口径：只退到余额）</strong>：店长审核通过 → 抢占 refund_apply 状态 → 业务侧核销（次卡置 <code>REFUNDED</code>）→ 账本写 <code>REFUND_IN</code> 入余额，三步同一事务，通过即到账、无「处理中」中间态。<strong>调微信退款 API 属二期</strong>：当前不提供退微信 / 退现金出口，余额亦不支持提现；二期若开放，落地点为保留未启用的 <code>REFUND</code>（余额出账）流水类型与 pay_order 的 <code>REFUNDED</code> 状态。</li>", 1),

("<tr><td><code>status</code></td><td>varchar(16)</td><td>CREATED / SUCCESS / CLOSED / REFUNDED，回调 CAS 更新</td></tr>",
 "<tr><td><code>status</code></td><td>varchar(16)</td><td>CREATED / SUCCESS / CLOSED / REFUNDED，回调 CAS 更新。当前「只退到余额」口径下 <code>REFUNDED</code> <strong>不使用</strong>（退款不经过支付单）；二期接入微信原路退款时需补 REFUNDING / REFUND_FAILED</td></tr>", 1),

("<tr><td><code>refund_apply</code></td><td>退款申请</td><td>业务类型（订单 / 余额 / 次卡）、审核人与结论留痕</td></tr>",
 "<tr><td><code>refund_apply</code></td><td>退款申请</td><td>字段：<code>refund_no UK</code>、<code>member_id</code>、<code>biz_type</code>（CARD / GOODS / APPOINT / SETTLE / MANUAL）、<code>biz_no</code>、<code>amount</code>、<code>principal_amt</code>、<code>gift_amt</code>、<code>reason</code>、<code>status</code>（APPLYING / REFUNDED / REJECTED / CANCELLED）、<code>auditor</code>、<code>audit_remark</code>、<code>finished_at</code>；索引 <code>(member_id,status)</code> / <code>(status,id)</code> / <code>(biz_type,biz_no)</code>。每次状态迁移均为 CAS（<code>WHERE status = 前置态</code>）</td></tr>", 1),

("<tr><td><code>GET /orders?status=</code> · <code>POST /orders/{id}/refund-apply</code></td><td>订单列表；申请退款（未核销）</td></tr>",
 "<tr><td><code>GET /orders?status=</code></td><td>订单列表（商品订单退款申请暂未开放，见下方退款分组）</td></tr>", 1),
("<tr><td><code>POST /balance-refunds</code></td><td>余额退款申请（展示折算明细）</td></tr>",
 """<tr><td><code>POST /refunds</code> · <code>GET /refunds</code> · <code>POST /refunds/{id}/cancel</code></td><td>退款申请（报文只传 <code>bizType</code> + <code>bizNo</code>，<strong>无金额字段</strong>，金额由服务端按购卡实付算）、我的退款单、撤回待审申请</td></tr>
              <tr><td><code>GET /wallet/cards</code></td><td>我的次卡（含卡名、购卡实付、服务端判定的 <code>refundable</code>）</td></tr>""", 1),
("<tr><th scope=\"row\">退款审核</th><td><code>POST /admin/refunds/{id}/audit</code></td><td>通过 / 驳回（驳回必填理由）（M）</td></tr>",
 "<tr><th scope=\"row\">退款审核</th><td><code>GET /admin/refunds?status=</code> · <code>POST /admin/refunds/{id}/audit</code> · <code>POST /admin/refunds/manual</code></td><td>待审队列；通过 / 驳回（驳回必填理由）；店长人工退款直接生效（M）</td></tr>", 1),
("<td>退款审核、次卡人工调整、价目配置走店长角色注解 + audit_log（若依操作日志）</td>",
 "<td>退款审核、次卡人工调整、价目配置走店长角色注解 + audit_log（若依操作日志）；<code>/api/v1/refunds</code> 与 <code>/admin/refunds</code> 纳入严格限流配额（10s / 5 次，与登录、充值、核销同级）</td>", 1),
("退款全链路（余额、次卡、微信原路）演练。",
 "退款全链路演练（次卡退款入余额、人工退款、重复审核幂等）；微信原路退款属二期，届时补沙箱演练。", 1),
("VALUES\n  (1, #{petId}, 'VACCINE', CURDATE(), DATE_ADD(CURDATE(), INTERVAL 1 YEAR),",
 "VALUES\n  (1, #{petId}, 'VACCINE', #{bizDate}, DATE_ADD(#{bizDate}, INTERVAL 1 YEAR),", 1),
]
apply(TECH, TECH_EDITS, "技术设计")


# ============================ 规划 ============================
PLAN_EDITS = [
("<tr><td>退款审核</td><td>余额退款人工审核原路退回（赠送金按本金比例折算不折现）；次卡未用退款；订单退款（微信付退微信、余额付退余额）</td></tr>",
 "<tr><td>退款审核</td><td><strong>退款一律退入可用余额、即时到账</strong>，不提供退微信 / 退现金出口，余额不可提现；覆盖未使用次卡退款与店长人工退款（商品订单、预约、结算多收三类暂走人工）</td></tr>", 1),
("<td>充值档位 + 自定义充值；余额账务（冻结 / 消费 / 退款全流水）；等级折扣引擎；退款审核后台</td>",
 "<td>充值档位 + 自定义充值；余额账务（冻结 / 消费 / 退款全流水，append-only 账本）；等级折扣引擎；退款审核后台</td>", 1),
]
apply(PLAN, PLAN_EDITS, "规划")


# ============================ UI 稿 ============================
UI_EDITS = [
("'<div><b>3 张</b><span>优惠券</span></div>' +",
 "'<div><b>1 条</b><span>退款记录</span></div>' +", 1),
]
apply(UI, UI_EDITS, "UI 稿")


# ============================ plan.md ============================
PLANMD_EDITS = [
("`petshop-prd`（产品需求文档）、`petshop-tech-design`（技术设计文档）。",
 "`petshop-prd`（产品需求文档）、`petshop-tech-design`（技术设计文档）、`petshop-ui`（UI 设计稿）。", 1),
("C 端 6 个功能模块 → B 端 8 个功能模块（每个模块嵌入交互原型 Demo）",
 "C 端 6 个功能模块（3.1–3.6）→ B 端 9 个功能模块（4.1–4.9，合计 15 个；每个模块嵌入交互原型 Demo）", 1),
]
apply(PLAN_MD, PLANMD_EDITS, "plan.md")


print()
if all_errors:
    print(f"!! {len(all_errors)} 处断言失败（对应文件未写入）：")
    for e in all_errors:
        print(e)
    sys.exit(1)
print("全部替换成功。" + ("（dry-run，未写盘）" if DRY else ""))
