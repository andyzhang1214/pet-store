/* ==========================================================================
   爪爪星球 · 门店后台
   纯静态单页，零构建、零依赖，由 Spring Boot 直接托管在 /manage/ 下。
   三套主题与小程序同源；响应式三档（手机底部导航 / 平板抽屉 / 桌面常驻侧栏）。
   配置域页面在 config.js（同样零依赖，加载时向 PAGES 注册自己）。
   ========================================================================== */

/*
 * 令牌放 sessionStorage 而不是 localStorage：
 * 这枚令牌能审退款、能改价，而 localStorage 的存活期是整个浏览器（关掉再开还在），
 * 且任何拿到该机器的人都能读到它。sessionStorage 的作用域是"当前标签页"——
 * 刷新不丢，关页即失效，把泄漏窗口从"永久"压到"这一班"。
 * 代价是关掉标签页要重新登录一次，这个代价在能改钱的系统上是值得的。
 */
const TOKEN_KEY = 'petshop.admin.token';
const PROFILE_KEY = 'petshop.admin.profile';
const THEME_KEY = 'petshop.manage.theme';
const POLL_MS = 30000;

const THEMES = [
  { id: 'standard', name: '标准', icon: '🐾' },
  { id: 'meow', name: '喵喵', icon: '🐱' },
  { id: 'woof', name: '汪汪', icon: '🐶' }
];

/**
 * 侧栏导航。
 *
 * managerOnly 的项对店员隐藏，而不是"点了再报 403"——
 * 后端仍然会拦（权限判断只在服务端），隐藏只是不让店员做无意义的尝试。
 * 两者都要有：只隐藏不禁，改个前端就能越权；只禁不隐藏，店员会以为系统坏了。
 */
const NAV = [
  { id: 'dashboard', name: '待审看板', icon: '📋', title: '待审看板' },
  { id: 'stats', name: '数据统计', icon: '📊', title: '数据统计' },
  { id: 'refunds', name: '退款审核', icon: '💰', title: '退款审核' },
  { id: 'orders', name: '订单管理', icon: '📦', title: '订单与自提核销' },
  { id: 'appointments', name: '预约管理', icon: '📅', title: '预约管理' },
  { id: 'verify', name: '核销台', icon: '✅', title: '核销台' },
  { id: 'settle', name: '到店结算', icon: '🧾', title: '到店结算' },
  { id: 'members', name: '会员管理', icon: '👤', title: '会员管理与健康看板' },
  { id: 'goods', name: '商品与库存', icon: '🏷️', title: '商品与库存', managerOnly: true },
  { id: 'services', name: '服务与价目', icon: '💅', title: '服务与价目', managerOnly: true },
  { id: 'walletcfg', name: '卡与充值', icon: '🎫', title: '次卡类型与充值档位', managerOnly: true },
  { id: 'levels', name: '会员等级', icon: '🏅', title: '会员等级', managerOnly: true },
  { id: 'storecfg', name: '门店规则', icon: '⚙️', title: '门店规则', managerOnly: true },
  { id: 'audit', name: '配置变更记录', icon: '🕘', title: '配置变更记录' },
  { id: 'accounts', name: '账号管理', icon: '🔑', title: '后台账号', managerOnly: true },
  { id: 'notify', name: '通知自检', icon: '🔔', title: '通知通道自检' }
];

// 手机底部导航只放最常用的 4 个，其余走「更多」抽屉
const TAB_IDS = ['dashboard', 'refunds', 'orders', 'verify'];

/**
 * 页面渲染函数注册表。app.js 注册自己的页面，config.js 注册配置域页面。
 * 用注册表而不是一个大 switch：新增页面时不必回头改 render()，
 * 也就不会出现"页面建好了但点进去是空白"的漏改。
 */
const PAGES = {};

const REFUND_STATUS = {
  APPLYING: { t: '待审核', c: 'warn' },
  REFUNDED: { t: '已退回余额', c: 'ok' },
  REJECTED: { t: '已驳回', c: 'danger' },
  CANCELLED: { t: '顾客已撤回', c: 'muted' }
};
const REFUND_BIZ = {
  CARD: '次卡退款', GOODS: '商品订单退款', MANUAL: '门店人工退款',
  APPOINT: '预约退款', SETTLE: '结算多收'
};
const ORDER_STATUS = {
  CREATED: { t: '待支付', c: 'warn' },
  PAID: { t: '待自提', c: 'warn' },
  VERIFIED: { t: '已核销', c: 'ok' },
  CANCELLED: { t: '已取消', c: 'muted' },
  REFUNDED: { t: '已退款', c: 'danger' }
};
const APPT_STATUS = {
  BOOKED: { t: '已预约', c: 'warn' },
  ARRIVED: { t: '已到店', c: 'ok' },
  SETTLING: { t: '结算中', c: 'warn' },
  SETTLED: { t: '已完成', c: 'ok' },
  CANCELLED: { t: '已取消', c: 'muted' },
  NOSHOW: { t: '未到店', c: 'danger' }
};
const PAY_TYPE = { BALANCE: '余额', CARD: '次卡', CASH: '现金', WXPAY: '微信' };

const state = {
  token: sessionStorage.getItem(TOKEN_KEY) || '',
  /** 当前登录身份：{ adminId, username, displayName, role, roleLabel, mustChangePassword } */
  profile: null,
  page: 'dashboard',
  theme: localStorage.getItem(THEME_KEY) || 'standard',
  pendingCount: 0,
  refundFilter: 'APPLYING',
  orderFilter: 'PAID',
  apptDate: todayStr(),
  settleApptId: '',
  memberKeyword: '',
  lastRefunds: [],
  timer: null
};

/** 店长才有的权限；共享令牌在 dev/test 下等价于店长 */
function isManager() {
  return !!state.profile && (state.profile.role === 'MANAGER' || state.profile.sharedToken);
}

/** 当前操作人显示名（仅用于界面提示；留痕由服务端从令牌解析，不从这里取） */
function me() {
  if (!state.profile) return '未登录';
  return state.profile.displayName || state.profile.username || '未登录';
}

/** 需要店长权限的项目前先自查，省掉一次必然 403 的往返 */
function requireManager(what) {
  if (isManager()) return true;
  toast(`「${what}」需要店长权限，请让店长操作`);
  return false;
}

/* ------------------------------ 小工具 ------------------------------ */

