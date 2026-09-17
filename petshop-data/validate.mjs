#!/usr/bin/env node
/**
 * 单一事实来源校验（S-2）
 *
 * 断言三方一致：petshop-data/pricing.json === 02_seed.sql === PRD 文档展示值
 * 运行：node petshop-data/validate.mjs
 * 退出码：0 全部一致；1 存在不一致
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

const dataset = JSON.parse(readFileSync(join(HERE, 'pricing.json'), 'utf8'));
const seedSql = readFileSync(
  join(ROOT, 'petshop-server/petshop-admin/src/main/resources/db/02_seed.sql'),
  'utf8'
);
const prdHtml = readFileSync(join(ROOT, 'petshop-prd/petshop-prd.html'), 'utf8');

const problems = [];
const ok = [];
const fail = (msg) => problems.push(msg);
const pass = (msg) => ok.push(msg);
const norm = (s) => String(s).replace(/\s+/g, '').trim();
const money = (n) => Number(n).toFixed(2);

// ---------------------------------------------------------------- SQL 解析
function sqlValues(table) {
  const re = new RegExp(`INSERT\\s+INTO\\s+${table}\\s*\\([^)]*\\)\\s*VALUES([\\s\\S]*?);`, 'i');
  const m = seedSql.match(re);
  if (!m) throw new Error(`02_seed.sql 中找不到 INSERT INTO ${table}`);
  return parseTuples(m[1]);
}
/** 按括号深度 + 单引号状态扫描 VALUES，正确处理 NOW() 与 '[11,12,13]' */
function parseTuples(body) {
  const rows = [];
  let depth = 0, cur = '', row = [], inStr = false;
  const push = () => { const v = cur.trim(); row.push(/^'.*'$/.test(v) ? v.slice(1, -1) : v); cur = ''; };
  for (const c of body) {
    if (inStr) { cur += c; if (c === "'") inStr = false; continue; }
    if (c === "'") { inStr = true; cur += c; continue; }
    if (c === '(') { depth++; if (depth === 1) { cur = ''; row = []; continue; } cur += c; continue; }
    if (c === ')') { depth--; if (depth === 0) { push(); rows.push(row); continue; } cur += c; continue; }
    if (c === ',' && depth === 1) { push(); continue; }
    if (depth === 1) cur += c;
  }
  return rows;
}

// ---------------------------------------------------------------- 文档解析
function docTable(id) {
  const re = new RegExp(`<table[^>]*\\bid="${id}"[^>]*>([\\s\\S]*?)</table>`);
  const m = prdHtml.match(re);
  if (!m) throw new Error(`PRD 中找不到 id="${id}" 的表格（锚点是否被改动？）`);
  const rows = [...m[1].matchAll(/<tr>([\s\S]*?)<\/tr>/g)].map((r) =>
    [...r[1].matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/g)].map((c) =>
      c[1].replace(/<[^>]+>/g, '').trim()
    )
  );
  return { head: rows[0], body: rows.slice(1) };
}
const docMoney = (s) => Number(String(s).replace(/[^\d.]/g, ''));

// ================================================================ 1. 服务
{
  const rows = sqlValues('service');
  const seed = new Map(rows.map((r) => [Number(r[0]), { name: r[2], category: r[3] }]));
  let bad = 0;
  for (const svc of dataset.services) {
    const s = seed.get(svc.id);
    if (!s) { fail(`服务 ${svc.id}(${svc.name}) 在 02_seed.sql 中不存在`); bad++; continue; }
    if (s.name !== svc.name) { fail(`服务 ${svc.id} 名称不一致：dataset="${svc.name}" seed="${s.name}"`); bad++; }
    if (s.category !== svc.category) { fail(`服务 ${svc.id} 分类不一致：dataset="${svc.category}" seed="${s.category}"`); bad++; }
  }
  if (seed.size !== dataset.services.length) fail(`服务条数不一致：dataset=${dataset.services.length} seed=${seed.size}`);
  if (!bad) pass(`服务 ${dataset.services.length} 项一致`);
}

