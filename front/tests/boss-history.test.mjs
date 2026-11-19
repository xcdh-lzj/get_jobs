import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import ts from 'typescript'

const root = new URL('../', import.meta.url)
const require = createRequire(new URL('../package.json', import.meta.url))
const cache = new Map()
function load(sourcePath) {
  if (cache.has(sourcePath)) return cache.get(sourcePath).exports
  const source = readFileSync(new URL(sourcePath, root), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText
  const compiledModule = { exports: {} }; cache.set(sourcePath, compiledModule)
  const resolve = id => {
    if (id === 'react-dom') return { createPortal: children => children }
    if (id.startsWith('@/')) return load(id.slice(2) + (id.includes('/ui/') ? '.tsx' : '.ts'))
    return require(id)
  }
  new Function('require', 'module', 'exports', code)(resolve, compiledModule, compiledModule.exports)
  return compiledModule.exports
}
const { bossHistoryUrl, bossHistorySearchText, bossProcessingDateRange } = load('lib/boss-history.ts')
const { formatBossDescription } = load('lib/boss-description.ts')

test('flattened job descriptions use real headings and list items while preserving the text for copying', () => {
  const jd = '一、岗位介绍参与软件研发。⼆、 岗位职责1. 负责 Java 21 和 Spring Boot 3.2 服务；2. 维护 CI/CD 与数据库。三、任职要求1）熟悉前端；2）具备测试经验。四、加分项- 有 Agent 工具链经验。- 熟悉数据平台。'
  const intro = '关于我们：示例公司成立于 2020 年，专注于软件服务。'
  const { jobSections, companySections, jobText, companyText } = formatBossDescription(jd + '\n' + intro)
  assert.deepEqual(jobSections.map(section => section.heading), ['一、岗位介绍', '⼆、 岗位职责', '三、任职要求', '四、加分项'])
  assert.deepEqual(jobSections[1].blocks[0].items, [
    { text: '负责 Java 21 和 Spring Boot 3.2 服务；', number: 1 },
    { text: '维护 CI/CD 与数据库。', number: 2 },
  ])
  assert.equal(jobSections[2].blocks[0].items.length, 2)
  assert.equal(jobSections[3].blocks[0].ordered, false)
  assert.equal(jobSections[3].blocks[0].items.length, 2)
  assert.equal(companySections[0].heading, '关于我们')
  assert.equal(jobText, jd)
  assert.equal(companyText, intro)
})

test('company introductions are separated when headed, named on their own line, or already stored', () => {
  const jd = '岗位职责：维护岗位职责说明和研发平台。\n在 100-499 人团队协作。'
  const intro = '示例研发公司成立于2020年，致力于企业软件服务。'
  const parsed = formatBossDescription(jd + '\n' + intro, intro, '示例研发公司')
  assert.equal(parsed.jobSections.length, 1)
  assert.equal(parsed.companySections.length, 1)
  assert.equal(parsed.jobText, jd)
  assert.equal(parsed.companyText, intro)

  const leading = formatBossDescription('公司简介：' + intro + '\n\n' + jd, null)
  assert.equal(leading.jobText, jd)
  assert.equal(leading.companySections.length, 1)

  const stored = formatBossDescription(null, intro, '示例研发公司')
  assert.deepEqual(stored.jobSections, [])
  assert.equal(stored.companyText, intro)
  assert.deepEqual(formatBossDescription(jd, null, null).companySections, [])
})

test('processing date presets use inclusive dates on the browser calendar', () => {
  const date = new Date(2026, 10, 19, 0, 5)
  assert.deepEqual(bossProcessingDateRange('today', date), ['2026-11-19', '2026-11-19'])
  assert.deepEqual(bossProcessingDateRange('yesterday', date), ['2026-11-18', '2026-11-18'])
  assert.deepEqual(bossProcessingDateRange('previousSevenDays', date), ['2026-11-12', '2026-11-18'])
  assert.deepEqual(bossProcessingDateRange('previousMonth', date), ['2026-10-19', '2026-11-18'])
  assert.deepEqual(bossProcessingDateRange('thisMonth', date), ['2026-11-01', '2026-11-19'])
})

test('processing date presets cross years and clamp previous-month dates at month end', () => {
  assert.deepEqual(bossProcessingDateRange('previousSevenDays', new Date(2027, 0, 2)), ['2026-12-26', '2027-01-01'])
  assert.deepEqual(bossProcessingDateRange('previousMonth', new Date(2027, 0, 1)), ['2026-12-01', '2026-12-31'])
  assert.deepEqual(bossProcessingDateRange('previousMonth', new Date(2026, 2, 31)), ['2026-02-28', '2026-03-30'])
  assert.deepEqual(bossProcessingDateRange('previousMonth', new Date(2028, 2, 31)), ['2028-02-29', '2028-03-30'])
})

test('history URL copying accepts only Boss job details and preserves original parameters', () => {
  for (const url of [
    'https://www.zhipin.com/job_detail/example_123.html',
    'https://www.zhipin.com/job_detail/example_123.html?securityId=fixture-token&lid=fixture-source',
  ]) assert.equal(bossHistoryUrl(url), url)
})

test('invalid, executable, disguised, login and verification URLs cannot be copied as a job detail', () => {
  for (const url of [undefined, '', 'javascript:alert(1)', 'http://www.zhipin.com/job_detail/example_123.html',
    '//www.zhipin.com/job_detail/example_123.html', 'https://www.zhipin.com.evil.test/job_detail/example_123.html',
    'https://user:pass@www.zhipin.com/job_detail/example_123.html', 'https://www.zhipin.com:8080/job_detail/example_123.html',
    'https://www.zhipin.com/web/passport/zp/verify.html', 'https://www.zhipin.com/web/user/',
    'https://www.zhipin.com/job_detail/../web/passport/zp/verify.html', 'https://www.zhipin.com/job_detail/example_123.html\n',
  ]) assert.equal(bossHistoryUrl(url), null)
})

test('manual search text is based on the stored company and role, even without a historical URL', () => {
  assert.equal(bossHistorySearchText({ companyName: ' 示例公司 ', jobName: ' Agent 开发 ' }), '示例公司 Agent 开发')
  assert.equal(bossHistorySearchText({ jobName: 'Agent 开发' }), 'Agent 开发')
  assert.equal(bossHistorySearchText({}), '')
})

test('record details render saved content with no external navigation or embedded network resources', () => {
  const React = require('react')
  const { renderToStaticMarkup } = require('react-dom/server')
  const Component = load('app/boss/analysis/BossJobRecordDialog.tsx').default
  const previous = globalThis.document
  globalThis.document = { body: {} }
  try {
    const markup = renderToStaticMarkup(React.createElement(Component, {
      job: { id: 1, jobName: 'Agent 开发', companyName: '示例公司',
        jobUrl: 'https://www.zhipin.com/job_detail/example_123.html',
        jobDescription: '<img src="https://invalid.example/visit"><script>location.reload()</script>负责开发 Agent。' },
      onClose() {},
    }))
    assert.match(markup, /本地岗位记录/)
    assert.match(markup, /负责开发 Agent/)
    assert.match(markup, /&lt;img/)
    assert.doesNotMatch(markup, /<(?:a|iframe|img|script|video|audio|object|embed|link)\b/i)
    assert.doesNotMatch(markup, /<[^>]*\s(?:href|src|srcdoc)=/i)
    assert.match(markup, /复制公司与岗位/)
    assert.match(markup, /复制原链接/)
  } finally {
    if (previous === undefined) delete globalThis.document
    else globalThis.document = previous
  }
})

test('record details render the role and company in separate sections', () => {
  const React = require('react')
  const { renderToStaticMarkup } = require('react-dom/server')
  const Component = load('app/boss/analysis/BossJobRecordDialog.tsx').default
  const previous = globalThis.document
  globalThis.document = { body: {} }
  try {
    const markup = renderToStaticMarkup(React.createElement(Component, {
      job: { id: 1, jobName: '模拟开发岗位', jobDescription: '岗位职责：1. 负责接口研发；2. 负责测试。\n公司简介：这是一家示例公司。' },
      onClose() {},
    }))
    const job = markup.match(/<section aria-labelledby="boss-record-jd">(.*?)<\/section>/s)[1]
    const company = markup.match(/<section aria-labelledby="boss-record-company">(.*?)<\/section>/s)[1]
    assert.match(job, /<h4[^>]+>岗位职责<\/h4>/)
    assert.match(job, /<ol[^>]+>.*?<li value="1"[^>]*>负责接口研发；<\/li>/s)
    assert.doesNotMatch(job, /这是一家示例公司/)
    assert.match(company, /这是一家示例公司/)
    assert.match(company, /复制公司简介/)
  } finally {
    if (previous === undefined) delete globalThis.document
    else globalThis.document = previous
  }
})


test('restored job links retain the saved address and open in an isolated new tab', () => {
  const React = require('react')
  const { renderToStaticMarkup } = require('react-dom/server')
  const Component = load('app/boss/analysis/BossJobLink.tsx').default
  const markup = renderToStaticMarkup(React.createElement(Component, {
    url: 'https://www.zhipin.com/job_detail/example_123.html?securityId=fixture-token&lid=fixture-source', title: 'Agent 开发',
  }))
  assert.match(markup, /href="https:\/\/www\.zhipin\.com\/job_detail\/example_123\.html\?securityId=fixture-token&amp;lid=fixture-source"/)
  assert.match(markup, /target="_blank"/)
  assert.match(markup, /rel="noopener noreferrer"/)
  assert.match(markup, /岗位链接/)
  const invalid = renderToStaticMarkup(React.createElement(Component, { url: 'javascript:alert(1)' }))
  assert.doesNotMatch(invalid, /<a\b/)
})