function $(sel, root) { return (root || document).querySelector(sel); }
function $$(sel, root) { return Array.from((root || document).querySelectorAll(sel)); }
function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
function todayStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function money(v) {
  const n = Number(v || 0);
  return '¥' + n.toFixed(2);
}
function dt(s) {
  if (!s) return '—';
  return String(s).replace('T', ' ').slice(0, 16);
}
function tag(map, key) {
  const m = map[key] || { t: key || '—', c: 'muted' };
  return `<span class="tag ${m.c}">${esc(m.t)}</span>`;
}
function toast(msg, ms) {
  const el = $('#toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.hidden = true; }, ms || 2400);
}
let scanCleanup = null;
/**
 * @param html 弹层内容
 * @param extraClass 可选修饰类，目前只有 'logs'（流水记录弹层：略宽一点、列表内部滚动）。
 *   #sheet 是复用节点，类名必须每次打开时重置，否则上一次的修饰类会残留到下一个弹层上。
 */
function openSheet(html, extraClass) {
  const overlay = $('#overlay');
  overlay.hidden = false;
  let sheet = $('#sheet');
  if (!sheet) {
    sheet = document.createElement('div');
    sheet.id = 'sheet';
    document.body.appendChild(sheet);
  }
  sheet.className = extraClass ? 'sheet ' + extraClass : 'sheet';
  sheet.innerHTML = html;
  sheet.hidden = false;
}
function closeSheet() {
  // 摄像头扫码弹层关闭时必须停掉媒体流，否则镜头指示灯常亮、持续占用摄像头
  if (typeof scanCleanup === 'function') { try { scanCleanup(); } catch (_) {} scanCleanup = null; }
  $('#overlay').hidden = true;
  const sheet = $('#sheet');
  if (sheet) sheet.hidden = true;
}

/* ------------------------------ 扫码与出码 ------------------------------ */

/**
 * 把核销码渲染成二维码展示给顾客扫。
 * 二维码在浏览器本地生成（vendor/qrcode.min.js，MIT 协议），码值不经过任何网络。
 */
function showQrSheet(title, code) {
  let src = '';
  try {
    const qr = window.qrcode(0, 'M');
    qr.addData(code);
    qr.make();
    src = qr.createDataURL(8, 12);
  } catch (e) { src = ''; }
  openSheet(`<h3>${esc(title)}</h3>
    ${src ? `<div class="qr-wrap"><img class="qr-img" src="${src}" alt="核销码二维码"></div>` : ''}
    <div class="qr-code-text">${esc(code)}</div>
    <p class="card-desc">店员在核销台用「摄像头扫码」或 USB 扫码枪读取，也可手动输入上方 8 位数字。</p>
    <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`);
  $('#sheetCancel').onclick = closeSheet;
}

/**
 * 摄像头扫码：识别成功后填入输码框并自动核销。
 * 两道能力探测都给出替代方案 —— 硬件/环境不满足时绝不留一个"看起来能点但永远转圈"的按钮。
 * USB 扫码枪不需要这个函数：它等价键盘输入，聚焦输码框直接扫即可。
 */
async function openScanner(inputId, label) {
  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    openSheet(`<h3>摄像头扫码不可用</h3><div class="empty"><span class="empty-icon">📷</span>
      <div class="empty-text">调用摄像头需要 HTTPS 或 localhost 环境。</div></div>
      <p class="card-desc">替代方案：USB 扫码枪（对准输码框直接扫，等价键盘输入）或手动输入 8 位码。</p>
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">知道了</button></div>`);
    $('#sheetCancel').onclick = closeSheet;
    return;
  }
  if (!window.BarcodeDetector) {
    openSheet(`<h3>摄像头扫码不可用</h3><div class="empty"><span class="empty-icon">🔍</span>
      <div class="empty-text">当前浏览器不支持摄像头识码（BarcodeDetector）。</div></div>
      <p class="card-desc">推荐 Chrome / Edge；USB 扫码枪与手动输码不受影响。</p>
      <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">知道了</button></div>`);
    $('#sheetCancel').onclick = closeSheet;
    return;
  }
  openSheet(`<h3>摄像头扫码 · ${esc(label)}</h3>
    <video id="scanVideo" class="scan-video" autoplay playsinline muted></video>
    <p class="card-desc" id="scanHint">对准顾客出示的核销二维码，识别后自动填入并核销。</p>
    <div class="sheet-actions"><button class="btn btn-ghost" id="sheetCancel">关闭</button></div>`);
  $('#sheetCancel').onclick = closeSheet;
  let stream = null, timer = null;
  scanCleanup = () => {
    if (timer) { clearInterval(timer); timer = null; }
    if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; }
  };
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    const video = $('#scanVideo');
    video.srcObject = stream;
    await video.play();
    const detector = new BarcodeDetector({ formats: ['qr_code'] });
    timer = setInterval(async () => {
      let codes;
      try { codes = await detector.detect(video); } catch (_) { return; }
      if (!codes || !codes.length) return;
      const raw = (codes[0].rawValue || '').trim();
      if (/^\d{8}$/.test(raw)) {
        closeSheet();
        $(inputId).value = raw;
        $(inputId === 'apptCode' ? 'apptVerifyBtn' : 'orderVerifyBtn').click();
      } else if (raw) {
        $('#scanHint').textContent = `识别到「${raw.slice(0, 24)}」，不是本店 8 位核销码，请对准核销二维码。`;
      }
    }, 350);
  } catch (e) {
    const reason = e && e.name === 'NotAllowedError' ? '摄像头权限被拒绝' : '无法打开摄像头';
    const hint = $('#scanHint');
    if (hint) hint.textContent = `${reason}。可改用 USB 扫码枪或手动输入 8 位码。`;
    else { scanCleanup(); closeSheet(); toast(reason); }
  }
}

/* ------------------------------ 请求 ------------------------------ */

/**
 * 统一请求。
 *
 * 凭证优先级：账号令牌（Authorization: Bearer）优先于开发用的共享令牌。
 * 这一点与服务端拦截器一致 —— 两个都带时应该以真实账号身份行事，
 * 否则留痕里会全是 shared-token(dev)，账号体系等于白建。
 *
 * 三类拒绝要分别对待，不能都当成"登录过期"：
 *  - 401：令牌无效 / 账号被停用 → 回登录页
 *  - 403 + 1024：初始口令没改 → 弹改密，不是权限问题
 *  - 403 + 1025：角色不够 → 只提示，不能把人踢下线（他仍是合法登录状态）
 */
async function api(path, options) {
  const opt = options || {};
  const headers = {};
  if (state.token) {
    // JWT 是三段点分结构；共享令牌是普通字符串。用形态区分，避免再存一个"令牌种类"字段
    if (state.token.split('.').length === 3) {
      headers['Authorization'] = 'Bearer ' + state.token;
    } else {
      headers['X-Admin-Token'] = state.token;
    }
  }
  if (opt.body) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method: opt.method || 'GET',
    headers,
    body: opt.body ? JSON.stringify(opt.body) : undefined
  });
  if (res.status === 429) {
    throw new Error('操作太频繁，请稍后再试');
  }
  const json = await res.json().catch(() => ({ code: -1, msg: '响应解析失败' }));

  if (res.status === 401) {
    doLogout('登录已失效，请重新登录');
    throw new Error('unauthorized');
  }
  if (res.status === 403 && json.code === 1024) {
    // 强制改密：把人送到改密表单，而不是登出（口令本身是对的）
    showPasswordForm('请先修改初始口令');
    throw new Error('must-change-password');
  }
  if (json.code !== 200) throw new Error(json.msg || '请求失败');
  return json.data;
}

/* ------------------------------ 登录 ------------------------------ */

/**
 * 未登录时也要能显示一个账号：服务端把商家账号交给店长手工建，
 * 首次登录用的就是引导创建的那个账号。这里不给默认口令提示 ——
 * 默认值是公开知识，写进界面等于把"没改初始口令的部署"直接标出来。
 */
function showLogin() {
  $('#login').hidden = false;
  $('#shell').hidden = true;
  $('#loginForm').hidden = false;
  $('#pwdForm').hidden = true;
  $('#loginHint').hidden = false;
}

function showShell() {
  $('#login').hidden = true;
  $('#shell').hidden = false;
}

/** 切到强制改密表单。oldPwd 传入时顺便回填当前口令，省得店长再输一遍。 */
function showPasswordForm(reason) {
  $('#login').hidden = false;
  $('#shell').hidden = true;
  $('#loginForm').hidden = true;
  $('#pwdForm').hidden = false;
  $('#loginHint').hidden = true;
  const err = $('#loginErr');
  err.textContent = reason || '';
  err.hidden = !reason;
}