// ================================================================ 2. 规格
{
  const rows = sqlValues('spec');
  const seed = new Map(rows.map((r) => [Number(r[0]), r[5]]));
  let bad = 0;
  for (const sp of dataset.specs) {
    const name = seed.get(sp.id);
    if (!name) { fail(`规格 ${sp.id}(${sp.name}) 在 02_seed.sql 中不存在`); bad++; continue; }
    if (norm(name) !== norm(sp.name)) { fail(`规格 ${sp.id} 名称不一致：dataset="${sp.name}" seed="${name}"`); bad++; }
  }
  if (seed.size !== dataset.specs.length) fail(`规格条数不一致：dataset=${dataset.specs.length} seed=${seed.size}`);
  if (!bad) pass(`规格档 ${dataset.specs.length} 项一致（猫 3 档含中长毛，犬 5 档）`);
}

// ================================================================ 3. 价格矩阵（三方）
{
  const rows = sqlValues('service_price');
  const seed = new Map(rows.map((r) => [`${r[1]}/${r[2]}`, Number(r[3])]));
  let bad = 0;
  for (const [svcId, bySpec] of Object.entries(dataset.priceMatrix)) {
    if (svcId === 'note') continue;
    for (const [specId, price] of Object.entries(bySpec)) {
      const k = `${svcId}/${specId}`;
      if (!seed.has(k)) { fail(`价格 ${k}（服务/规格）在 02_seed.sql 中缺失`); bad++; continue; }
      if (money(seed.get(k)) !== money(price)) { fail(`价格 ${k} 不一致：dataset=${money(price)} seed=${money(seed.get(k))}`); bad++; }
    }
  }
  if (!bad) pass(`价格矩阵 ${Object.keys(dataset.priceMatrix).length - 1} 服务 × 8 规格 = ${seed.size} 项 dataset↔seed 一致`);

  // 文档侧
  const t = docTable('price-matrix');
  const svcById = Object.fromEntries(dataset.services.map((s) => [s.id, s.name]));
  const specById = Object.fromEntries(dataset.specs.map((s) => [s.id, s.name]));
  const cols = [101, 102, 103];
  const expectHead = ['规格档', ...cols.map((c) => svcById[c])];
  if (t.head.map(norm).join('|') !== expectHead.map(norm).join('|')) {
    fail(`分档价格表表头不一致：文档=[${t.head}] 期望=[${expectHead}]`);
  }
  let docBad = 0;
  for (const row of t.body) {
    const specName = row[0];
    const sp = dataset.specs.find((s) => norm(s.name) === norm(specName));
    if (!sp) { fail(`分档价格表出现未知规格档「${specName}」`); docBad++; continue; }
    cols.forEach((svcId, i) => {
      const want = dataset.priceMatrix[String(svcId)][String(sp.id)];
      const got = docMoney(row[i + 1]);
      if (money(got) !== money(want)) {
        fail(`分档价格表 ${specName} × ${svcById[svcId]}：文档=¥${money(got)} 数据集=¥${money(want)}`);
        docBad++;
      }
    });
  }
  if (t.body.length !== dataset.specs.length) fail(`分档价格表行数 ${t.body.length} ≠ 规格档数 ${dataset.specs.length}`);
  if (!docBad) pass(`分档价格表 ${t.body.length} 行 × ${cols.length} 列 文档↔dataset 一致`);
}

// ================================================================ 4. 次卡
{
  // card_type 列序：id, store_id, name, service_ids, spec_ids, times, price, ...
  const rows = sqlValues('card_type');
  const seed = new Map(rows.map((r) => [Number(r[0]), r]));
  let bad = 0;
  for (const ct of dataset.cardTypes) {
    const s = seed.get(ct.id);
    if (!s) { fail(`次卡 ${ct.id} 在 02_seed.sql 中不存在`); bad++; continue; }
    if (norm(s[2]) !== norm(ct.name)) { fail(`次卡 ${ct.id} 名称不一致：dataset="${ct.name}" seed="${s[2]}"`); bad++; }
    if (money(s[6]) !== money(ct.price)) { fail(`次卡「${ct.name}」售价不一致：dataset=¥${money(ct.price)} seed=¥${money(s[6])}`); bad++; }
    if (Number(s[5]) !== ct.times) { fail(`次卡「${ct.name}」次数不一致：dataset=${ct.times} seed=${s[5]}`); bad++; }
    const seedSpecs = JSON.parse(s[4]).join(',');
    if (seedSpecs !== ct.specIds.join(',')) { fail(`次卡「${ct.name}」适用规格不一致：dataset=[${ct.specIds}] seed=[${seedSpecs}]`); bad++; }
    const seedSvcs = JSON.parse(s[3]).join(',');
    if (seedSvcs !== ct.serviceIds.join(',')) { fail(`次卡「${ct.name}」关联服务不一致：dataset=[${ct.serviceIds}] seed=[${seedSvcs}]`); bad++; }
  }
  if (!bad) pass(`次卡类型 ${dataset.cardTypes.length} 项 dataset↔seed 一致（名称/售价/次数/适用规格）`);

  const t = docTable('card-type-table');
  let docBad = 0;
  for (const ct of dataset.cardTypes) {
    const row = t.body.find((r) => norm(r[0]) === norm(ct.name));
    if (!row) { fail(`次卡类型表缺少「${ct.name}」`); docBad++; continue; }
    if (money(docMoney(row[3])) !== money(ct.price)) {
      fail(`次卡「${ct.name}」售价：文档=¥${money(docMoney(row[3]))} 数据集=¥${money(ct.price)}`);
      docBad++;
    }
    if (docMoney(row[2]) !== ct.times) { fail(`次卡「${ct.name}」次数：文档=${row[2]} 数据集=${ct.times}`); docBad++; }
  }
  if (t.body.length !== dataset.cardTypes.length) {
    fail(`次卡类型表行数 ${t.body.length} ≠ 数据集 ${dataset.cardTypes.length}`);
    docBad++;
  }
  if (!docBad) pass(`次卡类型表 ${t.body.length} 行 文档↔dataset 一致（含售价与次数）`);
}

