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
const { bossHistoryUrl, bossHistorySearchText } = load('lib/boss-history.ts')

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
