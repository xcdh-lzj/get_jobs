import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../lib/boss-progress.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { mergeBossStatus, isBossTaskStatus, batchProgressPercent } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const status = (revision, instanceId = 'server-1') => ({
  platform: 'boss', isRunning: true, isLoggedIn: true,
  progress: { instanceId, revision, state: 'running', logs: [] },
})

test('late polling response cannot overwrite newer live progress', () => {
  const live = status(20)
  assert.equal(mergeBossStatus(live, status(15)), live)
  const next = status(21)
  assert.equal(mergeBossStatus(live, next), next)
})

test('server restart and initial page load accept fresh snapshots', () => {
  const restarted = status(0, 'server-2')
  assert.equal(mergeBossStatus(status(50), restarted), restarted)
  assert.equal(mergeBossStatus(null, restarted), restarted)
})

test('invalid or old backend responses do not masquerade as task snapshots', () => {
  assert.equal(isBossTaskStatus({ isRunning: false }), false)
  assert.equal(isBossTaskStatus(null), false)
  assert.equal(isBossTaskStatus(status(3)), true)
})

test('search progress resets independently of cumulative counts and unknown totals', () => {
  assert.equal(batchProgressPercent({ current: 4, total: 10 }), 40)
  assert.equal(batchProgressPercent({ current: 0, total: 0 }), null)
  assert.equal(batchProgressPercent({ current: 0, total: 20 }), 0)
  assert.equal(batchProgressPercent({ current: 30, total: 20 }), 100)
})

test('switching accounts accepts the new account and stale queue snapshots cannot revert its state', () => {
  const first = { ...status(50), accountId: 1 }
  const second = { ...status(1), accountId: 2 }
  assert.equal(mergeBossStatus(first, second), second)
  const latest = { ...second, queue: { instanceId: 'queue-1', revision: 20 } }
  const stale = { ...second, queue: { instanceId: 'queue-1', revision: 15 } }
  assert.equal(mergeBossStatus(latest, stale), latest)
})

test('daily quota is a valid terminal state and keeps recorded delivery counts', () => {
  const limited = status(5)
  limited.isRunning = false
  limited.progress.state = 'quota_reached'
  limited.progress.deliveredCount = 1
  assert.equal(isBossTaskStatus(limited), true)
  assert.equal(mergeBossStatus(status(4), limited), limited)
  assert.equal(limited.progress.deliveredCount, 1)
  assert.equal(isBossTaskStatus({ ...limited, progress: { ...limited.progress, state: 'unknown' } }), false)
})

test('review budget is a terminal state and preserves completed delivery counts', () => {
  const limited = status(8)
  limited.isRunning = false
  limited.progress.state = 'budget_reached'
  limited.progress.deliveredCount = 2
  assert.equal(isBossTaskStatus(limited), true)
  assert.equal(mergeBossStatus(status(7), limited), limited)
  assert.equal(limited.progress.deliveredCount, 2)
})
