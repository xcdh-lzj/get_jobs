'use client'

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { bossUrl } from '@/lib/boss-accounts'
import { followUpStateLabels, followUpTime, type FollowUpRun, type FollowUpStatus } from '@/lib/boss-follow-up'
import type { BossProgressLog } from '@/lib/boss-progress'
import RunLogPanel from './RunLogPanel'

const pageSize = 16
const emptyLogs: BossProgressLog[] = []
const triggerNames = { scheduled: '定时', manual: '手动', legacy: '历史记录' }

function StateBadge({ state }: { state: string }) {
  const color = state === 'failed' ? 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300'
    : ['partial', 'interrupted', 'quota_reached', 'queued'].includes(state)
      ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
      : ['running', 'completed'].includes(state)
        ? 'bg-teal-100 text-teal-800 dark:bg-teal-950 dark:text-teal-300'
        : 'bg-muted text-muted-foreground'
  return <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>
    {followUpStateLabels[state] ?? state}
  </span>
}

export default function BossFollowUpLog({ accountId, status, loadError, visible }: {
  accountId: number; status: FollowUpStatus | null; loadError: boolean; visible: boolean
}) {
  const [older, setOlder] = useState<FollowUpRun[]>([])
  const [selected, setSelected] = useState<FollowUpRun | null>(null)
  const [loading, setLoading] = useState(false)
  const [exhausted, setExhausted] = useState(false)
  const [historyError, setHistoryError] = useState(false)
  const [snapshot, setSnapshot] = useState<{ runId: number; logs: BossProgressLog[] | null; error: boolean } | null>(null)
  const recent = status?.recentRuns ?? []
  const runs = [...recent, ...older.filter(run => !recent.some(item => item.id === run.id))]
  if (selected && !runs.some(run => run.id === selected.id)) runs.push(selected)
  const run = selected ? runs.find(run => run.id === selected.id)
    : runs.find(run => run.id === status?.activeRunId) ?? runs[0]
  const runId = run?.id
  const working = run?.state === 'running' || run?.state === 'queued'
  const progress = run && run.id === status?.activeRunId ? status.progress : null
  const current = snapshot && snapshot.runId === runId ? snapshot : null
  const logs = current?.logs ?? emptyLogs
  const timeZone = status?.timeZone

  useEffect(() => {
    if (!visible || runId == null) return
    const abort = new AbortController()
    let loading = false
    const read = async () => {
      if (loading) return
      loading = true
      try {
        const response = await fetch(bossUrl(`/api/boss/follow-up/runs/${runId}/logs`, accountId),
          { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(8_000)]), cache: 'no-store' })
        if (!response.ok) throw new Error('读取跟进日志失败')
        const entries: BossProgressLog[] = await response.json()
        if (!abort.signal.aborted) setSnapshot({ runId, logs: entries, error: false })
      } catch {
        if (!abort.signal.aborted) setSnapshot(previous => ({
          runId, logs: previous?.runId === runId ? previous.logs : null, error: true,
        }))
      } finally {
        loading = false
      }
    }
    void read()
    const timer = working ? window.setInterval(() => void read(), 3_000) : undefined
    return () => { abort.abort(); window.clearInterval(timer) }
  }, [accountId, runId, visible, working])

  async function loadOlder() {
    const before = older.at(-1)?.id ?? recent.at(-1)?.id
    if (!before || loading) return
    setLoading(true)
    setHistoryError(false)
    try {
      const response = await fetch(bossUrl(`/api/boss/follow-up/runs?before=${before}`, accountId), { cache: 'no-store' })
      if (!response.ok) throw new Error('读取历史失败')
      const next: FollowUpRun[] = await response.json()
      setOlder(previous => [...previous, ...next])
      setExhausted(next.length < pageSize)
    } catch { setHistoryError(true) }
    finally { setLoading(false) }
  }

  const emptyMessage = !run ? loadError ? '暂时无法同步跟进记录' : status ? '暂无跟进记录' : '正在读取跟进记录…'
    : current?.error ? '暂时无法读取这轮日志' : current?.logs == null ? '正在读取本轮日志…' : '本轮暂无详细日志'

  return <RunLogPanel key={runId ?? 'idle'} title="跟进运行日志" logs={logs}
    updatedAt={logs.at(-1)?.timestamp} emptyMessage={emptyMessage}>
    {(runs.length > 0 || status?.nextScheduledAt != null) && <div className="space-y-2 rounded-lg border bg-muted/20 p-3 text-xs">
      <div className="flex flex-wrap items-end justify-between gap-2">
        {runs.length > 0 && <div className="grid min-w-0 flex-1 gap-1.5 sm:max-w-sm">
          <Label htmlFor="boss-follow-up-log-run" className="text-xs">跟进批次</Label>
          <Select id="boss-follow-up-log-run" className="h-8 w-full text-xs" value={selected?.id ?? ''}
            onChange={event => setSelected(runs.find(item => String(item.id) === event.target.value) ?? null)}>
            <option value="">自动显示当前或最新一轮</option>
            {runs.map(item => <option value={item.id} key={item.id}>
              {triggerNames[item.trigger]} · {followUpTime(item.scheduledAt ?? item.createdAt, timeZone)} · {followUpStateLabels[item.state] ?? item.state}
            </option>)}
          </Select>
        </div>}
        {!exhausted && (recent.length >= pageSize || older.length > 0) &&
          <Button type="button" variant="ghost" size="sm" disabled={loading} onClick={() => void loadOlder()}>
            {loading ? '正在加载…' : '加载更早记录'}
          </Button>}
        {status?.nextScheduledAt != null && <span className="text-muted-foreground">下次计划 {followUpTime(status.nextScheduledAt, timeZone)}</span>}
      </div>
      {run && <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground">
          <StateBadge state={run.state} />
          <span>{triggerNames[run.trigger]} · {followUpTime(run.scheduledAt ?? run.createdAt, timeZone)}</span>
          {run.startedAt != null && run.scheduledAt != null && <span>实际开始 {followUpTime(run.startedAt, timeZone)}</span>}
          {run.finishedAt != null && <span>结束 {followUpTime(run.finishedAt, timeZone)}</span>}
          {!!run.dates.length && <span className="break-words">投递日期 {run.dates.join('、')}</span>}
        </div>
        <p className="break-words leading-5" role={progress ? 'status' : undefined}>
          {status?.stopping && progress ? '正在停止跟进' : progress?.message ?? run.message}
          {progress?.jobName && ` · ${progress.jobName}`}
          {progress && ` · 核对 ${progress.checked}/${progress.total}，已确认 ${progress.confirmed}`}
        </p>
      </div>}
    </div>}
    {historyError && <p role="alert" className="text-xs text-destructive">无法加载更早的日志。</p>}
    {loadError && run && <p role="status" className="text-xs text-muted-foreground">暂时无法同步执行状态，正在重试。</p>}
    {current?.error && logs.length > 0 && <p role="status" className="text-xs text-muted-foreground">暂时无法更新本轮日志。</p>}
  </RunLogPanel>
}