function loginErr(msg) {
  const err = $('#loginErr');
  err.textContent = msg;
  err.hidden = false;
}

async function doLogin(e) {
  if (e) e.preventDefault();
  const username = $('#usernameInput').value.trim();
  const password = $('#passwordInput').value;
  if (!username || !password) {
    loginErr('请输入账号与口令');
    return;
  }
  const btn = $('#loginBtn');
  btn.disabled = true;
  try {
    const profile = await api('/admin/auth/login', {
      method: 'POST',
      body: { username, password }
    });
    state.token = profile.token;
    state.profile = profile;
    sessionStorage.setItem(TOKEN_KEY, profile.token);
    sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile));
    $('#passwordInput').value = '';
    $('#loginErr').hidden = true;
    paintIdentity();

    if (profile.mustChangePassword) {
      // 服务端会在除改密接口外的所有请求上拦下它，早一步引导比让人撞一墙 403 友好
      $('#oldPwdInput').value = password;
      showPasswordForm('这是初始口令，请先设置你自己的新口令');
      return;
    }
    showShell();
    boot();
  } catch (err) {
    loginErr(err.message === 'unauthorized' ? '账号或口令不正确' : (err.message || '登录失败'));
  } finally {
    btn.disabled = false;
  }
}

async function doChangePassword(e) {
  if (e) e.preventDefault();
  const oldPwd = $('#oldPwdInput').value;
  const newPwd = $('#newPwdInput').value;
  const newPwd2 = $('#newPwd2Input').value;
  if (!oldPwd || !newPwd) { loginErr('请填写当前口令与新口令'); return; }
  if (newPwd !== newPwd2) { loginErr('两次输入的新口令不一致'); return; }
  if (newPwd.length < 8) { loginErr('新口令至少 8 位，且需包含字母与数字或符号'); return; }

  const btn = $('#pwdBtn');
  btn.disabled = true;
  try {
    await api('/admin/auth/password', { method: 'POST', body: { oldPassword: oldPwd, newPassword: newPwd } });
    // 改密成功后服务端的 must_change_pwd 标记已清除，令牌仍有效，直接进后台
    state.profile.mustChangePassword = false;
    sessionStorage.setItem(PROFILE_KEY, JSON.stringify(state.profile));
    ['#oldPwdInput', '#newPwdInput', '#newPwd2Input'].forEach((s) => { $(s).value = ''; });
    paintIdentity();
    showShell();
    boot();
    toast('口令已更新');
  } catch (err) {
    loginErr(err.message || '修改失败');
  } finally {
    btn.disabled = false;
  }
}

function doLogout(reason) {
  state.token = '';
  state.profile = null;
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(PROFILE_KEY);
  if (state.timer) clearInterval(state.timer);
  state.timer = null;
  paintIdentity();
  showLogin();
  if (reason) loginErr(reason);
}

/** 把当前身份画到界面（侧栏 + 顶栏角色标签） */
function paintIdentity() {
  const who = $('#whoami');
  const chip = $('#roleChip');
  const pwdBtn = $('#pwdBtn2');
  if (!state.profile) {
    if (who) who.innerHTML = '';
    if (chip) chip.hidden = true;
    if (pwdBtn) pwdBtn.hidden = true;
    return;
  }
  if (who) {
    const role = state.profile.roleLabel || state.profile.role || '';
    who.innerHTML = `<b>${esc(me())}</b><span class="who-role">${esc(role)}</span>`;
  }
  if (chip) {
    chip.hidden = false;
    chip.textContent = state.profile.roleLabel || state.profile.role || '';
    chip.className = 'role-chip ' + (isManager() ? 'mgr' : 'staff');
  }
  // 共享令牌会话没有账号，改自己口令无从谈起
  if (pwdBtn) pwdBtn.hidden = !!state.profile.sharedToken;
}

