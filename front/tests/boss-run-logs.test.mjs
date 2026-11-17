import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import ts from 'typescript'

const root = new URL('../', import.meta.url)
const require = createRequire(new URL('../package.json', import.meta.url))
const cache = new Map()
function load(path) {
  if (cache.has(path)) return cache.get(path).exports
  const source = readFileSync(new URL(path, root), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText
  const compiledModule = { exports: {} }; cache.set(path, compiledModule)
  const resolve = id => id.startsWith('@/') ? load(id.slice(2) + (id.includes('/ui/') ? '.tsx' : '.ts')) : require(id)
  new Function('require', 'module', 'exports', code)(resolve, compiledModule, compiledModule.exports)
  return compiledModule.exports
}
const { buildLogRows, visibleLogRows, exportRunLogs, countNewLogs } = load('lib/boss-run-logs.ts')
const log = (id, message, type = 'info', timestamp = 1_700_000_000_000 + id * 1000) => ({ id, type, message, timestamp })

test('关键视图保留结果与未知事件，过程可在全部日志中查看', () => {
  const logs = [log(1, '正在搜索岗位：示例城市 · 示例方向'), log(2, '正在读取岗位详情（第 1 个）'),
    log(3, '已过滤：示例岗位 · 薪资区间与设置无交集'), log(4, '当前岗位未投递'),
    log(5, '正在打开沟通页面：示例岗位'), log(6, '当前岗位投递流程完成'), log(7, '新的后端事件类型')]
  const original = structuredClone(logs)
  const rows = buildLogRows(logs)
  assert.deepEqual(visibleLogRows(rows, 'key', '').map(row => row.id), [1, 3, 6, 7])
  assert.equal(visibleLogRows(rows, 'all', '').length, 7)
  assert.equal(visibleLogRows(buildLogRows([log(1, '当前岗位未投递')]), 'key', '').length, 1)
  assert.deepEqual(logs, original)
})

test('异常和提醒不会被过程降噪隐藏，过滤结果也不冒充成功投递', () => {
  const rows = buildLogRows([log(1, '正在读取岗位详情', 'error'), log(2, '正在投递：示例岗位', 'warning'),
    log(3, '触发Boss安全校验，请在浏览器中手动完成验证'), log(4, '今天已沟通较多，还剩30次沟通机会'),
    log(5, '仅审核通过（未投递）：示例岗位'), log(6, '已过滤：示例岗位')])
  assert.deepEqual(visibleLogRows(rows, 'attention', '').map(row => row.id), [1, 2, 3, 4])
  assert.equal(visibleLogRows(rows, 'key', '').length, 6)
  assert.equal(rows[4].kind, 'review')
  assert.equal(rows[5].kind, 'filtered')
})

test('只折叠短时间连续同类型原始事件，不跨岗位过程、不跨长时间或异常级别合并', () => {
  const rows = buildLogRows([log(1, '用户取消投递'), log(2, '用户取消投递'),
    log(3, '正在读取岗位详情（第 2 个）'), log(4, '用户取消投递'),
    log(5, '用户取消投递', 'warning'), log(6, '用户取消投递', 'warning', 1_700_000_030_000)])
  assert.equal(rows.length, 5)
  assert.equal(rows[0].count, 2)
  assert.equal(rows[0].lastTimestamp, log(2, '').timestamp)
  assert.equal(rows[2].count, 1)
})

test('搜索与视图同时生效，复制仍包含未经省略的全部原始事件', () => {
  const logs = [log(1, '正在投递：AGENT 示例岗位'), log(2, '已过滤：Agent 示例岗位 · 原因'),
    log(3, '用户取消投递'), log(4, '用户取消投递')]
  const rows = buildLogRows(logs)
  assert.deepEqual(visibleLogRows(rows, 'key', ' agent ').map(row => row.id), [2])
  assert.equal(visibleLogRows(rows, 'all', 'agent').length, 2)
  const exported = exportRunLogs(logs)
  assert.equal(exported.split('\n').length, 4)
  assert.equal(exported.match(/用户取消投递/g).length, 2)
  assert.match(exported, /正在投递：AGENT 示例岗位/)
})

test('200条滚动窗口长度不变时仍能正确识别暂停后新到的日志', () => {
  const logs = Array.from({ length: 200 }, (_, index) => log(index + 4, '示例事件'))
  assert.equal(countNewLogs(logs, 200), 3)
  assert.equal(countNewLogs(logs, 203), 0)
})

test('默认界面提供关键日志、搜索与跟随控制，后端文本作为纯文本显示', () => {
  const React = require('react')
  const { renderToStaticMarkup } = require('react-dom/server')
  const Panel = load('app/boss/RunLogPanel.tsx').default
  const markup = renderToStaticMarkup(React.createElement(Panel, {
    logs: [log(1, '<img src="https://invalid.example/visit">合成日志'), log(2, '正在读取岗位详情（第 1 个）')],
  }))
  assert.match(markup, /关键日志/)
  assert.match(markup, /提醒与异常/)
  assert.match(markup, /搜索运行日志/)
  assert.match(markup, /暂停日志自动跟随/)
  assert.match(markup, /复制全部原始日志/)
  assert.match(markup, /&lt;img/)
  assert.doesNotMatch(markup, /<img\b/)
  assert.doesNotMatch(markup, /正在读取岗位详情（第 1 个）/)
})