// ================================================================ 5. 充值档位
{
  const rows = sqlValues('recharge_tier');
  const seed = rows.map((r) => ({ amount: Number(r[2]), gift: Number(r[3]), enabled: r[4] === '1' }));
  let bad = 0;
  dataset.rechargeTiers.tiers.forEach((tr, i) => {
    const s = seed[i];
    if (!s) { fail(`充值档位第 ${i + 1} 档在 02_seed.sql 中缺失`); bad++; return; }
    if (money(s.amount) !== money(tr.amount)) { fail(`充值档位 ${i + 1} 金额不一致：dataset=${tr.amount} seed=${s.amount}`); bad++; }
    if (money(s.gift) !== money(tr.gift)) { fail(`充值档位 ${i + 1} 赠送不一致：dataset=${tr.gift} seed=${s.gift}`); bad++; }
  });
  if (seed.length !== dataset.rechargeTiers.tiers.length) fail(`充值档位数不一致：dataset=${dataset.rechargeTiers.tiers.length} seed=${seed.length}`);
  if (!bad) pass(`充值档位 ${seed.length} 档 dataset↔seed 一致`);

  const t = docTable('recharge-tier-table');
  let docBad = 0;
  dataset.rechargeTiers.tiers.forEach((tr, i) => {
    const row = t.body[i];
    if (!row) { fail(`充值档位表缺少第 ${i + 1} 行`); docBad++; return; }
    if (money(docMoney(row[0])) !== money(tr.amount)) { fail(`充值档位表第 ${i + 1} 行金额：文档=¥${docMoney(row[0])} 数据集=¥${money(tr.amount)}`); docBad++; }
    if (money(docMoney(row[1])) !== money(tr.gift)) { fail(`充值档位表第 ${i + 1} 行赠送：文档=¥${docMoney(row[1])} 数据集=¥${money(tr.gift)}`); docBad++; }
    const wantSum = money(tr.amount + tr.gift);
    if (money(docMoney(row[2])) !== wantSum) { fail(`充值档位表第 ${i + 1} 行到账合计：文档=¥${docMoney(row[2])} 期望=¥${wantSum}`); docBad++; }
    const wantRate = (tr.amount / (tr.amount + tr.gift)) * 10;
    const gotRate = Number(String(row[3]).replace(/[^\d.]/g, ''));
    if (Math.abs(gotRate - wantRate) > 0.05) { fail(`充值档位表第 ${i + 1} 行折扣：文档=${gotRate} 折 期望=${wantRate.toFixed(1)} 折`); docBad++; }
  });
  if (t.body.length !== dataset.rechargeTiers.tiers.length) fail(`充值档位表行数 ${t.body.length} ≠ 数据集 ${dataset.rechargeTiers.tiers.length}`);
  if (!docBad) pass(`充值档位表 ${t.body.length} 行 文档↔dataset 一致（含到账合计与折扣率）`);
}

