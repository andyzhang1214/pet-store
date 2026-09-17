#!/usr/bin/env node
/**
 * 后台 manage 前端「悬空引用」扫描（TypeScript 语法树 + 作用域链版）。
 *
 * 抓的是这一类 bug：渲染函数引用了从未定义的符号。已踩过两次 ——
 *   ① config.js 的 auditRow 引用了不存在的 STATUS_LABEL → 整页永久停在「加载中…」；
 *   ② renderWalletCfg 引用了 services，而 services 只是 renderServices 里的局部 let
 *      → 点「新建卡型」必抛 ReferenceError，弹层永远打不开。
 *      ⚠️ 这一条旧版本扫描器<b>报不出来</b>：它把所有声明合并成一张全局表，
 *      「在 A 函数里引用 B 函数的局部变量」会被判为"已声明"。本版本改为按作用域链解析。
 *
 * 为什么不用正则/自写词法器：必须同时正确识别字符串、注释、模板串 ${} 嵌套、
 *   以及正则字面量（如 s.replace(/'/g, …) 里的引号会把字符串边界整体带偏），
 *   漏一处就大面积误报（本文件的前身正是这么翻车的）。交给 TypeScript 解析器，
 *   用 AST 判断"这个标识符是引用、还是属性名/声明名/局部绑定"。
 *
 * 解析规则（近似 JS 语义，够用且保守）：
 *   - 顶层 function / class / let / const / var 属于跨文件共享的全局作用域（这些脚本直接挂在 window 上）；
 *   - 函数体、块、catch 各自成作用域；var 与函数声明提升到最近的函数作用域；
 *   - 引用从最内层作用域逐级向外查，查不到再查全局、最后查浏览器内置白名单。
 *
 * 用法：
 *   node scripts/check-manage-dangling.mjs [manage 目录]
 *   默认检查 src/main/resources/static/manage
 * 依赖 typescript：依次在 TS_PATH、脚本上级各级的 node_modules、
 *   以及同仓库的 petshop-miniapp/node_modules 里查找。
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const HERE = dirname(fileURLToPath(import.meta.url))

function loadTs() {
  const tried = []
  const candidates = []
  if (process.env.TS_PATH) candidates.push(process.env.TS_PATH)
  let dir = HERE
  for (let i = 0; i < 8; i++) {
    candidates.push(join(dir, 'node_modules', 'typescript'))
    candidates.push(join(dir, 'petshop-miniapp', 'node_modules', 'typescript'))
    const parent = dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  for (const c of candidates) {
    tried.push(c)
    if (existsSync(c)) return require(c)
  }
  throw new Error(`找不到 typescript 包，试过：\n  ${tried.join('\n  ')}\n可用 TS_PATH=<typescript 包目录> 指定`)
}

const ts = loadTs()
const DIR = process.argv[2] || resolve(HERE, '..', 'src/main/resources/static/manage')
if (!existsSync(DIR)) throw new Error(`目录不存在：${DIR}`)

// ---------- 载入待检查源码 ----------
const sources = []
for (const f of readdirSync(DIR)) {
  const p = join(DIR, f)
  if (f.endsWith('.js')) sources.push({ name: f, text: readFileSync(p, 'utf8') })
  else if (f.endsWith('.html')) {
    const html = readFileSync(p, 'utf8')
    // 只取内联脚本；外链脚本由同目录 .js 覆盖
    for (const m of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)) {
      sources.push({ name: f + ' (inline)', text: m[1] })
    }
  }
}
if (!sources.length) throw new Error(`目录里没有 .js/.html：${DIR}`)

const parsed = sources.map((s) => ({
  ...s,
  sf: ts.createSourceFile(s.name, s.text, ts.ScriptTarget.ESNext, true, ts.ScriptKind.JS),
}))

// ---------- 1. 作用域树 ----------

/** node → 作用域；用来把"最深包含该节点的作用域"找出来 */
const scopeOfNode = new WeakMap()
/** 所有脚本共享的全局作用域：这些 .js 都是直接挂在 window 上的普通脚本 */
const globalScope = { parent: null, bindings: new Set(), node: null }

