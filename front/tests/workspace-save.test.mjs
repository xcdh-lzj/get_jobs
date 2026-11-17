import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
const source = await readFile(new URL('../lib/workspace-save.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const { saveWorkspace, writeWorkspaceJson } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

function editor(label, save, rest = {}) {
  return { id: label, label, dirty: true, ready: true, prepare: () => ({ save }), reset() {}, ...rest }
}

test('validates all drafts before writing any section, including a hidden tab', async () => {
  let writes = 0
  const entries = [editor('基础筛选', async () => writes++), editor('精准审核', async () => writes++, { validationError: '预算不合法' })]
  await assert.rejects(saveWorkspace(entries), /精准审核：预算不合法/)
  assert.equal(writes, 0)
  entries[1].validationError = ''
  entries[1].ready = false
  await assert.rejects(saveWorkspace(entries), /尚未读取完成/)
  assert.equal(writes, 0)
})

test('captures payloads before saving and skips sections with no changes', async () => {
  let target = '原始草稿', captured = '', cleanWrites = 0
  const result = await saveWorkspace([
    editor('基础筛选', async () => { target = '写入期间改变' }),
    editor('精准审核', async () => {}, { prepare: () => { const snapshot = target; return { save: async () => { captured = snapshot } } } }),
    editor('个人资料', async () => cleanWrites++, { dirty: false }),
  ])
  assert.equal(captured, '原始草稿')
  assert.equal(cleanWrites, 0)
  assert.deepEqual(result, { saved: ['基础筛选', '精准审核'], failed: [] })
})

test('reports partial failure and retries only unsaved sections', async () => {
  const sent = []
  const first = editor('基础筛选', async () => { sent.push('base'); first.dirty = false })
  const second = editor('精准审核', async () => { throw new Error('账号被锁定') })
  assert.deepEqual(await saveWorkspace([first, second]), { saved: ['基础筛选'], failed: [{ label: '精准审核', message: '账号被锁定' }] })
  second.prepare = () => ({ save: async () => { sent.push('review'); second.dirty = false } })
  assert.deepEqual(await saveWorkspace([first, second]), { saved: ['精准审核'], failed: [] })
  assert.deepEqual(sent, ['base', 'review'])
})

test('a 200 response carrying success false is not reported as saved', async () => {
  const original = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify({ success: false, message: '保存被拒绝' }), { status: 200 })
  try { await assert.rejects(writeWorkspaceJson('http://localhost/fixture', {}), /保存被拒绝/) }
  finally { globalThis.fetch = original }
})