// ================================================================ 6. 会员等级
{
  const rows = sqlValues('member_level');
  const seed = rows.map((r) => ({ name: r[3], threshold: Number(r[4]), general: Number(r[5]) }));
  let bad = 0;
  dataset.memberLevels.levels.forEach((lv, i) => {
    const s = seed[i];
    if (!s) { fail(`会员等级第 ${i + 1} 级在 02_seed.sql 中缺失`); bad++; return; }
    if (norm(s.name) !== norm(lv.name)) { fail(`会员等级 ${i + 1} 名称：dataset="${lv.name}" seed="${s.name}"`); bad++; }
    if (money(s.threshold) !== money(lv.threshold)) { fail(`会员等级「${lv.name}」门槛：dataset=${lv.threshold} seed=${s.threshold}`); bad++; }
    if (money(s.general) !== money(lv.generalDiscount)) { fail(`会员等级「${lv.name}」通用折扣：dataset=${lv.generalDiscount} seed=${s.general}`); bad++; }
  });
  if (seed.length !== dataset.memberLevels.levels.length) fail(`会员等级数不一致：dataset=${dataset.memberLevels.levels.length} seed=${seed.length}`);
  if (!bad) pass(`会员等级 ${seed.length} 级 dataset↔seed 一致`);
}

// ================================================================ 7. 退款口径
{
  if (dataset.refundPolicy.channel !== 'BALANCE_ONLY') fail('refundPolicy.channel 必须为 BALANCE_ONLY');

  // 旧口径的「肯定式」表述不得残留；否定式（不退回微信）与担保解除/二期说明为合法豁免
  const OLD_POLICY = /微信付退微信|原路退回微信|原支付方式退回|调微信退款|微信原路退款|微信原路退回|1[–-]3\s*个工作日/g;
  const EXEMPT_BEFORE = /[不非无禁]|二期|担保/;
  const EXEMPT_AFTER = /二期|担保|不折现/;
  const hits = [...prdHtml.matchAll(OLD_POLICY)].filter((h) => {
    const before = prdHtml.slice(Math.max(0, h.index - 16), h.index);
    const after = prdHtml.slice(h.index, h.index + 26);
    return !EXEMPT_BEFORE.test(before) && !EXEMPT_AFTER.test(after);
  });
  if (hits.length) {
    for (const h of hits.slice(0, 6)) {
      const ctx = prdHtml.slice(Math.max(0, h.index - 50), h.index + 50).replace(/\s+/g, ' ');
      fail(`PRD 仍存在旧退款口径的肯定式表述「${h[0]}」：…${ctx}…`);
    }
  } else {
    pass('退款口径检查：PRD 无旧「退微信」口径残留（否定式与担保解除已豁免）');
  }

  // 正向断言：关键位置必须写明新口径
  for (const [needle, where] of [
    ['退款一律退回可用余额', '订单退款规则'],
    ['一律退回可用余额', '订单管理业务逻辑'],
    ['全额退入可用余额', '退款申请原型页'],
  ]) {
    if (!prdHtml.includes(needle)) fail(`PRD 缺少新退款口径表述「${needle}」（${where}）`);
  }
  if (prdHtml.includes('全额退入可用余额') && !prdHtml.includes('可退本金（按比例折算）')) {
    pass('退款口径正向断言通过：3 处关键位置均已写明「退入可用余额」');
  }
}

// ================================================================ 12. 商品与 SKU
{
  const rows = sqlValues('goods');
  const seed = new Map(rows.map((r) => [Number(r[0]), { name: r[2], category: r[3], priceFrom: Number(r[6]) }]));
  let bad = 0;
  for (const g of dataset.goods.items) {
    const s = seed.get(g.id);
    if (!s) { fail(`商品 ${g.id}(${g.name}) 在 02_seed.sql 中不存在`); bad++; continue; }
    if (norm(s.name) !== norm(g.name)) { fail(`商品 ${g.id} 名称不一致：dataset="${g.name}" seed="${s.name}"`); bad++; }
    if (norm(s.category) !== norm(g.category)) { fail(`商品 ${g.id} 分类不一致：dataset="${g.category}" seed="${s.category}"`); bad++; }
    if (money(s.priceFrom) !== money(g.priceFrom)) { fail(`商品 ${g.id} 起价不一致：dataset=${money(g.priceFrom)} seed=${money(s.priceFrom)}`); bad++; }
  }
  if (seed.size !== dataset.goods.items.length) {
    fail(`商品条数不一致：dataset=${dataset.goods.items.length} seed=${seed.size}`);
  }
  if (!bad) pass(`商品 ${dataset.goods.items.length} 项一致（名称/分类/起价）`);
}

