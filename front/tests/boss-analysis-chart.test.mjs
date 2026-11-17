import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../lib/boss-analysis-chart.ts', import.meta.url), 'utf8')
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText
const { sameAnalysisChart } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)

const chart = () => ({ type: 'bar', labels: ['已投递', '已过滤'], data: [143, 132], colors: ['#10b981', '#ef4444'] })

test('轮询产生的新数组、相同统计响应和无关页面操作不应重绘图表', () => {
  assert.equal(sameAnalysisChart(chart(), chart()), true)
  assert.equal(sameAnalysisChart(chart(), JSON.parse(JSON.stringify(chart()))), true)
  assert.equal(sameAnalysisChart({ type: 'line', labels: [], data: [] }, { type: 'line', labels: [], data: [], color: '#3b82f6' }), true)
})

test('统计值、分类、顺序和长度发生变化时必须更新，避免缓存过期图表', () => {
  for (const change of [
    { data: [144, 132] }, { labels: ['未投递', '已过滤'] },
    { labels: ['已过滤', '已投递'], data: [132, 143] },
    { labels: ['已投递'], data: [143] }, { labels: [], data: [] },
  ]) assert.equal(sameAnalysisChart(chart(), { ...chart(), ...change }), false)
})

test('图表类型、标题和颜色设置变化时必须更新', () => {
  for (const change of [
    { type: 'pie' }, { title: '新统计' }, { color: '#ef4444' },
    { colors: ['#ef4444', '#10b981'] }, { colors: [] }, { colors: undefined },
  ]) assert.equal(sameAnalysisChart(chart(), { ...chart(), ...change }), false)
})
