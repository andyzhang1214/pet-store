/* ==========================================================================
   爪爪星球 · 门店后台 —— 配置域 + 会员管理
   与 app.js 同风格：零构建、零依赖，加载时向 PAGES 注册自己。
   app.js 里已有的工具（$ / esc / money / dt / tag / toast / openSheet / api）
   直接复用，这里不重复实现。

   三个贯穿全篇的约定，写在这里免得每处都解释：

   1) 所有 id 都是 19 位雪花号，服务端以【字符串】下发（见 JacksonConfig）。
      因此禁止对 id 做 Number() / 算术 —— 一旦转成数字就会被静默截断。
      只有 total / size / page 这些计数才 Number()。

   2) 「改一次、影响之后每一笔账」的动作（改价、改档位、改折扣、改规则）需要店长，
      日常动作（盘点库存、看列表）店员即可。前端按 isManager() 隐藏按钮，
      但真正的拦截在服务端 —— 隐藏只是不让店员做无意义的尝试。

   3) 写操作的表单里【不放操作人字段】。操作人由服务端从令牌解析，
      请求体里塞 operator 是无效的（有 AdminRoleGuardTest 守着这一点）。
   ========================================================================== */

/* ------------------------------ 共用 ------------------------------ */

/** 跨页保留的筛选条件。放在模块内而不是 state，避免污染 app.js 的全局状态 */
const CFG = {
  goods: { keyword: '', status: '', page: 1, size: 10 },
  members: { keyword: '', page: 1, size: 10 },
  board: { windowDays: 30, onlyOverdue: false },
  audit: { targetType: '', limit: 60 },
  /** 展开价目矩阵的服务 id（同时只展开一个，避免一屏几百个输入格） */
  priceOpen: null
};

const GOODS_STATUS = { ON_SALE: { t: '在售', c: 'ok' }, OFF_SALE: { t: '已下架', c: 'muted' } };
const SVC_STATUS = { ENABLED: { t: '已启用', c: 'ok' }, DISABLED: { t: '已停用', c: 'muted' } };
const CT_STATUS = { ENABLED: { t: '在售', c: 'ok' }, DISABLED: { t: '已停售', c: 'muted' } };
const CARD_USE = {
  USING: { t: '使用中', c: 'ok' },
  EXHAUSTED: { t: '已用完', c: 'muted' },
  EXPIRED: { t: '已过期', c: 'muted' },
  REFUNDED: { t: '已退款', c: 'danger' }
};
const ACCT_STATUS = { ENABLED: { t: '启用中', c: 'ok' }, DISABLED: { t: '已停用', c: 'muted' } };
const ROLE_LABEL = { MANAGER: '店长', STAFF: '店员' };
const DUE_STATUS = {
  OVERDUE: { t: '已逾期', c: 'danger' },
  DUE_SOON: { t: '临近到期', c: 'warn' },
  SCHEDULED: { t: '已排期', c: 'ok' },
  UNKNOWN: { t: '无记录', c: 'muted' }
};
const CATEGORY_LABEL = {
  WASH: '洗护', GROOM: '美容', SPA: 'SPA',
  BOARD: '寄养', VACCINE: '疫苗', WORM: '驱虫'
};
const AUDIT_ACTION = {
  CREATE: '新建', UPDATE: '修改', STATUS: '状态变更', ADJUST: '数量调整'
};
const AUDIT_TARGET = {
  GOODS: '商品', SKU: '规格', SERVICE: '服务项目', SERVICE_PRICE: '服务价目',
  CARD_TYPE: '次卡类型', CARD: '次卡', RECHARGE_TIER: '充值档位',
  MEMBER_LEVEL: '会员等级', STORE_CONFIG: '门店规则'
};
/* 审计记录里的「状态」变更存的是机器值（ON_SALE / ENABLED），要翻成人话。
   记录里只有值、没有实体类型，而同一个「状态」字段名下混着商品、服务、次卡类型
   各自的词表，所以只能合成一张扁平表，用词取各家的中性交集。
   新增「写状态审计」的实体时必须回来补这里，否则会退化成直接显示 ON_SALE。 */
const STATUS_LABEL = {
  ON_SALE: { t: '在售' }, OFF_SALE: { t: '已下架' },
  ENABLED: { t: '启用' }, DISABLED: { t: '停用' }
};
const INV_BIZ = { SALE: '销售出库', RESTORE: '取消回补', ADJUST: '人工盘点' };
const CARD_BIZ = {
  PURCHASE: '购卡', PRE_DEDUCT: '预约预扣', CONFIRM: '核销扣次',
  RELEASE: '取消释放', NO_SHOW: '未到店扣次', ADJUST: '人工调次', REFUND: '退款'
};

/** 取显示名。未登记的机器值原样返回 —— 遇到新增的枚举值时不至于显示成空白 */
function labelOf(map, key) {
  if (key === null || key === undefined) return '—';
  const m = map[key];
  return m ? m.t : String(key);
}

/** 查询串构造：只带上非空参数，避免 ?keyword=&status= 这种噪音 */
function qs(params) {
  const sp = new URLSearchParams();
  Object.keys(params).forEach((k) => {
    const v = params[k];
    if (v !== '' && v !== null && v !== undefined) sp.set(k, v);
  });
  const s = sp.toString();
  return s ? '?' + s : '';
}

function emptyHtml(icon, text, extra) {
  return `<div class="empty"><span class="empty-icon">${icon}</span>
    <div class="empty-text">${esc(text)}</div>${extra || ''}</div>`;
}

/** 下拉选项：labels 是 { 值: 显示名 } */
function opts(labels, cur) {
  return Object.keys(labels).map((k) =>
    `<option value="${k}" ${String(k) === String(cur) ? 'selected' : ''}>${esc(labels[k])}</option>`
  ).join('');
}

/**
 * 分页条。total 是字符串（Long 被序列化成文本），必须 Number() 后再算页数 ——
 * 直接 '' + 数字 拼接会得到 "1/10" 这种看似正常实则错误的页码。
 */
function pagerHtml(st, total) {
  const n = Number(total || 0);
  const pages = Math.max(1, Math.ceil(n / st.size));
  return `<div class="pager">
    <button class="btn btn-ghost sm" data-pg="${st.page - 1}" ${st.page <= 1 ? 'disabled' : ''}>上一页</button>
    <span>第 ${st.page} / ${pages} 页 · 共 ${n} 条</span>
    <button class="btn btn-ghost sm" data-pg="${st.page + 1}" ${st.page >= pages ? 'disabled' : ''}>下一页</button>
  </div>`;
}

/** 给分页按钮接上事件；翻页后重新执行 renderFn */
function bindPager(view, st, renderFn) {
  $$('[data-pg]', view).forEach((b) => {
    b.onclick = () => {
      const target = Number(b.dataset.pg);
      if (target < 1) return;
      st.page = target;
      renderFn();
    };
  });
}

/** 读一个数字输入框；空串返回 null，非法返回 NaN 供调用方拦下 */
function numVal(sel) {
  const raw = $(sel).value.trim();
  return raw === '' ? null : Number(raw);
}

function requireNum(val, label) {
  if (val === null || val === undefined || Number.isNaN(val)) {
    toast(`请填写正确的${label}`);
    return false;
  }
  return true;
}

/* ==========================================================================
   1. 商品与库存
   ========================================================================== */

async function renderGoods(view) {
  const st = CFG.goods;
  let page;
  try {
    page = await api('/admin/goods' + qs({
      keyword: st.keyword, status: st.status, page: st.page, size: st.size
    }));
  } catch (e) { return fail(view, e); }

  const records = page.records || [];
  view.innerHTML = `
    ${roleNote('盘点库存店员就能做，每次调整都会留下操作人与原因；新建商品、改价、上下架需要店长权限。')}
    <div class="searchbar">
      <input id="gKw" class="ctl" placeholder="搜索商品名称" value="${esc(st.keyword)}">
      <select id="gStatus" class="ctl" style="max-width:132px">
        <option value="">全部状态</option>
        ${opts({ ON_SALE: '在售', OFF_SALE: '已下架' }, st.status)}
      </select>
      <button id="gFind" class="btn btn-primary sm">搜索</button>
      ${isManager() ? '<button id="gNew" class="btn btn-ghost sm">＋ 新建商品</button>' : ''}
    </div>
    ${records.length ? records.map(goodsCard).join('')
      : emptyHtml('🏷️', '没有匹配的商品。新建后先添加带价格的规格，才能上架到会员端。')}
    ${Number(page.total) > st.size ? pagerHtml(st, page.total) : ''}`;

  bindPager(view, st, () => renderGoods(view));
  $('#gFind').onclick = () => { st.keyword = $('#gKw').value.trim(); st.page = 1; renderGoods(view); };
  $('#gKw').onkeydown = (e) => { if (e.key === 'Enter') $('#gFind').click(); };
  $('#gStatus').onchange = () => { st.status = $('#gStatus').value; st.page = 1; renderGoods(view); };
  if ($('#gNew')) $('#gNew').onclick = () => openGoodsSheet(null);

  $$('[data-gedit]', view).forEach((b) => {
    b.onclick = () => openGoodsSheet(records.find((g) => String(g.id) === b.dataset.gedit));
  });
  $$('[data-gsku]', view).forEach((b) => {
    b.onclick = () => openSkuSheet(b.dataset.gsku);
  });
  $$('[data-gtoggle]', view).forEach((b) => {
    b.onclick = () => toggleGoods(b.dataset.gtoggle, b.dataset.to, view);
  });
  $$('[data-skuedit]', view).forEach((b) => {
    b.onclick = () => openSkuEditSheet(b.dataset.skuedit, b.dataset.name, b.dataset.price, view);
  });
  $$('[data-stock]', view).forEach((b) => {
    b.onclick = () => openStockSheet(b.dataset.stock, b.dataset.name, Number(b.dataset.stockval), view);
  });
  $$('[data-skulog]', view).forEach((b) => {
    b.onclick = () => showInventoryLogs(b.dataset.skulog, b.dataset.skuName || '');
  });
}