// ================================================================ 13. SKU
{
  const rows = sqlValues('sku');
  // sku: (id, goods_id, name, price, stock)
  const seed = new Map(rows.map((r) => [Number(r[0]), { goodsId: Number(r[1]), name: r[2], price: Number(r[3]), stock: Number(r[4]) }]));
  const flat = dataset.goods.items.flatMap((g) => g.skus.map((s) => ({ ...s, goodsId: g.id })));
  let bad = 0;
  for (const s of flat) {
    const row = seed.get(s.id);
    if (!row) { fail(`SKU ${s.id}(${s.name}) 在 02_seed.sql 中不存在`); bad++; continue; }
    if (norm(row.name) !== norm(s.name)) { fail(`SKU ${s.id} 名称不一致：dataset="${s.name}" seed="${row.name}"`); bad++; }
    if (money(row.price) !== money(s.price)) { fail(`SKU ${s.id} 价格不一致：dataset=${money(s.price)} seed=${money(row.price)}`); bad++; }
    if (row.stock !== s.stock) { fail(`SKU ${s.id} 库存不一致：dataset=${s.stock} seed=${row.stock}`); bad++; }
    if (row.goodsId !== s.goodsId) { fail(`SKU ${s.id} 归属商品不一致：dataset=${s.goodsId} seed=${row.goodsId}`); bad++; }
  }
  if (!bad) pass(`SKU ${flat.length} 项一致（名称/价格/库存/归属）`);
}

// ================================================================ 14. 商城页展示型号
// 商品分类是自由文本（不是枚举），最容易被前端自拟一套对不上的键 —— 曾经 UI 稿用
// 主粮/玩具/清洁护理，而库里是洗护/零食/用品/护理/保健，一次都筛不出数据。
// 这里断言 UI 稿商城页展示的每个商品都能在数据集里找到，且价格一致。
{
  const uiHtml = readFileSync(join(ROOT, 'petshop-ui/petshop-ui.html'), 'utf8');
  const calls = [...uiHtml.matchAll(/good\(\s*'[^']*'\s*,\s*'[^']*'\s*,\s*'([^']+)'\s*,\s*'[^']*'\s*,\s*([\d.]+)\s*\)/g)];
  if (!calls.length) {
    fail('UI 稿商城页未解析到任何商品卡（good(...) 调用是否被改动？）');
  } else {
    const byName = new Map(dataset.goods.items.map((g) => [g.name, g]));
    let bad = 0;
    for (const [, name, price] of calls) {
      const g = byName.get(name);
      if (!g) { fail(`UI 稿商城页商品「${name}」不在数据集里`); bad++; continue; }
      if (money(price) !== money(g.priceFrom)) {
        fail(`UI 稿商城页「${name}」价格不一致：文档=${money(price)} dataset=${money(g.priceFrom)}`);
        bad++;
      }
    }
    if (!bad) pass(`商城页展示 ${calls.length} 个商品与数据集一致（名称 + 价格）`);

    // 分类 chip 必须都是数据集里真实存在的分类
    const chipBlock = uiHtml.match(/class="chips">' \+([\s\S]*?)\+[\s\S]{0,40}?<\/div>/);
    const chips = chipBlock ? [...chipBlock[1].matchAll(/chip\('([^']+)'\s*,/g)].map((m) => m[1]) : [];
    const realCats = new Set(dataset.goods.items.map((g) => g.category));
    const bogus = chips.filter((c) => c !== '全部' && !realCats.has(c));
    if (bogus.length) {
      fail(`UI 稿商城页出现数据集里不存在的分类：${bogus.join(' / ')}（真实分类：${[...realCats].join(' / ')}）`);
    } else if (chips.length) {
      pass(`商城页分类 chip ${chips.length} 个均存在于数据集`);
    }
  }
}

// ================================================================ 输出
console.log('\n=== 单一事实来源校验 ===\n');
for (const m of ok) console.log('  ✓ ' + m);
if (problems.length) {
  console.log('');
  for (const m of problems) console.log('  ✗ ' + m);
  console.log(`\n结果：${ok.length} 项通过，${problems.length} 项不一致\n`);
  process.exit(1);
}
console.log(`\n结果：全部 ${ok.length} 项通过 — 文档展示值 = 数据集 = 种子数据\n`);
