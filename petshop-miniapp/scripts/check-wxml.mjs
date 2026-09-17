#!/usr/bin/env node
/**
 * 小程序 WXML 静态检查：所有「写了但静默不生效」的属性。
 *
 * 背景：WXML 里很多属性写错不会报编译错误，运行时只是"点了没反应"或"渲染不对"，
 *   排查成本极高。本项目已经踩过两次：
 *     1. catchtap=""  → 微信不为空值注册事件，弹层内的 tap 照样冒泡到遮罩，
 *        点输入框的瞬间弹层被关掉，表现是「添加宠物点不进输入框」。
 *     2. wx:key="index" 用在字符串数组上 → 取的是 item.index（恒为 undefined），
 *        官方规定只能是 item 的字段名，item 为字符串/数值时才用 *this。
 *
 * 检查项：
 *   A. bind / catch 空值绑定           （不会注册事件，catch 也挡不住冒泡）
 *   B. bind / catch 指向不存在的处理器 （在同名 .ts 里找不到定义）
 *   C. wx:for 缺 wx:key                （提示级：官方说列表固定时可不写）
 *   D. wx:key 使用数据绑定 {{ }}       （官方明确禁止）
 *   E. wx:key="index"                  （除非 item 真有 index 字段）
 *
 * 注意：本文件注释里**不要**写 `bind` 加 `星号斜杠` 的连写形式 ——
 *   其中的斜杠与星号会提前闭合块注释（写这一版时就踩了）。
 *
 * 用法：node scripts/check-wxml.mjs [miniprogram 目录]
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = process.argv[2] || 'miniprogram'
const EVENT_ATTR = /\b((?:bind|catch|capture-bind|capture-catch)[:a-zA-Z]*)\s*=\s*"([^"]*)"/g

/** 注释里也会出现 bindtap="..." 这类字样（本项目偏偏爱在注释里举反例），
 *  先把 <!-- --> 内容替换成等长空格，保住行号，再扫。 */
function stripWxmlComments(src) {
  return src.replace(/<!--[\s\S]*?-->/g, (c) => c.replace(/[^\n]/g, ' '))
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('.wxml')) out.push(p)
  }
  return out
}

/**
 * 迭代标签：从 '<' 到配对的 '>'，但**跳过引号内的 '>'**。
 * 属性值里的 `wx:if="{{a > b}}"` 会骗过朴素的 indexOf('>')，
 * 导致把整个标签切碎、wx:for 与 wx:key 被分到两段里。
 */
function* tags(src) {
  let i = 0
  while (i < src.length) {
    const lt = src.indexOf('<', i)
    if (lt < 0) break
    let j = lt + 1
    let quote = null
    while (j < src.length) {
      const c = src[j]
      if (quote) { if (c === quote) quote = null }
      else if (c === '"' || c === "'") quote = c
      else if (c === '>') break
      j++
    }
    yield { text: src.slice(lt, j + 1), index: lt }
    i = j + 1
  }
}

function lineAt(src, index) {
  return src.slice(0, index).split('\n').length
}

/** 从 wxml 属性值里取出所有可能的处理器名：既支持 "save"，也支持 "{{a ? '' : 'pickSlot'}}" */
function handlerNames(attrValue) {
  const raw = attrValue.trim()
  if (!raw) return []                       // 空字符串 = 没绑，静默无反应（catch 场景下还会失去挡冒泡作用）
  if (!raw.includes('{{')) return [raw]
  // 动态绑定：把表达式里的字符串字面量都当候选，'' 表示"该状态下不绑"
  const lits = [...raw.matchAll(/'([^']*)'|"([^"]*)"/g)].map((m) => m[1] ?? m[2])
  return lits.filter((s) => s !== '')
}

/**
 * 从 ts 里收集方法名：方法简写 foo() {} / foo: () => … / async foo() {}。
 *
 * 两个要点，都是为了不"吞掉"方法名：
 * 1. 行内匹配（用 [ \t]* 而不是 \s*）：`\s` 能跨行，`Page({\n closeForm() {` 会被
 *    整体当成一个签名而把 closeForm 吃进参数里，于是 closeForm 悄悄从集合里消失。
 * 2. 排除 Page / Component / App / Behavior 等容器调用以及语言关键字。
 */
const CONTAINERS = new Set(['Page', 'Component', 'App', 'Behavior', 'if', 'for', 'while', 'switch', 'catch', 'return', 'function'])
function definedMethods(ts) {
  const names = new Set()
  for (const m of ts.matchAll(/^[ \t]*(?:async[ \t]+)?([A-Za-z_$][\w$]*)[ \t]*\([^()\n]*\)[ \t]*\{/gm)) {
    if (!CONTAINERS.has(m[1])) names.add(m[1])
  }
  for (const m of ts.matchAll(/^[ \t]*([A-Za-z_$][\w$]*)[ \t]*:[ \t]*(?:async[ \t]*)?(?:function\b|\()/gm)) {
    if (!CONTAINERS.has(m[1])) names.add(m[1])
  }
  return names
}

let problems = 0
let advisories = 0
let checked = 0
let forCount = 0

for (const wxml of walk(ROOT)) {
  const base = wxml.replace(/\.wxml$/, '')
  const rel = relative(ROOT, wxml)
  let ts = ''
  try { ts = readFileSync(base + '.ts', 'utf8') } catch { /* 纯 js 页面没有 ts */ }
  const methods = definedMethods(ts)
  const src = stripWxmlComments(readFileSync(wxml, 'utf8'))
  const lines = src.split('\n')

  // ---- A / B：事件绑定 ----
  for (const m of src.matchAll(EVENT_ATTR)) {
    const [, attr, value] = m
    if (/^\s*$/.test(value)) {
      const line = lineAt(src, m.index)
      console.log(`✗ ${rel}:${line}  ${attr}="" 空绑定（不会注册事件，catch 也挡不住冒泡）`)
      console.log(`    ${lines[line - 1].trim().slice(0, 110)}`)
      problems++
      continue
    }
    for (const name of handlerNames(value)) {
      checked++
      if (!methods.has(name)) {
        const line = lineAt(src, m.index)
        console.log(`✗ ${rel}:${line}  ${attr}="${name}" 在 ${relative(ROOT, base)}.ts 里没有定义`)
        problems++
      }
    }
  }

  // ---- C / D / E：wx:for 与 wx:key ----
  for (const tag of tags(src)) {
    const forAttr = /\bwx:for\s*=\s*"([^"]*)"/.exec(tag.text)
    if (!forAttr) continue
    forCount++
    const line = lineAt(src, tag.index)
    const keyAttr = /\bwx:key\s*=\s*"([^"]*)"/.exec(tag.text)
    if (!keyAttr) {
      console.log(`· ${rel}:${line}  wx:for 未写 wx:key（列表固定/仅尾部增删时官方建议不写，可忽略）`)
      advisories++
      continue
    }
    const key = keyAttr[1].trim()
    if (key.includes('{{')) {
      console.log(`✗ ${rel}:${line}  wx:key="${key}" 不能使用数据绑定，应直接写字段名字符串`)
      problems++
    } else if (key === 'index') {
      console.log(`✗ ${rel}:${line}  wx:key="index" 取的是 item.index；字符串/数值数组应写 *this，对象数组应写唯一字段名`)
      problems++
    }
  }
}
console.log(`\n检查了 ${checked} 个事件绑定、${forCount} 个 wx:for；问题 ${problems} 处，提示 ${advisories} 处`)
process.exit(problems ? 1 : 0)