function roleNote(text) {
  return `<div class="role-note"><span>💡</span><span>${esc(text)}</span></div>`;
}

function goodsCard(g) {
  const skus = g.skus || [];
  return `<div class="card">
    <div class="card-head">
      <span class="card-title">${esc(g.name)}</span>
      ${tag(GOODS_STATUS, g.status)}
    </div>
    <p class="card-desc">
      ${esc(g.category || '未分类')}
      · 起价 ${g.priceFrom != null ? money(g.priceFrom) : '—'}
      · 总库存 <b class="nums">${g.totalStock == null ? 0 : g.totalStock}</b>
      ${g.description ? '· ' + esc(g.description) : ''}
    </p>
    <div class="sku-cards">
      ${skus.length ? skus.map(skuRow).join('')
        : `<div class="sku-card" style="justify-content:center;color:var(--muted);font-size:12.5px;min-height:96px">还没有规格 —— 添加至少一个带价格的规格后才能上架</div>`}
    </div>
    <div class="card-actions" style="margin-top:12px">
      ${isManager() ? `<button class="btn btn-ghost xs" data-gsku="${g.id}">＋ 加规格</button>` : ''}
      ${isManager() ? `<button class="btn btn-ghost xs" data-gedit="${esc(g.id)}">改信息</button>` : ''}
      ${isManager()
        ? `<button class="btn ${g.status === 'ON_SALE' ? 'btn-ghost' : 'btn-primary'} xs"
             data-gtoggle="${esc(g.id)}" data-to="${g.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'}">
             ${g.status === 'ON_SALE' ? '下架' : '上架'}</button>`
        : ''}
    </div>
  </div>`;
}

