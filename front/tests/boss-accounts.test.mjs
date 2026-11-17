import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = (await readFile(new URL('../lib/boss-accounts.ts', import.meta.url), 'utf8'))
  .replace("import { API_BASE } from './api'", "const API_BASE = 'http://127.0.0.1:9527'")
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { bossUrl, moveQueueAccount } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('all Boss requests identify the account while retaining existing filters', () => {
  const url = new URL(bossUrl('/api/boss/list?keyword=Java&accountId=1', 2))
  assert.equal(url.searchParams.get('accountId'), '2')
  assert.deepEqual(url.searchParams.getAll('accountId'), ['2'])
  assert.equal(url.searchParams.get('keyword'), 'Java')
  assert.equal(new URL(bossUrl('/api/boss/start', 3)).searchParams.get('accountId'), '3')
})

test('queue ordering keeps the input intact and does not lose or duplicate accounts', () => {
  const ids = [1, 2, 3]
  assert.deepEqual(moveQueueAccount(ids, 2, -1), [1, 3, 2])
  assert.deepEqual(moveQueueAccount(ids, 0, 1), [2, 1, 3])
  assert.deepEqual(ids, [1, 2, 3])
  assert.equal(moveQueueAccount(ids, 0, -1), ids)
  assert.equal(moveQueueAccount(ids, 3, 1), ids)
})