function newScope(node, parent) {
  const scope = { parent, bindings: new Set(), node }
  if (node) scopeOfNode.set(node, scope)
  return scope
}

function addBinding(scope, name) {
  if (name && ts.isIdentifier(name)) scope.bindings.add(name.text)
  else if (name && (ts.isObjectBindingPattern(name) || ts.isArrayBindingPattern(name))) {
    for (const el of name.elements) if (ts.isBindingElement(el)) addBinding(scope, el.name)
  }
}

/** 变量声明挂到哪个作用域：var / 函数声明提升到最近函数作用域，let/const 留在当前块 */
function isFunctionLike(node) {
  return ts.isFunctionDeclaration(node) || ts.isFunctionExpression(node)
    || ts.isArrowFunction(node) || ts.isMethodDeclaration(node)
    || ts.isGetAccessor(node) || ts.isSetAccessor(node)
    || ts.isConstructorDeclaration?.(node) === true
}

function nearestFunctionScope(scope) {
  let cur = scope
  while (cur.parent && cur.kind === 'block') cur = cur.parent
  return cur
}

// 第一遍：递归建作用域 + 收集绑定
for (const { sf } of parsed) {
  const walk = (node, scope) => {
    let cur = scope
    if (isFunctionLike(node)) {
      // 函数名本身属于外层作用域（函数声明提升）
      if (ts.isFunctionDeclaration(node) && node.name) cur.bindings.add(node.name.text)
      cur = newScope(node, scope)
      cur.kind = 'function'
      if (node.parameters) for (const p of node.parameters) addBinding(cur, p.name)
      if (ts.isFunctionExpression(node) && node.name) cur.bindings.add(node.name.text)
    } else if (ts.isClassDeclaration(node) || ts.isClassExpression(node)) {
      if (node.name) cur.bindings.add(node.name.text)
      cur = newScope(node, scope)
      cur.kind = 'block'
    } else if (ts.isBlock(node) || ts.isModuleBlock?.(node)) {
      cur = newScope(node, scope)
      cur.kind = 'block'
    } else if (ts.isCatchClause(node)) {
      cur = newScope(node, scope)
      cur.kind = 'block'
      if (node.variableDeclaration) addBinding(cur, node.variableDeclaration.name)
    } else if (ts.isForStatement(node) || ts.isForInStatement(node) || ts.isForOfStatement(node)) {
      cur = newScope(node, scope)
      cur.kind = 'block'
    } else if (ts.isVariableDeclaration(node)) {
      const list = node.parent
      const blockScoped = list && (list.flags & ts.NodeFlags.BlockScoped) !== 0
      if (blockScoped) addBinding(cur, node.name)
      else addBinding(nearestFunctionScope(cur), node.name)
    } else if (ts.isImportDeclaration(node) && node.importClause) {
      const ic = node.importClause
      if (ic.name) cur.bindings.add(ic.name.text)
      if (ic.namedBindings) {
        if (ts.isNamespaceImport(ic.namedBindings)) cur.bindings.add(ic.namedBindings.name.text)
        else for (const sp of ic.namedBindings.elements) cur.bindings.add(sp.name.text)
      }
    }
    ts.forEachChild(node, (child) => walk(child, cur))
  }
  walk(sf, globalScope)
}

// 每个文件都从同一个全局作用域出发，避免"只在 B.js 里定义的函数在 A.js 里报悬空"
for (const { sf } of parsed) {
  scopeOfNode.set(sf, globalScope)
}

/** 找到最深包含该节点的作用域 */
function enclosingScope(node) {
  let cur = node
  while (cur) {
    const s = scopeOfNode.get(cur)
    if (s) return s
    cur = cur.parent
  }
  return globalScope
}