function skuRow(s) {
  const stock = s.stock == null ? 0 : s.stock;
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(s.name)}</span>
    </div>
    <div class="chip-row">
      <span class="meta-chip price">${money(s.price)}</span>
      <span class="meta-chip ${stock <= 0 ? 'delta-down' : ''}">库存 ${stock}</span>
    </div>
    <div class="sku-card-actions">
      <button class="btn btn-ghost xs" data-stock="${esc(s.id)}"
        data-name="${esc(s.name)}" data-stockval="${stock}">盘点</button>
      ${isManager() ? `<button class="btn btn-ghost xs" data-skuedit="${esc(s.id)}"
        data-name="${esc(s.name)}" data-price="${s.price}">改价</button>` : ''}
      <button class="btn btn-ghost xs" data-skulog="${esc(s.id)}" data-sku-name="${esc(s.name)}">流水</button>
    </div>
  </div>`;
}

/** 新建 / 编辑商品。g 为 null 时是新建 */
function openGoodsSheet(g) {
  if (!requireManager('商品信息')) return;
  const isNew = !g;
  openSheet(`
    <h3>${isNew ? '新建商品' : '修改商品信息'}</h3>
    ${isNew ? `<p class="card-desc">新商品一律先落在<b>下架</b>状态 —— 规格没配全就上架，会员点进去会下不了单。</p>` : ''}
    <label class="field"><span>商品名称（必填）</span>
      <input id="gName" class="ctl" value="${isNew ? '' : esc(g.name)}" placeholder="例如 皇家幼猫粮 2kg"></label>
    <label class="field"><span>分类</span>
      <input id="gCat" class="ctl" value="${isNew ? '' : esc(g.category || '')}" placeholder="例如 主粮"></label>
    <label class="field"><span>商品图片</span>
      <input id="gImg" class="ctl" value="${isNew ? '' : esc((g.images || []).join(','))}"
        placeholder="图片地址，多个用英文逗号分隔">
      <span class="fhint">会员端列表与详情页都取这个字段，第一张作为封面。</span></label>
    <label class="field"><span>商品描述</span>
      <textarea id="gDesc" class="ctl" placeholder="一句话说明，例如：适合 1 岁以下幼猫">${isNew ? '' : esc(g.description || '')}</textarea></label>
    ${isNew ? `
      <hr class="hr">
      <p class="card-desc">可直接带上第一个规格（也可稍后在列表里「＋ 加规格」）。</p>
      <div class="form-2col">
        <label class="field"><span>规格名</span><input id="gSkuName" class="ctl" placeholder="例如 2kg 装"></label>
        <label class="field"><span>价格</span><input id="gSkuPrice" class="ctl" inputmode="decimal" placeholder="例如 129.00"></label>
      </div>
      <label class="field"><span>初始库存</span>
        <input id="gSkuStock" class="ctl" inputmode="numeric" placeholder="不填按 0 计"></label>` : ''}
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const name = $('#gName').value.trim();
    if (!name) { toast('商品名称不能为空'); return; }
    const body = {
      name,
      category: $('#gCat').value.trim(),
      images: $('#gImg').value.trim(),
      description: $('#gDesc').value.trim()
    };
    if (isNew) {
      const skuName = $('#gSkuName').value.trim();
      if (skuName) {
        const price = numVal('#gSkuPrice');
        if (!requireNum(price, '规格价格') || price <= 0) { toast('规格价格必须大于 0'); return; }
        const stock = numVal('#gSkuStock');
        if (stock !== null && (Number.isNaN(stock) || stock < 0)) { toast('初始库存不能为负'); return; }
        body.skus = [{ name: skuName, price, stock: stock === null ? 0 : stock }];
      }
    }
    try {
      await api(isNew ? '/admin/goods' : '/admin/goods/' + g.id,
        { method: isNew ? 'POST' : 'PUT', body });
      closeSheet();
      toast(isNew ? '商品已创建（当前为下架状态）' : '已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

/** 给已有商品加规格 */
function openSkuSheet(goodsId) {
  if (!requireManager('添加规格')) return;
  openSheet(`
    <h3>添加规格</h3>
    <p class="card-desc">容器里存的是成交价快照，改价只影响之后的新订单，已下单的顾客不受影响。</p>
    <label class="field"><span>规格名（必填）</span>
      <input id="sName" class="ctl" placeholder="例如 5kg 装 / 大号"></label>
    <div class="form-2col">
      <label class="field"><span>价格（必填）</span>
        <input id="sPrice" class="ctl" inputmode="decimal" placeholder="例如 199.00"></label>
      <label class="field"><span>初始库存</span>
        <input id="sStock" class="ctl" inputmode="numeric" placeholder="不填按 0 计"></label>
    </div>
    <span class="fhint">一个商品最多 50 个规格；再多请拆成多个商品，否则会员端列表会变成长图。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">添加</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const name = $('#sName').value.trim();
    const price = numVal('#sPrice');
    const stock = numVal('#sStock');
    if (!name) { toast('规格名称不能为空'); return; }
    if (!requireNum(price, '价格') || price <= 0) { toast('价格必须大于 0'); return; }
    if (stock !== null && (Number.isNaN(stock) || stock < 0)) { toast('库存不能为负'); return; }
    try {
      await api('/admin/goods/' + goodsId + '/skus',
        { method: 'POST', body: { name, price, stock: stock === null ? 0 : stock } });
      closeSheet();
      toast('规格已添加');
      render();
    } catch (e) { toast(e.message || '添加失败'); }
  };
}

/** 改规格名与价格 */
function openSkuEditSheet(skuId, name, price, view) {
  if (!requireManager('修改规格')) return;
  openSheet(`
    <h3>修改规格</h3>
    <p class="card-desc">改价会记入「配置变更记录」并带上你的账号，历史订单里的成交价不受影响。</p>
    <label class="field"><span>规格名</span>
      <input id="seName" class="ctl" value="${esc(name)}"></label>
    <label class="field"><span>价格（必须大于 0）</span>
      <input id="sePrice" class="ctl" inputmode="decimal" value="${price == null ? '' : price}"></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">保存</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const nm = $('#seName').value.trim();
    const pr = numVal('#sePrice');
    if (!nm) { toast('规格名不能为空'); return; }
    if (!requireNum(pr, '价格') || pr <= 0) { toast('价格必须大于 0'); return; }
    try {
      await api('/admin/skus/' + skuId, { method: 'PUT', body: { name: nm, price: pr } });
      closeSheet();
      toast('已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

/** 盘点库存：delta 可正可负，原因必填 */
function openStockSheet(skuId, name, cur, view) {
  openSheet(`
    <h3>盘点库存 · ${esc(name)}</h3>
    <p class="card-desc">当前库存 <b class="nums">${cur}</b>。
      正数入库（进货 / 取消订单回补），负数出库（报损 / 过期处理）。</p>
    <label class="field"><span>调整数量（必填，可负）</span>
      <input id="stDelta" class="ctl" inputmode="numeric" placeholder="例如 -3 或 10"></label>
    <label class="field"><span>盘点原因（必填）</span>
      <textarea id="stRemark" class="ctl"
        placeholder="例如：盘点发现 2 袋猫粮临期破损，做报损处理"></textarea></label>
    <span class="fhint">库存调整没有对应单据，原因会写进库存流水和配置变更记录 ——
      这是日后查「货少了去哪了」唯一的线索。调整后不允许变成负数。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">确认调整</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const delta = numVal('#stDelta');
    const remark = $('#stRemark').value.trim();
    if (!requireNum(delta, '调整数量') || delta === 0) { toast('调整数量不能为 0'); return; }
    if (!remark) { toast('请填写盘点原因'); return; }
    try {
      const r = await api('/admin/skus/' + skuId + '/stock', { method: 'POST', body: { delta, remark } });
      closeSheet();
      toast(`库存 ${r.stockBefore} → ${r.stockAfter}`);
      render();
    } catch (e) { toast(e.message || '调整失败'); }
  };
}

async function toggleGoods(goodsId, to, view) {
  if (!requireManager('上下架')) return;
  try {
    await api('/admin/goods/' + goodsId + '/status', { method: 'POST', body: { status: to } });
    toast(to === 'ON_SALE' ? '已上架' : '已下架');
    render();
  } catch (e) { toast(e.message || '操作失败'); }
}

async function showInventoryLogs(skuId, name) {
  // 'logs'：流水弹层只比卡片略宽、列表内部滚动，见 app.css 的 .sheet.logs
  openSheet(`<h3>库存流水</h3><p class="card-desc">${esc(name || '')}</p>
    <div class="empty"><span class="empty-icon">⏳</span><div class="empty-text">加载中…</div></div>`, 'logs');
  try {
    const logs = await api('/admin/skus/' + skuId + '/inventory-logs?limit=60');
    const body = logs.length ? `<div class="log-cards">${logs.map((l) => `
      <div class="log-card">
        <span class="log-time">${dt(l.createdAt)}</span>
        <div class="log-main">
          <div class="row-title">${esc(INV_BIZ[l.bizType] || l.bizType)}
            <span class="${l.qty < 0 ? 'delta-down' : 'delta-up'}">${l.qty > 0 ? '+' : ''}${l.qty}</span></div>
          <div class="row-sub">结存 ${l.stockAfter} · 操作人 ${esc(l.operator || '—')}
            ${l.bizNo ? '· 单号 ' + esc(l.bizNo) : ''}</div>
        </div>
      </div>`).join('')}</div>` : emptyHtml('📭', '这个规格还没有库存变动记录');
    openSheet(`<h3>库存流水</h3><p class="card-desc">${esc(name || '')}</p>${body}
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`, 'logs');
    $('#sheetCancel').onclick = closeSheet;
  } catch (e) {
    openSheet(`<h3>库存流水</h3>${emptyHtml('😿', e.message || '加载失败')}
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`, 'logs');
    $('#sheetCancel').onclick = closeSheet;
  }
}

PAGES.goods = renderGoods;

/* ==========================================================================
   2. 服务与价目
   ========================================================================== */

async function renderServices(view) {
  let services = [];
  let specs = [];
  try {
    [services, specs] = await Promise.all([api('/admin/services'), api('/admin/specs')]);
  } catch (e) { return fail(view, e); }

  // 价目矩阵失败不该让整页崩掉：单独 catch，把这块显示成「未定价」而不是白屏
  CFG.priceOpenRows = CFG.priceOpen
    ? await api('/admin/services/' + CFG.priceOpen + '/prices').catch(() => [])
    : null;

  view.innerHTML = `
    ${roleNote('服务项目、价目、规格都属于「改一次、影响之后每一笔账」的参数，需要店长权限；店员可以查看。')}
    <div class="card">
      <div class="card-head">
        <span class="card-title">服务项目</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="svcNew">＋ 新建服务</button>' : ''}
      </div>
      <p class="card-desc">时长决定占用几个时段，容量是同一时段可同时接待的数量 —— 两个都直接决定顾客能不能约上。</p>
      ${services.length ? `<div class="sku-cards">${services.map(svcRow).join('')}</div>`
        : emptyHtml('💅', '还没有服务项目')}
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-head">
        <span class="card-title">规格（宠物类型 × 体型 × 毛长）</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="specNew">＋ 新建规格</button>' : ''}
      </div>
      <p class="card-desc">价目是「服务 × 规格」的矩阵。规格是全店共用的，新建后每个服务的价目里会多出一列待定价。</p>
      <div class="price-grid">
        ${specs.length ? specs.map((s) => `
          <div class="price-cell"><span class="pc-name">${esc(s.name || specLabel(s))}</span></div>`).join('')
          : '<span class="missing-hint">还没有规格</span>'}
      </div>
    </div>`;

  if ($('#svcNew')) $('#svcNew').onclick = () => openServiceSheet(null);
  if ($('#specNew')) $('#specNew').onclick = () => openSpecSheet();

  $$('[data-sedit]', view).forEach((b) => {
    b.onclick = () => openServiceSheet(services.find((s) => String(s.id) === b.dataset.sedit));
  });
  $$('[data-stoggle]', view).forEach((b) => {
    b.onclick = () => toggleService(b.dataset.stoggle, b.dataset.to, view);
  });
  $$('[data-price-open]', view).forEach((b) => {
    b.onclick = () => { CFG.priceOpen = b.dataset.priceOpen; renderServices(view); };
  });
  $$('[data-price-close]', view).forEach((b) => {
    b.onclick = () => { CFG.priceOpen = null; renderServices(view); };
  });
  $$('[data-price]', view).forEach((c) => {
    c.onclick = () => openPriceSheet(c.dataset.price, c.dataset.spec, c.dataset.name, c.dataset.priceval, view);
  });
}

function specLabel(s) {
  return [s.petType, s.size, s.hair].filter(Boolean).join(' / ') || '规格#' + s.id;
}

function svcRow(s) {
  const open = String(CFG.priceOpen) === String(s.id);
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(s.name)}</span>
      ${tag(SVC_STATUS, s.status)}
    </div>
    <div class="chip-row">
      <span class="meta-chip">${esc(CATEGORY_LABEL[s.category] || s.category || '未分类')}</span>
      <span class="meta-chip">时长 ${s.durationSlots == null ? '—' : s.durationSlots} 时段</span>
      <span class="meta-chip">容量 ${s.capacity == null ? '—' : s.capacity}</span>
    </div>
    <div class="sku-card-actions">
      <button class="btn btn-ghost xs" data-price-open="${esc(s.id)}">${open ? '收起价目' : '价目'}</button>
      ${isManager() ? `<button class="btn btn-ghost xs" data-sedit="${esc(s.id)}">编辑</button>` : ''}
      ${isManager() ? `<button class="btn ${s.status === 'ENABLED' ? 'btn-ghost' : 'btn-primary'} xs"
        data-stoggle="${esc(s.id)}" data-to="${s.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'}">
        ${s.status === 'ENABLED' ? '停用' : '启用'}</button>` : ''}
    </div>
    ${open ? `<div class="sku-card-extra">${priceMatrixBlock(s.id)}</div>` : ''}
  </div>`;
}

/**
 * 价目矩阵。空档（price 为 null）用不同底色标出来 ——
 * 「没定价」和「定价为 0」在数据上完全不同，但在界面上很容易长得一样。
 */
function priceMatrixBlock(svcId) {
  const rows = CFG.priceOpenRows && String(CFG.priceOpen) === String(svcId) ? CFG.priceOpenRows : null;
  if (!rows) return `<div class="missing-hint" style="margin-top:10px">价目加载中…</div>`;
  if (!rows.length) return `<div class="missing-hint" style="margin-top:10px">还没有可用规格，先去上面新建规格</div>`;
  return `<div class="price-grid" style="margin-top:12px">
    ${rows.map((r) => `
      <div class="price-cell ${r.price == null ? 'is-missing' : ''}"
           data-price="${esc(svcId)}" data-spec="${esc(r.specId)}"
           data-name="${esc(r.specName)}" data-priceval="${r.price == null ? '' : r.price}">
        <span class="pc-name">${esc(r.specName)}</span>
        <span class="pc-price">${r.price != null ? money(r.price) : '<span class="missing-hint">未定价</span>'}</span>
      </div>`).join('')}
  </div>`;
}

function openServiceSheet(s) {
  if (!requireManager('服务项目配置')) return;
  const isNew = !s;
  openSheet(`
    <h3>${isNew ? '新建服务项目' : '编辑服务项目'}</h3>
    <label class="field"><span>服务名称（必填）</span>
      <input id="svName" class="ctl" value="${isNew ? '' : esc(s.name)}" placeholder="例如 猫咪基础洗护"></label>
    <label class="field"><span>服务分类</span>
      <select id="svCat" class="ctl">${opts(CATEGORY_LABEL, isNew ? 'WASH' : s.category)}</select>
      <span class="fhint">分类会影响会员等级里的「按分类折扣」，选错会让折扣算到别的服务上。</span></label>
    <div class="form-2col">
      <label class="field"><span>占用时段数</span>
        <input id="svDur" class="ctl" inputmode="numeric"
          value="${isNew || s.durationSlots == null ? '' : s.durationSlots}" placeholder="1 个时段 = 30 分钟"></label>
      <label class="field"><span>同时段容量</span>
        <input id="svCap" class="ctl" inputmode="numeric"
          value="${isNew || s.capacity == null ? '' : s.capacity}" placeholder="能同时接待几只"></label>
    </div>
    <span class="fhint">容量填 0 会让这个服务在所有时段都约不上，请填正整数。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const name = $('#svName').value.trim();
    if (!name) { toast('服务名称不能为空'); return; }
    const body = { name, category: $('#svCat').value };
    const dur = numVal('#svDur');
    const cap = numVal('#svCap');
    if (dur !== null) {
      if (Number.isNaN(dur) || dur <= 0) { toast('占用时段数必须大于 0'); return; }
      body.durationSlots = dur;
    }
    if (cap !== null) {
      if (Number.isNaN(cap) || cap <= 0) { toast('容量必须大于 0'); return; }
      body.capacity = cap;
    }
    try {
      await api(isNew ? '/admin/services' : '/admin/services/' + s.id,
        { method: isNew ? 'POST' : 'PUT', body });
      closeSheet();
      toast(isNew ? '服务已创建' : '已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

function openSpecSheet() {
  if (!requireManager('新建规格')) return;
  openSheet(`
    <h3>新建规格</h3>
    <p class="card-desc">规格 = 宠物类型 + 体型 + 毛长。价目按「服务 × 规格」定价，所以这里每加一条，
      所有服务的价目都会多出一个待定价的格子（会标成橙色）。</p>
    <label class="field"><span>宠物类型</span>
      <input id="spType" class="ctl" placeholder="例如 猫 / 狗"></label>
    <div class="form-2col">
      <label class="field"><span>体型</span><input id="spSize" class="ctl" placeholder="例如 小型 / 中型"></label>
      <label class="field"><span>毛长</span><input id="spHair" class="ctl" placeholder="例如 短毛 / 长毛"></label>
    </div>
    <label class="field"><span>显示名（可选）</span>
      <input id="spName" class="ctl" placeholder="留空则显示为「猫 / 小型 / 短毛」"></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">创建</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const body = {
      petType: $('#spType').value.trim(),
      size: $('#spSize').value.trim(),
      hair: $('#spHair').value.trim(),
      name: $('#spName').value.trim()
    };
    if (!body.petType && !body.name) { toast('至少要填宠物类型或显示名'); return; }
    try {
      await api('/admin/specs', { method: 'POST', body });
      closeSheet();
      toast('规格已创建，去各服务的价目里定价');
      render();
    } catch (e) { toast(e.message || '创建失败'); }
  };
}

/** 设置某一格价格：已存在则改、不存在则建 */
function openPriceSheet(svcId, specId, specName, curPrice, view) {
  if (!requireManager('服务价目')) return;
  openSheet(`
    <h3>设置价目</h3>
    <p class="card-desc">${esc(specName)}</p>
    <label class="field"><span>价格（必填，大于 0）</span>
      <input id="prVal" class="ctl" inputmode="decimal"
        value="${curPrice === '' || curPrice === undefined ? '' : curPrice}" placeholder="例如 128.00"></label>
    <span class="fhint">改价会记入「配置变更记录」，带你的账号与改前改后的值。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">保存</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const price = numVal('#prVal');
    if (!requireNum(price, '价格') || price <= 0) { toast('价格必须大于 0'); return; }
    try {
      await api('/admin/services/' + svcId + '/prices',
        { method: 'PUT', body: { specId, price } });
      closeSheet();
      toast('价目已保存');
      await loadPriceRows(view);
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

async function toggleService(svcId, to, view) {
  if (!requireManager('服务启停')) return;
  try {
    await api('/admin/services/' + svcId + '/status', { method: 'POST', body: { status: to } });
    toast(to === 'ENABLED' ? '已启用' : '已停用');
    render();
  } catch (e) { toast(e.message || '操作失败'); }
}

/**
 * 保存价目后就地重绘，而不是走 render()。
 * render() 会把页面清空再重建，展开的价目矩阵会折叠、滚动位置也回到顶部 ——
 * 店员连续给十几个格子定价时，每次都被弹回开头是很难忍受的。
 */
function loadPriceRows(view) {
  return renderServices(view);
}

PAGES.services = renderServices;

/* ==========================================================================
   3. 次卡类型与充值档位
   ========================================================================== */

async function renderWalletCfg(view) {
  let types = [];
  let tiers = [];
  let services = [];
  let specs = [];
  try {
    [types, tiers, services, specs] = await Promise.all([
      api('/admin/card-types?includeDisabled=true'),
      api('/admin/recharge-tiers'),
      api('/admin/services'),
      api('/admin/specs')
    ]);
  } catch (e) { return fail(view, e); }

  view.innerHTML = `
    ${roleNote('次卡类型与充值档位决定「顾客花多少钱拿到多少价值」，与退款审核同级，需要店长权限；店员可以查看。都不提供删除：停售即可，删掉会破坏历史可读性。')}

    <div class="card">
      <div class="card-head">
        <span class="card-title">次卡类型</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="ctNew">＋ 新建卡型</button>' : ''}
      </div>
      <p class="card-desc">次数与价格定价，适用服务决定这张卡能核销哪些项目。</p>
      ${types.length ? `<div class="sku-cards">${types.map(ctRow).join('')}</div>`
        : emptyHtml('🎫', '还没有次卡类型')}
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-head">
        <span class="card-title">充值档位</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="trNew">＋ 新建档位</button>' : ''}
      </div>
      <p class="card-desc">会员充值时的固定金额与赠送额。金额必须与所选档位完全一致才可享受赠送。</p>
      ${tiers.length ? `<div class="sku-cards">${tiers.map(tierRow).join('')}</div>`
        : emptyHtml('💰', '还没有充值档位，会员端将无法充值')}
    </div>`;

  if ($('#ctNew')) $('#ctNew').onclick = () => openCardTypeSheet(null, services, specs);
  if ($('#trNew')) $('#trNew').onclick = () => openTierSheet(null);

  $$('[data-ctedit]', view).forEach((b) => {
    b.onclick = () => openCardTypeSheet(
      types.find((t) => String(t.id) === b.dataset.ctedit), services, specs);
  });
  $$('[data-cttoggle]', view).forEach((b) => {
    b.onclick = () => toggleCardType(b.dataset.cttoggle, b.dataset.to, view);
  });
  $$('[data-csold]', view).forEach((b) => {
    b.onclick = () => showSoldCount(b.dataset.csold, b.dataset.ctname);
  });
  $$('[data-tredit]', view).forEach((b) => {
    b.onclick = () => openTierSheet(tiers.find((t) => String(t.id) === b.dataset.tredit));
  });
  $$('[data-trtoggle]', view).forEach((b) => {
    b.onclick = () => toggleTier(b.dataset.trtoggle, Number(b.dataset.to));
  });
}

function ctRow(t) {
  const valid = t.validMode === 'FIXED'
    ? `固定到期 ${t.validUntil || '未设置'}`
    : `有效期 ${t.validMonths == null ? '—' : t.validMonths} 个月`;
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(t.name)}</span>
      ${tag(CT_STATUS, t.status)}
    </div>
    <div class="chip-row">
      <span class="meta-chip">${t.times == null ? '—' : t.times} 次</span>
      <span class="meta-chip price">${money(t.price)}</span>
      <span class="meta-chip">${esc(valid)}</span>
    </div>
    <div class="sku-card-actions">
      <button class="btn btn-ghost xs" data-csold="${esc(t.id)}" data-ctname="${esc(t.name)}">已售</button>
      ${isManager() ? `<button class="btn btn-ghost xs" data-ctedit="${esc(t.id)}">编辑</button>` : ''}
      ${isManager() ? `<button class="btn ${t.status === 'ENABLED' ? 'btn-ghost' : 'btn-primary'} xs"
        data-cttoggle="${esc(t.id)}" data-to="${t.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'}">
        ${t.status === 'ENABLED' ? '停售' : '开售'}</button>` : ''}
    </div>
  </div>`;
}

function tierRow(t) {
  const on = Number(t.enabled) === 1;
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">充 ${money(t.amount)}</span>
      ${on ? '<span class="tag ok">启用中</span>' : '<span class="tag muted">已停用</span>'}
    </div>
    <div class="chip-row">
      <span class="meta-chip delta-up">赠 ${money(t.gift)}</span>
      <span class="meta-chip price">实得 ${money(Number(t.amount) + Number(t.gift || 0))}</span>
    </div>
    <div class="sku-card-actions">
      ${isManager() ? `<button class="btn btn-ghost xs" data-tredit="${esc(t.id)}">编辑</button>` : ''}
      ${isManager() ? `<button class="btn ${on ? 'btn-ghost' : 'btn-primary'} xs"
        data-trtoggle="${esc(t.id)}" data-to="${on ? 0 : 1}">${on ? '停用' : '启用'}</button>` : ''}
    </div>
  </div>`;
}

// 库里 serviceIds / specIds 存的是 "[101,102]" JSON 数组文本（见后端 normalizeJsonArray）。
// 直接 split(',') 会把首尾元素带上方括号，勾选态永远回显不上 —— 先剥方括号再去引号再切。
function idListOf(v) {
  return String(v == null ? '' : v)
    .replace(/^\[/, '').replace(/\]$/, '')
    .split(',').map((s) => s.trim().replace(/^"|"$/g, '')).filter(Boolean);
}

function openCardTypeSheet(t, services, specs) {
  if (!requireManager('次卡类型')) return;
  const isNew = !t;
  const specIds = isNew ? [] : idListOf(t.specIds);
  const svcIds = isNew ? [] : idListOf(t.serviceIds);
  const validMode = isNew ? 'MONTHS' : (t.validMode || 'MONTHS');
  openSheet(`
    <h3>${isNew ? '新建次卡类型' : '编辑次卡类型'}</h3>
    <label class="field"><span>卡型名称（必填）</span>
      <input id="ctName" class="ctl" value="${isNew ? '' : esc(t.name)}" placeholder="例如 洗护 10 次卡"></label>
    <div class="form-2col">
      <label class="field"><span>包含次数（必填）</span>
        <input id="ctTimes" class="ctl" inputmode="numeric"
          value="${isNew || t.times == null ? '' : t.times}" placeholder="例如 10"></label>
      <label class="field"><span>售价（必填）</span>
        <input id="ctPrice" class="ctl" inputmode="decimal"
          value="${isNew || t.price == null ? '' : t.price}" placeholder="例如 880.00"></label>
    </div>
    <label class="field"><span>有效期计算方式</span>
      <select id="ctMode" class="ctl">
        <option value="MONTHS" ${validMode === 'MONTHS' ? 'selected' : ''}>按购买日起 N 个月</option>
        <option value="FIXED" ${validMode === 'FIXED' ? 'selected' : ''}>固定到期日</option>
      </select></label>
    <div class="form-2col">
      <label class="field"><span>有效月数</span>
        <input id="ctMonths" class="ctl" inputmode="numeric"
          value="${isNew || t.validMonths == null ? '' : t.validMonths}" placeholder="例如 12"></label>
      <label class="field"><span>固定到期日</span>
        <input id="ctUntil" class="ctl" type="date" value="${isNew ? '' : esc(t.validUntil || '')}"></label>
    </div>
    <span class="fhint">只填与所选方式对应的那一个，另一个会被忽略。</span>
    <label class="field"><span>适用服务（至少选一个）</span></label>
    <div class="price-grid">
      ${(services || []).length ? services.map((s) => `
        <label class="fcheck" style="margin:0">
          <input type="checkbox" data-svc="${esc(s.id)}"
            ${svcIds.includes(String(s.id)) ? 'checked' : ''}>
          <span>${esc(s.name)}</span>
        </label>`).join('') : '<span class="missing-hint">还没有服务项目，请先在「服务与价目」里新建</span>'}
    </div>
    <span class="fhint warn">没选服务的次卡在核销时会找不到可用范围，顾客买了也用不了。</span>
    <label class="field"><span>适用规格（至少选一个）</span></label>
    <div class="price-grid">
      ${(specs || []).length ? specs.map((s) => `
        <label class="fcheck" style="margin:0">
          <input type="checkbox" data-spec="${esc(s.id)}"
            ${specIds.includes(String(s.id)) ? 'checked' : ''}>
          <span>${esc(s.name || specLabel(s))}</span>
        </label>`).join('') : '<span class="missing-hint">还没有规格，请先在「服务与价目」里新建</span>'}
    </div>
    <span class="fhint warn">没选规格的次卡在核销时会找不到可用范围，顾客买了也用不了。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const name = $('#ctName').value.trim();
    const times = numVal('#ctTimes');
    const price = numVal('#ctPrice');
    const mode = $('#ctMode').value;
    if (!name) { toast('卡型名称不能为空'); return; }
    if (!requireNum(times, '包含次数') || times <= 0) { toast('次数必须大于 0'); return; }
    if (!requireNum(price, '售价') || price <= 0) { toast('售价必须大于 0'); return; }
    const pickedSpecs = $$('[data-spec]:checked').map((c) => c.dataset.spec);
    const pickedSvcs = $$('[data-svc]:checked').map((c) => c.dataset.svc);
    if (!pickedSvcs.length) { toast('请至少选择一个适用服务'); return; }
    if (!pickedSpecs.length) { toast('请至少选择一个适用规格'); return; }
    // serviceIds / specIds 传空会被服务端规范化成 null，等于清掉适用范围 ——
    // 所以这里两个都必须非空才提交（上面已经拦过）
    const body = {
      name, times, price,
      serviceIds: pickedSvcs.join(','),
      specIds: pickedSpecs.join(','),
      validMode: mode
    };
    if (mode === 'FIXED') {
      const until = $('#ctUntil').value;
      if (!until) { toast('固定到期日必须填写'); return; }
      body.validUntil = until;
    } else {
      const months = numVal('#ctMonths');
      if (!requireNum(months, '有效月数') || months <= 0) { toast('有效月数必须大于 0'); return; }
      body.validMonths = months;
    }
    try {
      await api(isNew ? '/admin/card-types' : '/admin/card-types/' + t.id,
        { method: isNew ? 'POST' : 'PUT', body });
      closeSheet();
      toast(isNew ? '卡型已创建' : '已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

function openTierSheet(t) {
  if (!requireManager('充值档位')) return;
  const isNew = !t;
  openSheet(`
    <h3>${isNew ? '新建充值档位' : '编辑充值档位'}</h3>
    <div class="form-2col">
      <label class="field"><span>充值金额（必填）</span>
        <input id="trAmount" class="ctl" inputmode="decimal"
          value="${isNew || t.amount == null ? '' : t.amount}" placeholder="例如 500.00"></label>
      <label class="field"><span>赠送金额</span>
        <input id="trGift" class="ctl" inputmode="decimal"
          value="${isNew || t.gift == null ? '' : t.gift}" placeholder="例如 50.00，不赠送填 0"></label>
    </div>
    <span class="fhint">同一个金额不要建两条档位 —— 会员充值时会按金额匹配，重复档位的行为不确定。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const amount = numVal('#trAmount');
    let gift = numVal('#trGift');
    if (!requireNum(amount, '充值金额') || amount <= 0) { toast('充值金额必须大于 0'); return; }
    if (gift === null) gift = 0;
    if (Number.isNaN(gift) || gift < 0) { toast('赠送金额不能为负'); return; }
    try {
      await api(isNew ? '/admin/recharge-tiers' : '/admin/recharge-tiers/' + t.id,
        { method: isNew ? 'POST' : 'PUT', body: { amount, gift, enabled: 1 } });
      closeSheet();
      toast(isNew ? '档位已创建' : '已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

async function toggleCardType(id, to, view) {
  if (!requireManager('卡型停售')) return;
  try {
    await api('/admin/card-types/' + id + '/status', { method: 'POST', body: { status: to } });
    toast(to === 'ENABLED' ? '已开售' : '已停售');
    render();
  } catch (e) { toast(e.message || '操作失败'); }
}

async function toggleTier(id, to) {
  if (!requireManager('充值档位启停')) return;
  try {
    await api('/admin/recharge-tiers/' + id + '/status', { method: 'POST', body: { enabled: to } });
    toast(to === 1 ? '已启用' : '已停用');
    render();
  } catch (e) { toast(e.message || '操作失败'); }
}

async function showSoldCount(id, name) {
  try {
    const r = await api('/admin/card-types/' + id + '/sold-count');
    openSheet(`<h3>已售数量</h3>
      <p class="card-desc">${esc(name || '')}</p>
      <div class="kv"><span class="kv-k">累计卖出</span><span class="kv-v nums">${r.soldCount}</span></div>
      <span class="fhint">已售出的卡会继续按原卡型规则核销与退款，所以卡型只能停售、不能删除。</span>
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`);
    $('#sheetCancel').onclick = closeSheet;
  } catch (e) { toast(e.message || '查询失败'); }
}

PAGES.walletcfg = renderWalletCfg;

/* ==========================================================================
   4. 会员等级
   ========================================================================== */

async function renderLevels(view) {
  let levels = [];
  try {
    levels = await api('/admin/member-levels');
  } catch (e) { return fail(view, e); }

  view.innerHTML = `
    ${roleNote('等级折扣直接影响收入，全部需要店长权限。等级不提供删除 —— 会员身上存的是等级编号，删掉会让这些会员指向不存在的档位，不用了就停用。')}
    <div class="card">
      <div class="card-head">
        <span class="card-title">会员等级</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="lvNew">＋ 新建等级</button>' : ''}
      </div>
      <p class="card-desc">累计消费达到门槛后自动升级；折扣按「服务分类」或全场生效，两个都有时取更优的那个。</p>
      ${levels.length ? `<div class="sku-cards">${levels.map(lvRow).join('')}</div>`
        : emptyHtml('🏅', '还没有会员等级配置')}
    </div>`;

  if ($('#lvNew')) $('#lvNew').onclick = () => openLevelSheet(null, levels);
  $$('[data-lvedit]', view).forEach((b) => {
    b.onclick = () => openLevelSheet(levels.find((l) => String(l.id) === b.dataset.lvedit), levels);
  });
}

function lvRow(l) {
  const on = Number(l.enabled) === 1;
  let sd = {};
  try { sd = JSON.parse(l.serviceDiscount || '{}') || {}; } catch (e) { sd = {}; }
  const sdText = Object.keys(sd).length
    ? Object.keys(sd).map((k) => `${CATEGORY_LABEL[k] || k} ${szDiscount(sd[k])}`).join(' · ')
    : '无分类折扣';
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">L${l.level} ${esc(l.name)}</span>
      ${on ? '<span class="tag ok">启用中</span>' : '<span class="tag muted">已停用</span>'}
    </div>
    <div class="chip-row">
      <span class="meta-chip">门槛 ${money(l.threshold)}</span>
      <span class="meta-chip">全场 ${l.generalDiscount == null ? '无折扣' : szDiscount(l.generalDiscount)}</span>
    </div>
    <div class="row-sub" style="margin-top:2px">${esc(sdText)}</div>
    <div class="sku-card-actions">
      ${isManager() ? `<button class="btn btn-ghost xs" data-lvedit="${esc(l.id)}">编辑</button>` : ''}
    </div>
  </div>`;
}

/** 0.95 → "9.5 折" */
function szDiscount(v) {
  const n = Number(v);
  if (!n) return '—';
  return (n * 10).toFixed(n * 10 % 1 === 0 ? 0 : 1) + ' 折';
}

function openLevelSheet(l, levels) {
  if (!requireManager('会员等级')) return;
  const isNew = !l;
  let sd = {};
  try { sd = JSON.parse((l && l.serviceDiscount) || '{}') || {}; } catch (e) { sd = {}; }
  // 新等级的默认等级号 = 现有最大 +1
  const nextLevel = levels.reduce((m, x) => Math.max(m, Number(x.level) || 0), 0) + 1;
  openSheet(`
    <h3>${isNew ? '新建会员等级' : '编辑会员等级'}</h3>
    <div class="form-2col">
      <label class="field"><span>等级号</span>
        <input class="ctl" value="${isNew ? nextLevel : l.level}" disabled>
        <span class="fhint">等级号由系统顺序分配，不手工填写。</span></label>
      <label class="field"><span>等级名称（必填）</span>
        <input id="lvName" class="ctl" value="${isNew ? '' : esc(l.name)}" placeholder="例如 银卡会员"></label>
    </div>
    <label class="field"><span>升级门槛（累计消费）</span>
      <input id="lvTh" class="ctl" inputmode="decimal"
        value="${isNew || l.threshold == null ? '' : l.threshold}" placeholder="例如 1000.00"></label>
    <label class="field"><span>全场折扣</span>
      <input id="lvGen" class="ctl" inputmode="decimal"
        value="${isNew || l.generalDiscount == null ? '' : l.generalDiscount}"
        placeholder="填 0.95 表示 9.5 折；不打折留空">
      <span class="fhint">取值范围 (0, 1]，例如 0.95 = 9.5 折。填 1 等于不打折。</span></label>
    <label class="field"><span>按服务分类折扣</span>
      <span class="fhint">只给部分分类打折时填这里，留空表示该分类按全场折扣走。</span></label>
    <div class="price-grid">
      ${Object.keys(CATEGORY_LABEL).map((k) => `
        <div class="price-cell">
          <span class="pc-name">${esc(CATEGORY_LABEL[k])}</span>
          <input class="ctl" style="max-width:96px" inputmode="decimal"
            data-cat="${k}" value="${sd[k] == null ? '' : sd[k]}" placeholder="如 0.92">
        </div>`).join('')}
    </div>
    <label class="field" style="margin-top:14px"><span>权益说明</span>
      <textarea id="lvBen" class="ctl"
        placeholder='可写 JSON 或直接写文字，例如 {"note":"生日双倍积分"}'>${isNew ? '' : esc(l.benefits || '')}</textarea></label>
    <label class="fcheck"><input type="checkbox" id="lvOn" ${isNew || Number(l.enabled) === 1 ? 'checked' : ''}>
      <span>启用这个等级</span></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const name = $('#lvName').value.trim();
    if (!name) { toast('等级名称不能为空'); return; }
    const body = { name, enabled: $('#lvOn').checked ? 1 : 0 };
    const th = numVal('#lvTh');
    if (th !== null) {
      if (Number.isNaN(th) || th < 0) { toast('门槛金额不能为负'); return; }
      body.threshold = th;
    }
    const gen = numVal('#lvGen');
    if (gen !== null) {
      if (Number.isNaN(gen) || gen <= 0 || gen > 1) { toast('全场折扣必须在 (0, 1] 之间'); return; }
      body.generalDiscount = gen;
    }
    const sdOut = {};
    $$('[data-cat]').forEach((inp) => {
      const raw = inp.value.trim();
      if (raw === '') return;
      const v = Number(raw);
      sdOut[inp.dataset.cat] = v;
    });
    const bad = Object.keys(sdOut).find((k) => Number.isNaN(sdOut[k]) || sdOut[k] <= 0 || sdOut[k] > 1);
    if (bad) { toast(`${CATEGORY_LABEL[bad]}的折扣必须在 (0, 1] 之间`); return; }
    body.serviceDiscount = Object.keys(sdOut).length ? JSON.stringify(sdOut) : '';
    body.benefits = $('#lvBen').value.trim();
    try {
      await api(isNew ? '/admin/member-levels' : '/admin/member-levels/' + l.id,
        { method: isNew ? 'POST' : 'PUT', body });
      closeSheet();
      toast(isNew ? '等级已创建' : '已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

PAGES.levels = renderLevels;

/* ==========================================================================
   5. 门店规则
   ========================================================================== */

async function renderStoreCfg(view) {
  let cfg;
  try {
    cfg = await api('/admin/store-config');
  } catch (e) { return fail(view, e); }
  const on = cfg.noGuaranteeEnabled === true
    || cfg.noGuaranteeEnabled === 1 || cfg.noGuaranteeEnabled === 'true';

  view.innerHTML = `
    ${roleNote('这几项都在下单路径上被读：容量决定时段能否约上、取消时限决定顾客能否自助取消、可约天数决定日历放开多远。改之前请确认当前在营业。')}
    <div class="card">
      <div class="card-head"><span class="card-title">门店基本信息</span></div>
      <label class="field"><span>门店名称</span>
        <input id="cfName" class="ctl" value="${esc(cfg.name || '')}"></label>
      <label class="field"><span>营业时间</span>
        <input id="cfHours" class="ctl" value="${esc(cfg.businessHours || '')}" placeholder="例如 09:00-21:00">
        <span class="fhint">会员端日历按这段文字展示，服务时段本身由系统按时段长度生成。</span></label>
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-head"><span class="card-title">预约规则</span></div>
      <div class="form-2col">
        <label class="field"><span>每时段容量</span>
          <input id="cfCap" class="ctl" inputmode="numeric"
            value="${cfg.slotCapacity == null ? '' : cfg.slotCapacity}">
          <span class="fhint warn">填 0 会让当天所有时段直接约满。</span></label>
        <label class="field"><span>可提前预约天数</span>
          <input id="cfDays" class="ctl" inputmode="numeric"
            value="${cfg.bookableDays == null ? '' : cfg.bookableDays}"></label>
      </div>
      <label class="field"><span>自助取消时限（小时）</span>
        <input id="cfCancel" class="ctl" inputmode="numeric"
          value="${cfg.cancelHours == null ? '' : cfg.cancelHours}">
        <span class="fhint">距开始时间超过这个小时数，顾客可自行取消；小于则需联系门店。</span></label>
      <label class="fcheck"><input type="checkbox" id="cfGuar" ${on ? 'checked' : ''}>
        <span>开启「免密担保」预约</span></label>
      <label class="field"><span>未到店（爽约）处理规则</span>
        <textarea id="cfPolicy" class="ctl"
          placeholder="例如：超过预约时间 15 分钟未到视为爽约，次卡扣 1 次">${esc(cfg.noShowPolicy || '')}</textarea>
        <span class="fhint">这段文字会展示给顾客，请写得让人能看懂。</span></label>
      <div class="card-actions" style="justify-content:flex-start;margin-top:16px">
        <button class="btn btn-primary" id="cfSave" ${isManager() ? '' : 'disabled'}>保存门店规则</button>
        ${isManager() ? '' : '<span class="fhint">需要店长权限才能保存</span>'}
      </div>
    </div>`;

  $('#cfSave').onclick = async () => {
    if (!requireManager('门店规则')) return;
    const body = {
      name: $('#cfName').value.trim(),
      businessHours: $('#cfHours').value.trim(),
      noGuaranteeEnabled: $('#cfGuar').checked,
      noShowPolicy: $('#cfPolicy').value.trim()
    };
    const cap = numVal('#cfCap');
    const days = numVal('#cfDays');
    const cancel = numVal('#cfCancel');
    if (cap !== null) {
      if (Number.isNaN(cap) || cap <= 0) { toast('每时段容量必须大于 0，否则当天所有时段会直接约满'); return; }
      body.slotCapacity = cap;
    }
    if (days !== null) {
      if (Number.isNaN(days) || days <= 0) { toast('可提前预约天数必须大于 0'); return; }
      body.bookableDays = days;
    }
    if (cancel !== null) {
      if (Number.isNaN(cancel) || cancel < 0) { toast('取消时限不能为负'); return; }
      body.cancelHours = cancel;
    }
    try {
      await api('/admin/store-config', { method: 'PUT', body });
      toast('门店规则已保存');
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

PAGES.storecfg = renderStoreCfg;

/* ==========================================================================
   6. 会员管理与宠物健康看板
   ========================================================================== */

async function renderMembers(view) {
  const st = CFG.members;
  const b = CFG.board;
  let page;
  let board = [];
  try {
    page = await api('/admin/members' + qs({ keyword: st.keyword, page: st.page, size: st.size }));
    board = await api('/admin/health-board'
      + qs({ windowDays: b.windowDays, onlyOverdue: b.onlyOverdue })).catch(() => []);
  } catch (e) { return fail(view, e); }

  const records = page.records || [];
  const overdue = board.filter((r) => r.status === 'OVERDUE').length;

  view.innerHTML = `
    <div class="grid stats" style="margin-bottom:16px">
      <div class="stat">
        <div class="stat-label">已逾期 🩺</div>
        <div class="stat-value ${overdue > 0 ? 'warn' : ''}">${overdue}</div>
        <div class="stat-hint">疫苗或驱虫已过应做日期，建议优先电话联系</div>
      </div>
      <div class="stat">
        <div class="stat-label">临近到期（${b.windowDays} 天内）</div>
        <div class="stat-value">${board.length - overdue}</div>
        <div class="stat-hint">窗口期内的提醒对象</div>
      </div>
      <div class="stat">
        <div class="stat-label">会员总数（筛选结果）</div>
        <div class="stat-value">${Number(page.total || 0)}</div>
        <div class="stat-hint">按手机号 / 昵称 / 宠物名检索</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <span class="card-title">会员检索</span>
      </div>
      <div class="searchbar" style="margin-bottom:12px">
        <input id="mKw" class="ctl" placeholder="手机号 / 昵称 / 宠物名" value="${esc(st.keyword)}">
        <button id="mFind" class="btn btn-primary sm">搜索</button>
      </div>
      <span class="fhint">柜台最常听顾客说「我家团子」，所以宠物名也能搜 —— 命中后会返回该宠物所属的会员。</span>
      ${records.length ? `<div class="sku-cards" style="margin-top:12px">${records.map(memberRow).join('')}</div>`
        : emptyHtml('👤', '没有匹配的会员')}
      ${Number(page.total) > st.size ? pagerHtml(st, page.total) : ''}
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-head">
        <span class="card-title">宠物健康到期看板</span>
        <span class="card-actions">
          <button class="btn ${b.onlyOverdue ? 'btn-ghost' : 'btn-primary'} xs" id="bOnly">${b.onlyOverdue ? '看全部' : '只看逾期'}</button>
          <button class="btn btn-ghost xs" id="bWin">窗口 ${b.windowDays} 天</button>
          <button class="btn btn-ghost xs" id="bReload">刷新</button>
        </span>
      </div>
      <p class="card-desc">按到期日升序 —— 逾期最久的排最前，店员从上往下打电话就不会漏掉最该联系的那只。</p>
      ${board.length ? `<div class="log-cards">${board.map(dueRow).join('')}</div>`
        : emptyHtml('🎉', b.onlyOverdue ? '没有已逾期的宠物' : `${b.windowDays} 天内没有到期提醒`)}
      <span class="fhint" style="margin-top:10px">看板目前是只读清单 + 联系方式。
        微信推送需要为「疫苗到期提醒」单独申请订阅模板并让顾客授权，未接入之前不做「点了说已发送」的假按钮。</span>
    </div>`;

  bindPager(view, st, () => renderMembers(view));
  $('#mFind').onclick = () => { st.keyword = $('#mKw').value.trim(); st.page = 1; renderMembers(view); };
  $('#mKw').onkeydown = (e) => { if (e.key === 'Enter') $('#mFind').click(); };
  $('#bOnly').onclick = () => { b.onlyOverdue = !b.onlyOverdue; renderMembers(view); };
  $('#bWin').onclick = () => {
    const v = window.prompt('提前提醒天数（只看已逾期时填 0）', String(b.windowDays));
    if (v === null) return;
    const n = Number(v);
    if (Number.isNaN(n) || n < 0) { toast('请填写 0 或正整数'); return; }
    b.windowDays = n;
    renderMembers(view);
  };
  $('#bReload').onclick = () => renderMembers(view);

  $$('[data-mopen]', view).forEach((e) => {
    e.onclick = () => showMemberDetail(e.dataset.mopen, view);
  });
  $$('[data-board-member]', view).forEach((e) => {
    e.onclick = (ev) => { ev.stopPropagation(); showMemberDetail(e.dataset.boardMember, view); };
  });
}

function memberRow(m) {
  return `<div class="sku-card sku-card-click" data-mopen="${esc(m.id)}">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(m.nickname || '未设昵称')}</span>
    </div>
    <div class="chip-row">
      <span class="meta-chip">${esc(m.phone || '无手机号')}</span>
      <span class="meta-chip">${esc(m.levelName || (m.level == null ? '无等级' : 'L' + m.level))}</span>
      <span class="meta-chip price">累计 ${money(m.totalPaid)}</span>
    </div>
    <div class="row-sub" style="margin-top:2px">${dt(m.createdAt)} 注册</div>
  </div>`;
}

function dueRow(r) {
  const cls = r.status === 'OVERDUE' ? 'overdue' : 'soon';
  const days = Number(r.daysLeft);
  const label = days < 0 ? `逾期 ${-days} 天` : `还剩 ${days} 天`;
  return `<div class="log-card">
    <span class="due-days ${cls}">${label}</span>
    <div class="log-main">
      <div class="row-title">${esc(r.petName || '')}（${esc(r.petType || '—')}）· ${esc(r.logTypeLabel || r.logType)}
        ${tag(DUE_STATUS, r.status)}</div>
      <div class="row-sub">上次 ${esc(r.lastDate || '—')} · 下次应做 <b>${esc(r.nextDue || '—')}</b>
        · ${esc(r.memberName || '未设昵称')} ${esc(r.memberPhone || '')}</div>
    </div>
    <span class="log-actions">
      <button class="btn btn-ghost xs" data-board-member="${esc(r.memberId)}">看会员</button>
    </span>
  </div>`;
}

async function showMemberDetail(memberId, view) {
  openSheet(`<h3>会员详情</h3>
    <div class="empty"><span class="empty-icon">⏳</span><div class="empty-text">加载中…</div></div>`);
  let m;
  try {
    m = await api('/admin/members/' + memberId);
  } catch (e) {
    openSheet(`<h3>会员详情</h3>${emptyHtml('😿', e.message || '加载失败')}
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`);
    $('#sheetCancel').onclick = closeSheet;
    return;
  }
  const w = m.wallet || {};
  const cards = m.cards || [];
  const pets = m.pets || [];
  openSheet(`
    <h3>${esc(m.nickname || '未设昵称')}</h3>
    <p class="card-desc">${esc(m.phone || '无手机号')} · ${esc(m.levelName || '无等级')}
      · 累计消费 ${money(m.totalPaid)} · ${dt(m.createdAt)} 注册</p>

    <div class="grid stats" style="margin:14px 0">
      <div class="stat"><div class="stat-label">可用余额</div><div class="stat-value">${money(w.available)}</div></div>
      <div class="stat"><div class="stat-label">账户余额</div><div class="stat-value">${money(w.balance)}</div></div>
      <div class="stat"><div class="stat-label">冻结中</div><div class="stat-value">${money(w.frozenAmount)}</div></div>
      <div class="stat"><div class="stat-label">可退本金</div><div class="stat-value">${money(w.refundablePrincipal)}</div></div>
    </div>
    <span class="fhint">冻结额是未完成的预约预扣，可用余额 = 余额 − 冻结。可退本金是尚未消费的充值本金，
      退款审核时按它计算上限。</span>

    <hr class="hr">
    <div class="card-head"><span class="card-title">次卡</span></div>
    ${cards.length ? `<div class="sku-cards">${cards.map((c) => cardRow(c, view)).join('')}</div>`
      : '<p class="card-desc">该会员没有次卡</p>'}

    <hr class="hr">
    <div class="card-head"><span class="card-title">宠物与健康</span></div>
    ${pets.length ? `<div class="sku-cards">${pets.map(petRow).join('')}</div>` : '<p class="card-desc">该会员还没有登记宠物</p>'}

    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">关闭</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $$('[data-adj]', document).forEach((b) => {
    b.onclick = () => openAdjustTimesSheet(b.dataset.adj, b.dataset.cardno, Number(b.dataset.remain), view);
  });
  $$('[data-clog]', document).forEach((b) => {
    b.onclick = () => showCardLedgers(b.dataset.clog, b.dataset.cardno);
  });
}

function cardRow(c, view) {
  const remain = c.remainTimes == null ? 0 : c.remainTimes;
  const total = c.totalTimes == null ? 0 : c.totalTimes;
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(c.cardTypeName || '次卡')}</span>
      ${tag(CARD_USE, c.status)}
    </div>
    <div class="chip-row">
      <span class="meta-chip">卡号 ${esc(c.cardNo || '—')}</span>
      <span class="meta-chip ${remain <= 0 ? 'delta-down' : 'delta-up'}">剩余 ${remain} / ${total} 次</span>
      ${c.preDeductCount ? `<span class="meta-chip warn">预扣 ${c.preDeductCount} 次</span>` : ''}
    </div>
    <div class="row-sub" style="margin-top:2px">有效期至 ${esc(c.validUntil || '—')} · 购卡实付 ${money(c.paidAmount)}</div>
    <div class="sku-card-actions">
      ${isManager() ? `<button class="btn btn-ghost xs" data-adj="${esc(c.id)}"
        data-cardno="${esc(c.cardNo || '')}" data-remain="${remain}">调次</button>` : ''}
      <button class="btn btn-ghost xs" data-clog="${esc(c.id)}" data-cardno="${esc(c.cardNo || '')}">流水</button>
    </div>
  </div>`;
}

function petRow(p) {
  const due = (d, label, st) => d
    ? `<div class="kv"><span class="kv-k">${label}</span>
        <span class="kv-v">${esc(d)} ${tag(DUE_STATUS, st)}</span></div>`
    : `<div class="kv"><span class="kv-k">${label}</span><span class="kv-v">无记录</span></div>`;
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(p.name || '未命名')}（${esc(p.type || '—')}）</span>
    </div>
    <div class="chip-row">
      ${p.size ? `<span class="meta-chip">${esc(p.size)}</span>` : ''}
      ${p.hair ? `<span class="meta-chip">${esc(p.hair)}</span>` : ''}
      <span class="meta-chip">生日 ${esc(p.birthday || '—')}</span>
      <span class="meta-chip">体重 ${p.weight == null ? '—' : esc(p.weight) + ' kg'}</span>
    </div>
    <div style="margin-top:6px">
      ${due(p.nextVaccineDue, '下次疫苗', p.vaccineStatus)}
      ${due(p.nextWormDue, '下次驱虫', p.wormStatus)}
    </div>
  </div>`;
}

function openAdjustTimesSheet(cardId, cardNo, remain, view) {
  if (!requireManager('次卡调次')) return;
  openSheet(`
    <h3>调整次卡剩余次数</h3>
    <p class="card-desc">${esc(cardNo || '')} · 当前剩余 <b class="nums">${remain}</b> 次。</p>
    <label class="field"><span>调整次数（必填，可负）</span>
      <input id="adDelta" class="ctl" inputmode="numeric" placeholder="补次填正数，扣次填负数"></label>
    <label class="field"><span>调次原因（必填）</span>
      <textarea id="adRemark" class="ctl"
        placeholder="例如：8 月 3 日到店但系统未核销，补偿 1 次"></textarea></label>
    <span class="fhint">这会直接改变顾客已经付过钱的权益，因此必须写清原因；
      调整会同时写入卡流水与配置变更记录。扣减后剩余次数不允许为负。</span>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">确认调整</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const times = numVal('#adDelta');
    const remark = $('#adRemark').value.trim();
    if (!requireNum(times, '调整次数') || times === 0) { toast('调整次数不能为 0'); return; }
    if (!remark) { toast('请填写调次原因'); return; }
    try {
      const r = await api('/admin/cards/' + cardId + '/adjust-times',
        { method: 'POST', body: { times, remark } });
      closeSheet();
      toast(`剩余次数 ${r.remainBefore} → ${r.remainAfter}`);
      render();
    } catch (e) { toast(e.message || '调整失败'); }
  };
}

async function showCardLedgers(cardId, cardNo) {
  // 'logs'：流水弹层只比卡片略宽、列表内部滚动，见 app.css 的 .sheet.logs
  openSheet(`<h3>次卡流水</h3><p class="card-desc">${esc(cardNo || '')}</p>
    <div class="empty"><span class="empty-icon">⏳</span><div class="empty-text">加载中…</div></div>`, 'logs');
  try {
    const logs = await api('/admin/cards/' + cardId + '/ledgers?limit=60');
    const body = logs.length ? `<div class="log-cards">${logs.map((l) => `
      <div class="log-card">
        <span class="log-time">${dt(l.createdAt)}</span>
        <div class="log-main">
          <div class="row-title">${esc(CARD_BIZ[l.bizType] || l.bizType)}
            <span class="${l.times < 0 ? 'delta-down' : 'delta-up'}">${l.times > 0 ? '+' : ''}${l.times}</span></div>
          <div class="row-sub">操作人 ${esc(l.operator || '—')}
            ${l.remark ? '· ' + esc(l.remark) : ''}${l.bizNo ? ' · 单号 ' + esc(l.bizNo) : ''}</div>
        </div>
      </div>`).join('')}</div>` : emptyHtml('📭', '这张卡还没有流水');
    openSheet(`<h3>次卡流水</h3><p class="card-desc">${esc(cardNo || '')}</p>${body}
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`, 'logs');
    $('#sheetCancel').onclick = closeSheet;
  } catch (e) { toast(e.message || '加载失败'); }
}

PAGES.members = renderMembers;

/* ==========================================================================
   7. 配置变更记录
   ========================================================================== */

async function renderAudit(view) {
  const st = CFG.audit;
  let logs = [];
  try {
    logs = await api('/admin/config-audit' + qs({ targetType: st.targetType, limit: st.limit }));
  } catch (e) { return fail(view, e); }

  view.innerHTML = `
    ${roleNote('审计对所有人可见 —— 店员也需要能自证「这次改价不是我干的」。写入端才有权限要求。')}
    <div class="searchbar">
      <select id="auType" class="ctl" style="max-width:190px">
        <option value="">全部对象</option>
        ${opts(AUDIT_TARGET, st.targetType)}
      </select>
      <select id="auLimit" class="ctl" style="max-width:140px">
        ${opts({ 30: '最近 30 条', 60: '最近 60 条', 120: '最近 120 条', 200: '最近 200 条' }, st.limit)}
      </select>
      <button id="auFind" class="btn btn-primary sm">查询</button>
    </div>
    <div class="card">
      <div class="card-head">
        <span class="card-title">变更记录</span>
        <span class="tag muted">${logs.length} 条</span>
      </div>
      <p class="card-desc">改价、改档位、改折扣、盘点库存都会落这里。操作人由服务端从令牌解析，不可伪造。</p>
      ${logs.length ? `<div class="log-cards">${logs.map(auditRow).join('')}</div>`
        : emptyHtml('🕘', '没有匹配的变更记录')}
    </div>`;

  $('#auFind').onclick = () => {
    st.targetType = $('#auType').value;
    st.limit = Number($('#auLimit').value);
    renderAudit(view);
  };
  $('#auType').onchange = () => $('#auFind').click();
  $('#auLimit').onchange = () => $('#auFind').click();
}

function auditRow(l) {
  const target = AUDIT_TARGET[l.targetType] || l.targetType || '';
  const action = AUDIT_ACTION[l.action] || l.action || '';
  const isStatus = l.action === 'STATUS';
  // 状态变更记的是 ON_SALE / ENABLED 这类机器值，展示时要翻成人话，
  // 否则「状态 ON_SALE → OFF_SALE」在界面上等于没翻译
  const dim = (l.fieldName === '状态');
  let change = '';
  if (l.beforeValue != null || l.afterValue != null) {
    change = dim
      ? `${esc(labelOf(STATUS_LABEL, l.beforeValue))} → ${esc(labelOf(STATUS_LABEL, l.afterValue))}`
      : `${esc(l.beforeValue == null ? '—' : l.beforeValue)} → ${esc(l.afterValue == null ? '—' : l.afterValue)}`;
  }
  return `<div class="log-card">
    <span class="log-time">${dt(l.createdAt)}</span>
    <div class="log-main">
      <div class="row-title">
        <span class="tag ${isStatus ? 'warn' : 'muted'}">${esc(action)}</span>
        ${esc(target)}${l.targetId ? ' #' + esc(l.targetId) : ''}
        ${l.fieldName ? ' · ' + esc(l.fieldName) : ''}
      </div>
      <div class="row-sub">${change}${l.remark ? ' · ' + esc(l.remark) : ''}
        · 操作人 <b>${esc(l.operator || '—')}</b></div>
    </div>
  </div>`;
}

PAGES.audit = renderAudit;

/* ==========================================================================
   8. 后台账号
   ========================================================================== */

async function renderAccounts(view) {
  let accounts = [];
  try {
    accounts = await api('/admin/accounts');
  } catch (e) { return fail(view, e); }

  view.innerHTML = `
    ${roleNote('账号只能停用、不能删除 —— 流水里记的是显示名，删掉同名账号重建后，「三个月前那笔人工退款是谁做的」就分不清了。')}
    <div class="card">
      <div class="card-head">
        <span class="card-title">后台账号</span>
        ${isManager() ? '<button class="btn btn-ghost xs" id="acNew">＋ 新建账号</button>' : ''}
      </div>
      <p class="card-desc">店长能改价、审退款、管账号；店员能盘点库存、核销、查会员，但不能碰资金与配置。</p>
      ${accounts.length ? `<div class="sku-cards">${accounts.map(acctRow).join('')}</div>`
        : emptyHtml('🔑', '还没有后台账号')}
    </div>`;

  if ($('#acNew')) $('#acNew').onclick = () => openAccountSheet(null);
  $$('[data-acedit]', view).forEach((b) => {
    b.onclick = () => openAccountSheet(accounts.find((a) => String(a.id) === b.dataset.acedit));
  });
  $$('[data-acreset]', view).forEach((b) => {
    b.onclick = () => openResetPwdSheet(b.dataset.acreset, b.dataset.acname);
  });
}

function acctRow(a) {
  const isSelf = state.profile && String(state.profile.id) === String(a.id);
  const on = a.status === 'ENABLED';
  return `<div class="sku-card">
    <div class="sku-card-head">
      <span class="sku-card-name">${esc(a.displayName || a.username)}${isSelf ? '<span class="missing-hint" style="font-weight:400;margin-left:4px">（我）</span>' : ''}</span>
      ${tag(ACCT_STATUS, a.status)}
      ${a.mustChangePwd ? '<span class="tag warn">待改初始口令</span>' : ''}
    </div>
    <div class="chip-row">
      <span class="meta-chip">${esc(a.username)}</span>
      <span class="meta-chip">${esc(a.roleLabel || ROLE_LABEL[a.role] || a.role)}</span>
    </div>
    <div class="row-sub" style="margin-top:2px">${a.lastLoginAt ? '上次登录 ' + dt(a.lastLoginAt) : '从未登录'}</div>
    <div class="sku-card-actions">
      ${isManager() ? `<button class="btn btn-ghost xs" data-acedit="${esc(a.id)}">编辑</button>` : ''}
      ${isManager() ? `<button class="btn btn-ghost xs" data-acreset="${esc(a.id)}"
        data-acname="${esc(a.displayName || a.username)}">重置口令</button>` : ''}
    </div>
  </div>`;
}

function openAccountSheet(a) {
  if (!requireManager('账号管理')) return;
  const isNew = !a;
  const on = isNew || a.status === 'ENABLED';
  openSheet(`
    <h3>${isNew ? '新建后台账号' : '编辑账号'}</h3>
    ${isNew ? `
      <label class="field"><span>登录账号（必填）</span>
        <input id="acUser" class="ctl" placeholder="英文或数字，例如 wang01"></label>` : `
      <label class="field"><span>登录账号</span>
        <input class="ctl" value="${esc(a.username)}" disabled>
        <span class="fhint">账号一旦创建就不能改名 —— 它是留痕的锚点。</span></label>`}
    <label class="field"><span>显示名（必填）</span>
      <input id="acName" class="ctl" value="${isNew ? '' : esc(a.displayName || '')}"
        placeholder="会出现在退款、改价的留痕里"></label>
    <label class="field"><span>角色</span>
      <select id="acRole" class="ctl">${opts(ROLE_LABEL, isNew ? 'STAFF' : a.role)}</select></label>
    ${isNew ? `
      <label class="field"><span>初始口令（必填）</span>
        <input id="acPwd" class="ctl" type="password" placeholder="至少 8 位，含字母与数字/符号"></label>
      <span class="fhint">对方首次登录时会被强制改成自己的口令，所以初始口令不需要长期保密。</span>` : `
      <label class="fcheck"><input type="checkbox" id="acOn" ${on ? 'checked' : ''}>
        <span>启用这个账号</span></label>
      <span class="fhint warn">不能停用自己，也不能停用最后一个店长 —— 否则没人能再进后台。</span>`}
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">${isNew ? '创建' : '保存'}</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const displayName = $('#acName').value.trim();
    if (!displayName) { toast('显示名不能为空'); return; }
    try {
      if (isNew) {
        const username = $('#acUser').value.trim();
        const initialPassword = $('#acPwd').value;
        if (!username) { toast('登录账号不能为空'); return; }
        if (!initialPassword || initialPassword.length < 8) { toast('初始口令至少 8 位'); return; }
        await api('/admin/accounts', {
          method: 'POST',
          body: { username, displayName, role: $('#acRole').value, initialPassword }
        });
        closeSheet();
        toast('账号已创建，请把初始口令当面告知对方');
      } else {
        await api('/admin/accounts/' + a.id, {
          method: 'PUT',
          body: { displayName, role: $('#acRole').value, enabled: $('#acOn').checked }
        });
        closeSheet();
        toast('已保存');
      }
      render();
    } catch (e) { toast(e.message || '保存失败'); }
  };
}

function openResetPwdSheet(id, name) {
  if (!requireManager('重置口令')) return;
  openSheet(`
    <h3>重置口令 · ${esc(name || '')}</h3>
    <p class="card-desc">重置后该账号下次登录必须先自己改一次 ——
      避免「重置出来的口令被长期当共享口令用」。</p>
    <label class="field"><span>新的初始口令（必填）</span>
      <input id="rpPwd" class="ctl" type="password" placeholder="至少 8 位，含字母与数字/符号"></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">确认重置</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const newPassword = $('#rpPwd').value;
    if (!newPassword || newPassword.length < 8) { toast('新口令至少 8 位'); return; }
    try {
      await api('/admin/accounts/' + id + '/reset-password', { method: 'POST', body: { newPassword } });
      closeSheet();
      toast('已重置，请把新口令当面告知对方');
      render();
    } catch (e) { toast(e.message || '重置失败'); }
  };
}

PAGES.accounts = renderAccounts;