/** 侧栏「修改我的口令」 */
function showMyPasswordSheet() {
  if (!state.profile || state.profile.sharedToken) {
    toast('当前是共享令牌会话，没有可修改的账号口令');
    return;
  }
  openSheet(`
    <h3>修改我的口令</h3>
    <p class="card-desc">改完之后其他已登录的标签页要重新登录（令牌里带的是账号 id，不随口令变更失效）。</p>
    <label class="field"><span>当前口令</span><input id="mpOld" type="password" autocomplete="current-password"></label>
    <label class="field"><span>新口令</span><input id="mpNew" type="password" placeholder="至少 8 位，含字母与数字/符号" autocomplete="new-password"></label>
    <label class="field"><span>确认新口令</span><input id="mpNew2" type="password" autocomplete="new-password"></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">确认修改</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    const oldPwd = $('#mpOld').value;
    const newPwd = $('#mpNew').value;
    if (newPwd !== $('#mpNew2').value) { toast('两次输入的新口令不一致'); return; }
    try {
      await api('/admin/auth/password', { method: 'POST', body: { oldPassword: oldPwd, newPassword: newPwd } });
      closeSheet();
      toast('口令已更新');
    } catch (e) { toast(e.message || '修改失败'); }
  };
}

/* ------------------------------ 主题与导航 ------------------------------ */

function applyTheme(id) {
  state.theme = id;
  document.documentElement.setAttribute('data-theme', id);
  localStorage.setItem(THEME_KEY, id);
  renderThemePicker();
}

function renderThemePicker() {
  $('#themePick').innerHTML = THEMES.map((t) =>
    `<button class="theme-dot ${t.id === state.theme ? 'on' : ''}" data-theme-id="${t.id}">${t.icon} ${t.name}</button>`
  ).join('');
  $$('#themePick .theme-dot').forEach((b) => {
    b.onclick = () => applyTheme(b.dataset.themeId);
  });
}

/** 当前身份能看到的导航项 */
function visibleNav() {
  return NAV.filter((n) => !n.managerOnly || isManager());
}

function renderNav() {
  const nav = visibleNav();
  $('#sideNav').innerHTML = nav.map((n) =>
    `<button class="nav-item ${n.id === state.page ? 'active' : ''}" data-nav="${n.id}">
       <span class="nav-icon">${n.icon}</span><span class="nav-text">${n.name}</span>
     </button>`
  ).join('');
  $$('#sideNav .nav-item').forEach((b) => {
    b.onclick = () => { go(b.dataset.nav); closeDrawer(); };
  });

  const tabs = TAB_IDS.filter((id) => nav.some((n) => n.id === id)).map((id) => {
    const n = NAV.find((x) => x.id === id);
    return `<button class="tab ${n.id === state.page ? 'on' : ''}" data-nav="${n.id}">
      <span class="tab-ico">${n.icon}</span><span>${n.name}</span>
      ${n.id === 'refunds' ? '<span class="badge" id="tabBadge" hidden>0</span>' : ''}
    </button>`;
  }).join('');
  $('#tabBar').innerHTML = tabs + `<button class="tab" data-more="1"><span class="tab-ico">☰</span><span>更多</span></button>`;
  $$('#tabBar .tab').forEach((b) => {
    b.onclick = () => {
      if (b.dataset.more) { toggleDrawer(); return; }
      go(b.dataset.nav);
    };
  });
}

function toggleDrawer() { $('#sidebar').classList.toggle('open'); }
function closeDrawer() { $('#sidebar').classList.remove('open'); }

function go(page) {
  const meta = NAV.find((n) => n.id === page);
  // 直接改地址栏 hash 或点了隐藏项时，在入口处挡一次（服务端仍会拦，这里只是不让界面进死胡同）
  if (meta && meta.managerOnly && !isManager()) {
    toast(`「${meta.name}」需要店长权限`);
    return;
  }
  // 未登录时任何页面跳转都无效，避免登录前点到底层 API
  if (!state.token) { showLogin(); return; }
  state.page = meta ? meta.id : 'dashboard';
  $('#pageTitle').textContent = meta ? meta.title : '';
  renderNav();
  render();
}

/* ------------------------------ 待审角标 ------------------------------ */

async function refreshBadge() {
  try {
    const n = await api('/admin/refunds/pending-count');
    state.pendingCount = Number(n || 0);
    const badge = $('#bellBadge');
    const tabBadge = $('#tabBadge');
    const show = state.pendingCount > 0;
    [badge, tabBadge].forEach((el) => {
      if (!el) return;
      el.hidden = !show;
      el.textContent = state.pendingCount > 99 ? '99+' : String(state.pendingCount);
    });
    $('#bellBtn').classList.toggle('pulse', show);
    if (show && state.page === 'refunds') render();
  } catch (e) { /* 角标失败不打扰用户 */ }
}

/* ------------------------------ 页面渲染 ------------------------------ */

function render() {
  const view = $('#view');
  view.innerHTML = '<div class="empty"><span class="empty-icon">⏳</span><div class="empty-text">加载中…</div></div>';
  const fn = PAGES[state.page];
  (fn || renderDashboard)(view);
}

function fail(view, e) {
  view.innerHTML = `<div class="empty">
    <span class="empty-icon">😿</span>
    <div class="empty-text">${esc(e.message || '加载失败')}</div>
    <button class="btn btn-ghost sm" style="margin-top:14px" onclick="location.reload()">重试</button>
  </div>`;
}

/* ---------- 待审看板 ---------- */

async function renderDashboard(view) {
  try {
    const [pending, orders, notify] = await Promise.all([
      api('/admin/refunds?status=APPLYING&page=1&size=5'),
      api('/admin/orders?status=PAID&page=1&size=1'),
      api('/admin/notify/status').catch(() => null)
    ]);
    state.pendingCount = pending.total || 0;
    const rows = (pending.records || []).map(refundRow).join('');
    const notifyCard = notify
      ? `<div class="card">
           <div class="card-head">
             <span class="card-title">通知通道</span>
             ${notify.configured ? '<span class="tag ok">微信推送已开启</span>' : '<span class="tag muted">仅后台角标</span>'}
           </div>
           <p class="card-desc">${esc(notify.hint || '')}</p>
           <div class="kv"><span class="kv-k">店长数量</span><span class="kv-v">${notify.managerCount || 0}</span></div>
           ${(notify.quota || []).map((q) =>
             `<div class="kv"><span class="kv-k">…${esc(q.openidTail)}</span><span class="kv-v">剩余 ${q.remain} 次</span></div>`
           ).join('') || '<div class="kv"><span class="kv-k">配额</span><span class="kv-v">未配置店长</span></div>'}
         </div>`
      : '';

    view.innerHTML = `
      <div class="grid stats" style="margin-bottom:16px">
        <div class="stat">
          <div class="stat-label">待审退款 💰</div>
          <div class="stat-value ${state.pendingCount > 0 ? 'warn' : ''}">${state.pendingCount}</div>
          <div class="stat-hint">需要店长裁决后才能退款到账</div>
        </div>
        <div class="stat">
          <div class="stat-label">待自提订单 📦</div>
          <div class="stat-value">${orders.total || 0}</div>
          <div class="stat-hint">已支付、等待顾客到店核销</div>
        </div>
      </div>
      <div class="grid cards">
        <div class="card">
          <div class="card-head"><span class="card-title">最新退款申请</span></div>
          ${rows ? `<div class="log-cards" style="margin-top:0">${rows}</div>`
            : '<div class="empty"><span class="empty-icon">✨</span><div class="empty-text">暂无待审退款，很清静</div></div>'}
        </div>
        ${notifyCard}
      </div>`;
    bindRefundActions(view);
  } catch (e) { fail(view, e); }
}

/* ---------- 退款审核 ---------- */

function refundRow(r) {
  // 记录卡片：业务名 + 状态在头部，金额/单号/时间收进标签行，动作贴卡片底部。
  // 无动作（已审完）时连 .log-actions 一起不渲染，免得留一条没内容的虚线。
  const acts = r.status === 'APPLYING'
    ? `<div class="log-actions">
         <button class="btn btn-primary xs" data-approve="${r.id}">通过并退款</button>
         <button class="btn btn-danger xs" data-reject="${r.id}">驳回</button>
       </div>`
    : '';
  return `<div class="log-card" data-id="${r.id}">
    <div class="log-main">
      <div class="row-title">
        ${esc(REFUND_BIZ[r.bizType] || r.bizType)}
        ${tag(REFUND_STATUS, r.status)}
      </div>
      <div class="chip-row">
        <span class="meta-chip price">${money(r.amount)}</span>
        <span class="meta-chip">单号 ${esc(r.refundNo)}</span>
        <span class="meta-chip">${esc(dt(r.createdAt))}</span>
      </div>
      <div class="row-sub">来源 ${esc(r.bizNo)}</div>
      ${r.reason ? `<div class="row-sub">理由：${esc(r.reason)}</div>` : ''}
      ${r.auditRemark ? `<div class="row-sub">审核意见：${esc(r.auditRemark)}（${esc(r.auditor || '')}）</div>` : ''}
    </div>
    ${acts}
  </div>`;
}

async function renderRefunds(view) {
  try {
    const q = state.refundFilter ? `&status=${state.refundFilter}` : '';
    const page = await api(`/admin/refunds?page=1&size=50${q}`);
    state.lastRefunds = page.records || [];
    const segs = [
      { k: 'APPLYING', t: `待审 ${state.pendingCount}` },
      { k: 'REFUNDED', t: '已退款' },
      { k: 'REJECTED', t: '已驳回' },
      { k: 'CANCELLED', t: '已撤回' },
      { k: '', t: '全部' }
    ];
    view.innerHTML = `
      <div class="seg">
        ${segs.map((s) => `<button class="seg-item ${state.refundFilter === s.k ? 'on' : ''}" data-rf="${s.k}">${esc(s.t)}</button>`).join('')}
        <button class="seg-item" id="manualBtn" style="margin-left:auto">＋ 人工退款</button>
      </div>
      <div class="card">
        ${state.lastRefunds.length
          ? `<div class="log-cards" style="margin-top:0">${state.lastRefunds.map(refundRow).join('')}</div>`
          : '<div class="empty"><span class="empty-icon">✨</span><div class="empty-text">该状态下没有退款单</div></div>'}
      </div>
      <p class="card-desc" style="margin-top:14px">
        退款口径：审核通过后金额<b>即时退回会员可用余额</b>，不支持提现到微信。
        通过前的金额由服务端按来源单据重算，不以申请时的数值为准。
      </p>`;
    $$('[data-rf]', view).forEach((b) => {
      b.onclick = () => { state.refundFilter = b.dataset.rf; render(); };
    });
    $('#manualBtn').onclick = showManualRefund;
    bindRefundActions(view);
  } catch (e) { fail(view, e); }
}

function bindRefundActions(view) {
  // id 保持字符串：雪花 id 是 19 位，Number() 会被截断，越界后请求打到不存在的单据。
  $$('[data-approve]', view).forEach((b) => {
    b.onclick = () => auditRefund(b.dataset.approve, true);
  });
  $$('[data-reject]', view).forEach((b) => {
    b.onclick = () => auditRefund(b.dataset.reject, false);
  });
}

async function auditRefund(id, approved) {
  const r = state.lastRefunds.find((x) => x.id === id);
  const amountText = r ? money(r.amount) : '';
  if (approved) {
    openSheet(`
      <h3>确认退款 ${esc(amountText)}</h3>
      <p class="card-desc">金额将<b>即时退回会员可用余额</b>，不可撤销。若来源单据在这期间已变化（如次卡被使用、订单已核销），系统会拒绝并提示刷新。</p>
      <label class="field"><span>审核备注（选填）</span>
        <input id="auditRemark" placeholder="例如：顾客已到店协商一致"></label>
      <div class="sheet-actions">
        <button class="btn btn-ghost" id="sheetCancel">再想想</button>
        <button class="btn btn-primary" id="sheetOk">确认退款</button>
      </div>`);
    $('#sheetCancel').onclick = closeSheet;
    $('#sheetOk').onclick = async () => {
      const remark = $('#auditRemark').value.trim();
      closeSheet();
      // 不再传 operator：审核人由服务端从令牌解析，客户端塞什么都不算数
      await doAudit(id, { approved: true, remark });
    };
  } else {
    openSheet(`
      <h3>驳回退款申请</h3>
      <p class="card-desc">驳回后不会产生任何资金动作，顾客可修改后重新提交。</p>
      <label class="field"><span>驳回理由（必填，将同步给顾客）</span>
        <textarea id="auditRemark" placeholder="例如：该次卡已使用过 2 次，不满足整卡退款条件"></textarea></label>
      <div class="sheet-actions">
        <button class="btn btn-ghost" id="sheetCancel">取消</button>
        <button class="btn btn-danger" id="sheetOk">确认驳回</button>
      </div>`);
    $('#sheetCancel').onclick = closeSheet;
    $('#sheetOk').onclick = async () => {
      const remark = $('#auditRemark').value.trim();
      if (!remark) { toast('驳回必须填写理由'); return; }
      closeSheet();
      await doAudit(id, { approved: false, remark });
    };
  }
}

async function doAudit(id, cmd) {
  try {
    await api(`/admin/refunds/${id}/audit`, { method: 'POST', body: cmd });
    toast(cmd.approved ? '已退款到余额' : '已驳回');
    await refreshBadge();
    render();
  } catch (e) { toast(e.message || '操作失败'); }
}

function showManualRefund() {
  if (!requireManager('店长人工退款')) return;
  // 幂等键在弹层打开时生成一次，本次提交的所有重试都复用它：
  // 手抖双击 / 超时重试会被后端 uk_refund_idem 折成同一笔，只入账一次（对应审查 P2-1）。
  // 重新打开弹层 = 新的 key = 新的一笔退款，符合直觉。
  const idemKey = `mr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  openSheet(`
    <h3>店长人工退款</h3>
    <p class="card-desc">用于客诉补偿等无法走单据的场景。<b>提交即刻生效</b>，会留一条人工退款记录，
      操作人记为「${esc(me())}」。</p>
    <label class="field"><span>会员 ID</span><input id="mMember" placeholder="会员 user id"></label>
    <label class="field"><span>退款金额</span><input id="mAmount" placeholder="例如 50.00" inputmode="decimal"></label>
    <label class="field"><span>退款原因（必填）</span><textarea id="mReason" placeholder="例如：店员操作失误多扣了费用"></textarea></label>
    <div class="sheet-actions">
      <button class="btn btn-ghost" id="sheetCancel">取消</button>
      <button class="btn btn-primary" id="sheetOk">确认退款</button>
    </div>`);
  $('#sheetCancel').onclick = closeSheet;
  $('#sheetOk').onclick = async () => {
    // 会员 id 同样是 19 位雪花号，必须以字符串原样回传，不能 Number()
    const memberId = $('#mMember').value.trim();
    const amount = Number($('#mAmount').value.trim());
    const reason = $('#mReason').value.trim();
    if (!/^\d+$/.test(memberId) || !amount || amount <= 0) { toast('请填写会员 ID 与大于 0 的金额'); return; }
    if (!reason) { toast('人工退款必须说明原因'); return; }
    // 提交期间禁用按钮：幂等键兜底的是"同一笔"，重复点击仍然会在界面上闪出两次成功提示
    const btn = $('#sheetOk');
    btn.disabled = true;
    try {
      await api('/admin/refunds/manual', {
        method: 'POST',
        body: { memberId, amount, reason, idempotencyKey: idemKey }
      });
      closeSheet();
      toast('人工退款已到账');
      render();
    } catch (e) { toast(e.message || '操作失败'); } finally { btn.disabled = false; }
  };
}

/* ---------- 订单管理 ---------- */

function orderRow(o) {
  const items = (o.items || []).map((i) => `${esc(i.name)} ×${i.qty}`).join('、');
  // 只有还带自提码的订单才需要「出码」入口
  const acts = o.verifyCode
    ? `<div class="log-actions">
         <button class="btn btn-ghost xs" data-qrcode="${esc(o.verifyCode)}" data-qrt="自提码">出码</button>
       </div>`
    : '';
  return `<div class="log-card">
    <div class="log-main">
      <div class="row-title">${esc(o.orderNo)} ${tag(ORDER_STATUS, o.status)}</div>
      <div class="chip-row">
        <span class="meta-chip price">${money(o.total)}</span>
        <span class="meta-chip">${esc(dt(o.createdAt))}</span>
        ${o.payType ? `<span class="meta-chip">${esc(PAY_TYPE[o.payType] || o.payType)}支付</span>` : ''}
        ${o.verifyCode ? `<span class="meta-chip warn">自提码 ${esc(o.verifyCode)}</span>` : ''}
      </div>
      <div class="row-sub">${items || '—'}</div>
      ${o.principalPaid !== undefined && Number(o.principalPaid) + Number(o.giftPaid) > 0
        ? `<div class="row-sub">本金 ${money(o.principalPaid)} · 赠送金 ${money(o.giftPaid)}</div>` : ''}
    </div>
    ${acts}
  </div>`;
}

async function renderOrders(view) {
  try {
    const q = state.orderFilter ? `&status=${state.orderFilter}` : '';
    const page = await api(`/admin/orders?page=1&size=50${q}`);
    const segs = [
      { k: 'PAID', t: '待自提' }, { k: 'VERIFIED', t: '已核销' },
      { k: 'CREATED', t: '待支付' }, { k: 'REFUNDED', t: '已退款' },
      { k: 'CANCELLED', t: '已取消' }, { k: '' , t: '全部' }
    ];
    view.innerHTML = `
      <div class="seg">
        ${segs.map((s) => `<button class="seg-item ${state.orderFilter === s.k ? 'on' : ''}" data-of="${s.k}">${esc(s.t)}</button>`).join('')}
      </div>
      <div class="card">
        ${(page.records || []).length
          ? `<div class="log-cards" style="margin-top:0">${page.records.map(orderRow).join('')}</div>`
          : '<div class="empty"><span class="empty-icon">📦</span><div class="empty-text">该状态下没有订单</div></div>'}
      </div>`;
    $$('[data-of]', view).forEach((b) => {
      b.onclick = () => { state.orderFilter = b.dataset.of; render(); };
    });
    $$('[data-qrcode]', view).forEach((b) => {
      b.onclick = () => showQrSheet(b.dataset.qrt, b.dataset.qrcode);
    });
  } catch (e) { fail(view, e); }
}

/* ---------- 预约管理 ---------- */

async function renderAppointments(view) {
  try {
    const list = await api(`/admin/appointments?date=${encodeURIComponent(state.apptDate)}`);
    const rows = (list || []).map((a) => {
      // 三种动作按状态互斥，拼完再判空：全不满足时不留一条空的虚线分隔
      const acts = [
        a.verifyCode ? `<button class="btn btn-ghost xs" data-qrcode="${esc(a.verifyCode)}" data-qrt="预约签到码">出码</button>` : '',
        a.status === 'ARRIVED' ? `<button class="btn btn-primary xs" data-settle="${a.id}">去结算</button>` : '',
        a.status === 'PENDING' ? `<button class="btn btn-danger xs" data-noshow="${a.id}">标记未到</button>` : ''
      ].filter(Boolean).join('');
      return `<div class="log-card">
        <div class="log-main">
          <div class="row-title">
            ${esc(a.startTime ? String(a.startTime).slice(0, 5) : '—')}
            ${esc(a.serviceName || '')}
            ${tag(APPT_STATUS, a.status)}
            ${a.vaccinePending ? '<span class="tag warn">疫苗待确认</span>' : ''}
          </div>
          <div class="chip-row">
            <span class="meta-chip price">${money(a.price)}</span>
            <span class="meta-chip">${esc(a.petName || '')}</span>
            <span class="meta-chip">${esc(a.apptNo || '')}</span>
          </div>
          <div class="row-sub">担保 ${esc(a.guaranteeType || '—')} ${money(a.guaranteeAmount)}${a.verifyCode ? ` · 核销码 ${esc(a.verifyCode)}` : ''}</div>
        </div>
        ${acts ? `<div class="log-actions">${acts}</div>` : ''}
      </div>`;
    }).join('');

    view.innerHTML = `
      <div class="card" style="margin-bottom:16px">
        <div class="card-head">
          <span class="card-title">选择日期</span>
          <input type="date" id="apptDate" value="${esc(state.apptDate)}"
                 style="padding:8px 12px;border:1px solid var(--line);border-radius:var(--line);border-radius:10px;background:var(--card-soft);color:var(--ink);outline:none">
          <button class="btn btn-ghost sm" id="apptToday">今天</button>
        </div>
        <p class="card-desc" style="margin:0">
          顾客需先核销签到（核销台），状态变为「已到店」后才能结算。
        </p>
      </div>
      <div class="card">
        ${rows ? `<div class="log-cards" style="margin-top:0">${rows}</div>`
          : '<div class="empty"><span class="empty-icon">📅</span><div class="empty-text">这一天还没有预约</div></div>'}
      </div>`;

    $('#apptDate').onchange = (e) => { state.apptDate = e.target.value; render(); };
    $('#apptToday').onclick = () => { state.apptDate = todayStr(); render(); };
    $$('[data-qrcode]', view).forEach((b) => {
      b.onclick = () => showQrSheet(b.dataset.qrt, b.dataset.qrcode);
    });
    $$('[data-noshow]', view).forEach((b) => {
      b.onclick = async () => {
        if (!window.confirm('标记未到店会按门店规则扣减担保，确认？')) return;
        try {
          await api(`/admin/appointments/${b.dataset.noshow}/noshow`, { method: 'POST' });
          toast('已标记未到店');
          render();
        } catch (e) { toast(e.message || '操作失败'); }
      };
    });
    $$('[data-settle]', view).forEach((b) => {
      b.onclick = () => { state.settleApptId = b.dataset.settle; go('settle'); };
    });
  } catch (e) { fail(view, e); }
}

/* ---------- 核销台 ---------- */

function renderVerify(view) {
  view.innerHTML = `
    <div class="grid cards">
      <div class="card">
        <div class="card-head"><span class="card-title">预约签到核销</span><span class="tag">服务核销</span></div>
        <p class="card-desc">输入顾客出示的 8 位核销码，核销后预约状态变为「已到店」，即可进入结算。</p>
        <input class="verify-input" id="apptCode" inputmode="numeric" maxlength="8" placeholder="········">
        <div class="verify-actions">
          <button class="btn btn-primary" id="apptVerifyBtn">确认签到</button>
          <button class="btn btn-ghost" id="apptScanBtn">📷 扫码</button>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><span class="card-title">商品自提核销</span><span class="tag">订单核销</span></div>
        <p class="card-desc">输入商品订单的自提码。核销后订单转为「已核销」，此单不再支持线上退款。</p>
        <input class="verify-input" id="orderCode" inputmode="numeric" maxlength="8" placeholder="········">
        <div class="verify-actions">
          <button class="btn btn-primary" id="orderVerifyBtn">确认自提</button>
          <button class="btn btn-ghost" id="orderScanBtn">📷 扫码</button>
        </div>
      </div>
    </div>
    <div id="verifyResult" style="margin-top:16px"></div>
    <p class="card-desc" style="margin-top:18px">
      两类码都是 8 位数字且分别存在于预约单与商品订单中，所以分成两个入口 ——
      共用一个输入框需要猜「先查哪张表」，猜错就会把商品码当预约码报错。
    </p>`;

  const run = async (kind) => {
    const isAppt = kind === 'appt';
    const input = $(isAppt ? '#apptCode' : '#orderCode');
    const code = input.value.trim();
    if (!/^\d{8}$/.test(code)) { toast('请输入 8 位核销码'); return; }
    const btn = $(isAppt ? '#apptVerifyBtn' : '#orderVerifyBtn');
    btn.disabled = true;
    try {
      const data = await api(isAppt ? '/admin/verify' : '/admin/orders/verify',
        { method: 'POST', body: { code } });
      input.value = '';
      $('#verifyResult').innerHTML = `<div class="card">
        <div class="card-head"><span class="card-title">✅ 核销成功</span></div>
        ${isAppt
          ? `<div class="kv"><span class="kv-k">预约号</span><span class="kv-v">${esc(data.apptNo)}</span></div>
             <div class="kv"><span class="kv-k">服务</span><span class="kv-v">${esc(data.serviceName || '')}</span></div>
             <div class="kv"><span class="kv-k">宠物</span><span class="kv-v">${esc(data.petName || '')}</span></div>
             <div class="kv"><span class="kv-k">状态</span><span class="kv-v">已到店，可去结算</span></div>`
          : `<div class="kv"><span class="kv-k">订单号</span><span class="kv-v">${esc(data.orderNo)}</span></div>
             <div class="kv"><span class="kv-k">金额</span><span class="kv-v">${money(data.total)}</span></div>`}
      </div>`;
      toast('核销成功');
    } catch (e) {
      $('#verifyResult').innerHTML = `<div class="card"><div class="empty">
        <span class="empty-icon">⚠️</span><div class="empty-text">${esc(e.message || '核销失败')}</div></div></div>`;
      toast(e.message || '核销失败');
    } finally { btn.disabled = false; }
  };

  $('#apptVerifyBtn').onclick = () => run('appt');
  $('#orderVerifyBtn').onclick = () => run('order');
  $('#apptScanBtn').onclick = () => openScanner('apptCode', '预约签到');
  $('#orderScanBtn').onclick = () => openScanner('orderCode', '商品自提');
  $$('.verify-input', view).forEach((i) => {
    i.onkeydown = (e) => { if (e.key === 'Enter') run(i.id === 'apptCode' ? 'appt' : 'order'); };
  });
}

/* ---------- 到店结算 ---------- */

function renderSettle(view) {
  view.innerHTML = `
    <div class="grid cards">
      <div class="card">
        <div class="card-head"><span class="card-title">结算单</span></div>
        <p class="card-desc">只有状态为「已到店」的预约可结算。金额由服务端按门店与服务规格重算，改价需填原因留痕。</p>
        <label class="field"><span>预约 ID</span>
          <input id="sApptId" value="${esc(state.settleApptId)}" placeholder="从预约管理点「去结算」可自动带入"></label>

        <div id="sItems"></div>
        <button class="btn btn-ghost sm" id="sAdd" style="margin-top:8px">＋ 添加服务项</button>

        <label class="field"><span>支付方式</span>
          <select id="sPayType" style="width:100%;padding:12px 14px;border:1px solid var(--line);border-radius:var(--radius-sm);background:var(--card-soft);color:var(--ink);font-size:14px;outline:none">
            <option value="BALANCE">余额抵扣</option>
            <option value="CASH">现金 / 其他</option>
          </select></label>
        ${isManager() ? `
        <label class="field"><span>改价金额（选填，填了就必填原因）</span>
          <input id="sManualPrice" inputmode="decimal" placeholder="例如 88.00"></label>
        <label class="field"><span>改价原因</span>
          <input id="sManualReason" placeholder="例如：店庆活动价"></label>` : `
        <p class="card-desc">人工改价会直接改变实收金额，需要店长权限；当前身份为店员，这里不提供改价入口。</p>`}
        <button class="btn btn-primary" id="sSubmit">确认结算</button>
      </div>
    </div>
    <div id="settleResult" style="margin-top:16px"></div>`;

  const addItem = (name, amount) => {
    const row = document.createElement('div');
    row.className = 'settle-item';
    row.style.cssText = 'display:flex;gap:8px;margin-bottom:8px';
    row.innerHTML = `
      <input class="i-name" placeholder="服务项目" value="${esc(name || '')}"
             style="flex:2;padding:10px;border:1px solid var(--line);border-radius:10px;background:var(--card-soft);color:var(--ink);outline:none">
      <input class="i-amount" placeholder="金额" inputmode="decimal" value="${esc(amount || '')}"
             style="flex:1;padding:10px;border:1px solid var(--line);border-radius:10px;background:var(--card-soft);color:var(--ink);outline:none">
      <button class="btn btn-danger sm" data-del>删</button>`;
    row.querySelector('[data-del]').onclick = () => row.remove();
    $('#sItems').appendChild(row);
  };
  addItem('', '');
  $('#sAdd').onclick = () => addItem('', '');

  $('#sSubmit').onclick = async () => {
    // 预约 id 是雪花号，同样按字符串原样回传
    const apptId = $('#sApptId').value.trim();
    if (!/^\d+$/.test(apptId)) { toast('请填写预约 ID'); return; }
    const items = $$('#sItems .settle-item').map((r) => ({
      name: r.querySelector('.i-name').value.trim(),
      amount: Number(r.querySelector('.i-amount').value.trim())
    })).filter((i) => i.name && i.amount > 0);
    if (!items.length) { toast('至少填一条有效明细'); return; }
    // 店员身份下改价入口整个不渲染，这里要判空（改价需店长，后端也会再拦一次）
    const manualPriceRaw = $('#sManualPrice') ? $('#sManualPrice').value.trim() : '';
    const manualReason = $('#sManualReason') ? $('#sManualReason').value.trim() : '';
    if (manualPriceRaw && !manualReason) { toast('改价必须填原因'); return; }
    const body = {
      items: items.map((i) => ({ name: i.name, category: 'WASH', amount: i.amount })),
      payType: $('#sPayType').value,
      manualPrice: manualPriceRaw ? Number(manualPriceRaw) : null,
      manualReason: manualReason || null
    };
    const btn = $('#sSubmit');
    btn.disabled = true;
    try {
      const r = await api(`/admin/settle/${apptId}/confirm`, { method: 'POST', body });
      $('#settleResult').innerHTML = `<div class="card">
        <div class="card-head"><span class="card-title">✅ 结算完成</span></div>
        <div class="kv"><span class="kv-k">结算单号</span><span class="kv-v">${esc(r.settleNo)}</span></div>
        <div class="kv"><span class="kv-k">应收合计</span><span class="kv-v">${money(r.total)}</span></div>
        <div class="kv"><span class="kv-k">支付方式</span><span class="kv-v">${esc(PAY_TYPE[r.payType] || r.payType)}</span></div>
        <div class="kv"><span class="kv-k">余额实扣</span><span class="kv-v">${money(r.balancePaid)}</span></div>
        ${Number(r.cashDue) > 0 ? `<div class="kv"><span class="kv-k">⚠️ 现金应收</span>
          <span class="kv-v" style="color:var(--danger,#c0392b);font-weight:600">${money(r.cashDue)}
          （余额/次卡之外需线下收取）</span></div>` : ''}
      </div>`;
      toast('结算完成');
      state.settleApptId = '';
    } catch (e) { toast(e.message || '结算失败'); } finally { btn.disabled = false; }
  };
}

/* ---------- 通知自检 ---------- */

async function renderNotify(view) {
  try {
    const s = await api('/admin/notify/status');
    view.innerHTML = `
      <div class="card" style="margin-bottom:16px">
        <div class="card-head">
          <span class="card-title">微信推送通道</span>
          ${s.configured ? '<span class="tag ok">已开启</span>' : '<span class="tag muted">未开启</span>'}
        </div>
        <p class="card-desc">${esc(s.hint || '')}</p>
        <div class="kv"><span class="kv-k">配置的店长数</span><span class="kv-v">${s.managerCount || 0}</span></div>
        ${(s.quota || []).map((q) =>
          `<div class="kv"><span class="kv-k">店长 …${esc(q.openidTail)}</span><span class="kv-v">剩余推送额度 ${q.remain} 次</span></div>`
        ).join('')}
      </div>

      <div class="card">
        <div class="card-head"><span class="card-title">怎么让它工作</span></div>
        <div class="kv"><span class="kv-k">1</span><span class="kv-v" style="text-align:left">在微信公众平台申请「退款到账提醒」类订阅消息模板，把模板 ID 填到配置项 <code>petshop.notify.refund-template-id</code></span></div>
        <div class="kv"><span class="kv-k">2</span><span class="kv-v" style="text-align:left">配置 <code>petshop.wx.appid</code> 与 <code>petshop.wx.secret</code></span></div>
        <div class="kv"><span class="kv-k">3</span><span class="kv-v" style="text-align:left">把店长的 openid 填进 <code>petshop.notify.manager-openids</code>（逗号分隔）</span></div>
        <div class="kv"><span class="kv-k">4</span><span class="kv-v" style="text-align:left">店长在小程序点「接收退款通知」授权一次，即获得一次推送额度</span></div>
        <div class="hr"></div>
        <p class="card-desc" style="margin:0">
          <b>为什么要有额度</b>：微信订阅消息是<b>一次性授权</b>，店长每点一次「允许通知」才换来一次推送机会，
          推成功即消耗。这与公众号模板消息能无限推完全不同 —— 额度用完只会安静降级为「只发后台角标」，不会报错，
          所以需要这个页面来确认通道是否真的可用。
        </p>
      </div>`;
  } catch (e) { fail(view, e); }
}

/* ---------- 数据统计 ---------- */

/** 后端返回的状态 key 与 APPT_STATUS 对齐；这里只补一个兜底展示名 */
function apptStatusName(k) {
  return APPT_STATUS[k] ? APPT_STATUS[k].t : k;
}
function apptStatusTag(k) {
  const m = APPT_STATUS[k] || { t: k, c: 'muted' };
  return `<span class="tag ${m.c}">${esc(m.t)}</span>`;
}
/** 金额去尾零：¥120.00 → ¥120，¥88.50 → ¥88.5（只用于图表与提示，不用于对账） */
function num(v) {
  return Number(v || 0).toFixed(2).replace(/\.?0+$/, '');
}

/**
 * 近 N 日营收趋势：纯 CSS 双色柱状图，零依赖、随主题变色。
 * 高度按「服务+商品」合计的最大值归一，柱子上的 title 提供精确数字。
 */
function trendChart(trend) {
  const sums = trend.map((d) => Number(d.service) + Number(d.goods));
  const max = Math.max(...sums, 0);
  const cols = trend.map((d) => {
    const sv = Number(d.service), gv = Number(d.goods);
    const pct = (v) => (max > 0 && v > 0 ? Math.max(3, Math.round(v / max * 100)) + '%' : '3px');
    return `<div class="trend-col">
      <div class="trend-bars" title="${esc(d.date)} 服务 ${money(sv)} · 商品 ${money(gv)}">
        <div class="trend-bar service" style="height:${pct(sv)}"></div>
        <div class="trend-bar goods" style="height:${pct(gv)}"></div>
      </div>
      <div class="trend-day">${esc(d.date)}</div>
    </div>`;
  }).join('');
  const anyData = sums.some((v) => v > 0);
  return `<div class="trend-chart">${cols}</div>
    <div class="legend">
      <span><i class="legend-dot" style="background:var(--brand)"></i>服务结算</span>
      <span><i class="legend-dot" style="background:var(--brand);opacity:.32"></i>商品销售</span>
      <span class="legend-right">${anyData ? `单日峰值 ¥${num(max)}` : '近 7 日暂无营收数据'}</span>
    </div>`;
}

async function renderStats(view) {
  try {
    const s = await api('/admin/stats/overview?days=7');
    const t = s.today;
    const apptRows = Object.keys(t.apptByStatus || {}).length
      ? Object.entries(t.apptByStatus).map(([k, v]) => `
          <div class="kv">
            <span class="kv-k">${apptStatusTag(k)}</span>
            <span class="kv-v nums">${v} 单</span>
          </div>`).join('')
      : '<div class="empty" style="padding:18px"><span class="empty-icon">📅</span><div class="empty-text">今天还没有预约</div></div>';

    view.innerHTML = `
      <div class="grid stats" style="margin-bottom:16px">
        <div class="stat">
          <div class="stat-label">今日营收 💰</div>
          <div class="stat-value">${money(t.totalRevenue)}</div>
          <div class="stat-hint">服务 ${money(t.serviceRevenue)} · 商品 ${money(t.goodsRevenue)}</div>
        </div>
        <div class="stat">
          <div class="stat-label">服务结算 💅</div>
          <div class="stat-value">${t.serviceCount}<span class="stat-unit">单</span></div>
          <div class="stat-hint">${t.cardSettleCount > 0 ? `其中次卡抵扣 ${t.cardSettleCount} 单` : '含余额 / 现金 / 次卡'}</div>
        </div>
        <div class="stat">
          <div class="stat-label">商品销售 🏷️</div>
          <div class="stat-value">${money(t.goodsRevenue)}</div>
          <div class="stat-hint">${t.goodsCount} 单已支付（待自提 / 已核销）</div>
        </div>
        <div class="stat">
          <div class="stat-label">今日充值 🧧</div>
          <div class="stat-value">${money(t.rechargePrincipal)}</div>
          <div class="stat-hint">${t.rechargeCount} 笔${Number(t.rechargeGift) > 0 ? ` · 含赠送 ${money(t.rechargeGift)}` : ''}</div>
        </div>
        <div class="stat">
          <div class="stat-label">今日预约 📅</div>
          <div class="stat-value">${t.apptTotal}<span class="stat-unit">单</span></div>
          <div class="stat-hint">按预约当天时段统计</div>
        </div>
        <div class="stat">
          <div class="stat-label">新增会员 🐾</div>
          <div class="stat-value">${t.newMembers}<span class="stat-unit">人</span></div>
          <div class="stat-hint">会员总数 ${s.members.total} 人</div>
        </div>
      </div>

      <div class="card" style="margin-bottom:16px">
        <div class="card-head">
          <span class="card-title">近 7 日营收趋势</span>
          <button class="btn btn-ghost sm" id="statsRefresh">刷新</button>
        </div>
        ${trendChart(s.trend)}
      </div>

      <div class="grid cards">
        <div class="card">
          <div class="card-head"><span class="card-title">今日预约分布</span><span class="tag">${t.apptTotal} 单</span></div>
          ${apptRows}
        </div>
        <div class="card">
          <div class="card-head"><span class="card-title">会员资产总览</span></div>
          <div class="kv"><span class="kv-k">会员总数</span><span class="kv-v nums">${s.members.total} 人</span></div>
          <div class="kv"><span class="kv-k">余额池总额</span><span class="kv-v nums">${money(s.members.balanceTotal)}</span></div>
          <div class="kv"><span class="kv-k">其中冻结（预约担保）</span><span class="kv-v nums">${money(s.members.frozenTotal)}</span></div>
          <p class="card-desc" style="margin:10px 0 0">
            余额池是门店对会员的<b>负债</b>，不是营收；营收只统计上面「服务结算 + 商品销售」。
          </p>
        </div>
      </div>

      <p class="card-desc" style="margin-top:16px">
        口径说明：商品营收含「待自提」与「已核销」订单（已支付即算售出）；充值是储值负债，不计入营收；
        次卡抵扣的结算单金额计入服务营收，笔数单列以便对账。
      </p>`;

    $('#statsRefresh').onclick = () => render();
  } catch (e) { fail(view, e); }
}

/* ------------------------------ 启动 ------------------------------ */

// app.js 自己负责的页面注册进注册表；配置域页面由 config.js 追加
PAGES.dashboard = renderDashboard;
PAGES.stats = renderStats;
PAGES.refunds = renderRefunds;
PAGES.orders = renderOrders;
PAGES.appointments = renderAppointments;
PAGES.verify = renderVerify;
PAGES.settle = renderSettle;
PAGES.notify = renderNotify;

function boot() {
  applyTheme(state.theme);
  renderNav();
  go('dashboard');
  refreshBadge();
  if (state.timer) clearInterval(state.timer);
  state.timer = setInterval(refreshBadge, POLL_MS);
}

async function init() {
  document.documentElement.setAttribute('data-theme', state.theme);
  $('#loginForm').onsubmit = doLogin;
  $('#pwdForm').onsubmit = doChangePassword;
  $('#logoutBtn').onclick = () => doLogout('');
  $('#pwdBtn2').onclick = showMyPasswordSheet;
  $('#menuBtn').onclick = toggleDrawer;
  $('#bellBtn').onclick = () => go('refunds');
  $('#overlay').onclick = closeSheet;

  if (!state.token) {
    showLogin();
    return;
  }
  // 有令牌时不能直接信它：账号可能已被停用、角色可能已被改，
  // 刷新一次 /admin/auth/me 拿到权威身份再决定界面形态。
  try {
    const profile = await api('/admin/auth/me');
    if (profile.mustChangePassword) {
      showPasswordForm('请先修改初始口令');
      return;
    }
    state.profile = profile;
    sessionStorage.setItem(PROFILE_KEY, JSON.stringify(profile));
    paintIdentity();
    showShell();
    boot();
  } catch (e) {
    // api() 里已经在 401 时调过 doLogout 了，这里只兜住"没有身份"的收尾
    if (!state.token) showLogin();
  }
}

init();