// ---------- 2. 平台内置全局 ----------
const BUILTIN = new Set([
  'JSON', 'Math', 'Date', 'Object', 'Array', 'Number', 'String', 'Boolean', 'Promise', 'Set', 'Map',
  'WeakMap', 'WeakSet', 'URL', 'URLSearchParams', 'FormData', 'RegExp', 'Error', 'TypeError', 'RangeError',
  'SyntaxError', 'NaN', 'Infinity', 'undefined', 'null', 'true', 'false', 'globalThis', 'window', 'document',
  'console', 'Intl', 'Blob', 'File', 'FileReader', 'Image', 'Event', 'CustomEvent', 'Node', 'Element',
  'HTMLElement', 'HTMLInputElement', 'HTMLCanvasElement', 'AbortController', 'TextEncoder', 'TextDecoder',
  'BigInt', 'Symbol', 'Proxy', 'Reflect', 'ArrayBuffer', 'Uint8Array', 'Uint32Array', 'Int32Array',
  'arguments', 'eval', 'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURIComponent',
  'decodeURIComponent', 'encodeURI', 'decodeURI', 'setTimeout', 'clearTimeout', 'setInterval',
  'clearInterval', 'requestAnimationFrame', 'cancelAnimationFrame', 'alert', 'confirm', 'prompt', 'fetch',
  'localStorage', 'sessionStorage', 'navigator', 'location', 'history', 'performance', 'structuredClone',
  'queueMicrotask', 'atob', 'btoa', 'crypto', 'matchMedia', 'getComputedStyle', 'IntersectionObserver',
  'ResizeObserver', 'MutationObserver', 'WebSocket', 'EventSource', 'Worker', 'Notification', 'Path2D',
  'OffscreenCanvas', 'BarcodeDetector', 'Audio', 'ImageData', 'DOMParser', 'XMLSerializer', 'requestIdleCallback',
])

/** 该 Identifier 是否处于"当值使用"的位置（而非属性名/声明名） */
function isValueReference(node) {
  const p = node.parent
  if (!p) return false
  if (ts.isPropertyAccessExpression(p) && p.name === node) return false
  if (ts.isPropertyAssignment(p) && p.name === node) return false
  if (ts.isMethodDeclaration(p) && p.name === node) return false
  if (ts.isPropertyDeclaration(p) && p.name === node) return false
  if (ts.isGetAccessor(p) && p.name === node) return false
  if (ts.isSetAccessor(p) && p.name === node) return false
  if (ts.isFunctionDeclaration(p) && p.name === node) return false
  if (ts.isFunctionExpression(p) && p.name === node) return false
  if (ts.isClassDeclaration(p) && p.name === node) return false
  if (ts.isClassExpression(p) && p.name === node) return false
  if (ts.isParameter(p) && p.name === node) return false
  if (ts.isBindingElement(p)) return false
  if (ts.isVariableDeclaration(p) && p.name === node) return false
  if (ts.isImportSpecifier(p) || ts.isExportSpecifier(p) || ts.isNamespaceImport(p)) return false
  if (ts.isQualifiedName(p)) return false
  if (ts.isImportClause(p) && p.name === node) return false
  // 简写属性 { foo } 里 foo 是引用，保留
  return true
}

/** 从最内层作用域逐级向外查 */
function isResolvable(name, fromScope) {
  let scope = fromScope
  while (scope) {
    if (scope.bindings.has(name)) return true
    scope = scope.parent
  }
  return BUILTIN.has(name)
}

const findings = []
for (const { name, sf } of parsed) {
  const visit = (node) => {
    if (ts.isIdentifier(node) && isValueReference(node)) {
      const text = node.text
      if (!isResolvable(text, enclosingScope(node))) {
        const lc = sf.getLineAndCharacterOfPosition(node.getStart(sf))
        findings.push({ name, line: lc.line + 1, col: lc.character + 1, text })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
}

const seen = new Set()
let problems = 0
for (const f of findings) {
  // 同一符号在多处引用只报首个位置；但不同文件各报一次（否则看不出波及面）
  if (seen.has(f.name + '|' + f.text)) continue
  seen.add(f.name + '|' + f.text)
  const src = parsed.find((p) => p.name === f.name).text
  const line = (src.split('\n')[f.line - 1] || '').trim()
  console.log(`✗ ${f.name}:${f.line}:${f.col}  引用了未声明的 "${f.text}"`)
  console.log(`    ${line.slice(0, 130)}`)
  problems++
}

console.log(`\n扫描 ${sources.length} 个脚本；疑似悬空引用 ${problems} 处（按作用域链解析）`)
process.exit(problems ? 1 : 0)
