import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../lib/boss-inbox.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { newInboxAlerts } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

const row = (accountId, unread, notificationId, state = 'watching') => ({ accountId, unread, notificationId, state })
const snapshot = (accounts, enabled = true, instanceId = 'a') => ({ accounts, enabled, instanceId })

test('提醒仅跟随有明确新增未读的账号，忽略重复事件、已读和暂时断开的页面', () => {
  const previous = snapshot([row(1, 4, 1), row(2, 2, 0), row(3, 3, 2), row(4, 0, 0), row(5, 5, 3)])
  const next = snapshot([row(1, 4, 1), row(2, 3, 4), row(3, 0, 0), row(4, 1, 5), row(5, 5, 6, 'stale')])
  assert.deepEqual(newInboxAlerts(previous, next).map(account => account.accountId), [2, 4])
  assert.deepEqual(newInboxAlerts(next, next), [])
})

test('打开工作台、启动监听和后端重启时只记录基线', () => {
  const current = snapshot([row(1, 5, 1)])
  assert.deepEqual(newInboxAlerts(null, current), [])
  assert.deepEqual(newInboxAlerts(snapshot([], false), current), [])
  assert.deepEqual(newInboxAlerts(snapshot([], true, 'previous-instance'), current), [])
})
