'use client'

import { useEffect, useRef, useState } from 'react'
import { BiRefresh, BiTime } from 'react-icons/bi'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { batchProgressPercent, type BossTaskStatus } from '@/lib/boss-progress'
import { cityStateLabels } from '@/lib/boss-cities'

const stateLabels = {
  idle: '未开始', starting: '准备中', running: '投递中', stopping: '停止中',
  completed: '已完成', stopped: '已停止', failed: '执行失败',
  quota_reached: '今日沟通已达上限',
  budget_reached: '审核预算已触发',
}

function duration(milliseconds: number) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

function time(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

export default function DeliveryProgress({ status, connection, error, onRefresh }: {
  status: BossTaskStatus | null
  connection: 'connecting' | 'live' | 'reconnecting'
  error: string | null
  onRefresh: () => Promise<void>
}) {
  const progress = status?.progress
  const [now, setNow] = useState(() => Date.now())
  const logPanel = useRef<HTMLDivElement>(null)
  const followLogs = useRef(true)
  useEffect(() => {
    if (!status?.isRunning) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [status?.isRunning])
  useEffect(() => {
    if (followLogs.current && logPanel.current) logPanel.current.scrollTop = logPanel.current.scrollHeight
  }, [progress?.revision])

  const percent = progress ? batchProgressPercent(progress) : null
  const elapsed = progress?.startedAt == null ? 0
    : (progress.finishedAt ?? Math.max(now, progress.updatedAt)) - progress.startedAt
  const stateColor = progress?.state === 'failed' ? 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300'
    : progress?.state === 'stopping' || progress?.state === 'quota_reached' || progress?.state === 'budget_reached' ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
    : status?.isRunning ? 'bg-teal-100 text-teal-800 dark:bg-teal-950 dark:text-teal-300'
    : 'bg-muted text-muted-foreground'

  return (
    <Card aria-label="实时投递进度" className="overflow-hidden border-border">
      <CardHeader className="pb-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <CardTitle className="text-lg">实时投递进度</CardTitle>
            <span className={`rounded-full px-3 py-1 text-xs font-medium ${stateColor}`}>
              {progress ? stateLabels[progress.state] : '同步中'}
            </span>
          </div>
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <span className={`h-2 w-2 rounded-full ${connection === 'live' && !error ? 'bg-emerald-500' : 'bg-amber-500'}`} />
              {connection === 'live' && !error ? '实时连接' : connection === 'connecting' ? '正在连接' : '重连中，保留最近状态'}
            </span>
            <Button variant="ghost" size="sm" onClick={() => { void onRefresh() }} aria-label="刷新投递状态">
              <BiRefresh className="mr-1 text-lg" /> 刷新
            </Button>
          </div>
        </div>
        <CardDescription>当前任务的进度与最近运行记录，刷新页面后会自动恢复。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {progress?.cityProgress && <div className="space-y-3 rounded-xl border p-4" aria-label="城市执行进度"><p className="text-sm font-medium">城市队列 · {Math.max(0, progress.cityProgress.currentIndex + 1)} / {progress.cityProgress.cities.length}</p><div className="grid gap-2 sm:grid-cols-2">{progress.cityProgress.cities.map(city => <article key={city.code} className="rounded-lg border bg-muted/20 p-3"><div className="flex items-center justify-between gap-2 text-sm"><strong>{city.name}</strong><span>{cityStateLabels[city.state] || city.state}</span></div><p className="mt-2 text-xs text-muted-foreground">已处理 {city.processed} · 新增可投 {city.newJobs} · 待核对 {city.uncertain}</p>{city.state === 'running' && <p className="mt-1 text-xs text-muted-foreground">当前关键词观察窗口 {city.windowCount}/{progress.cityProgress!.windowSize} · 新增可投 {city.windowNewJobs} · 不确定 {city.windowUncertain}</p>}<p className="mt-1 text-xs leading-5 text-muted-foreground">{city.reason}</p></article>)}</div></div>}
        {error && <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">{error}</p>}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            ['累计已处理', progress?.processedCount ?? '—'],
            ['已投递', progress?.deliveredCount ?? '—'],
            ['未投递', progress?.skippedCount ?? '—'],
            ['任务耗时', progress ? duration(elapsed) : '—'],
          ].map(([label, value]) => (
            <div key={label} className="rounded-lg border bg-muted/20 p-3">
              <p className="text-xs text-muted-foreground">{label}</p>
              <p className="mt-2 text-lg font-semibold tabular-nums">{value}</p>
            </div>
          ))}
        </div>
        <div className="rounded-xl border p-4">
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="font-medium">{progress?.keyword ? `${progress.city ?? ''} · ${progress.keyword}` : '等待搜索任务'}</span>
            <span className="text-muted-foreground tabular-nums">
              {progress && progress.total > 0 ? `${progress.current} / ${progress.total} 个岗位 · ${percent}%` : '岗位数量待确认'}
            </span>
          </div>
          <div role="progressbar" aria-label="当前搜索处理进度" aria-valuemin={0} aria-valuemax={100}
            aria-valuenow={percent ?? undefined} className="mt-3 h-2 overflow-hidden rounded-full bg-muted">
            <div className={`h-full rounded-full bg-teal-500 transition-[width] duration-300 ${percent == null && status?.isRunning ? 'w-1/3 animate-pulse' : ''}`}
              style={{ width: percent == null ? status?.isRunning ? '33%' : '0%' : `${percent}%` }} />
          </div>
          <p className="mt-3 text-sm font-medium" role="status">{progress?.message ?? '正在读取后端任务状态…'}</p>
          {(progress?.jobName || progress?.companyName) && <p className="mt-1 text-sm text-muted-foreground">
            {[progress.companyName, progress.jobName].filter(Boolean).join(' · ')}
          </p>}
          <p className="mt-3 text-xs text-muted-foreground">进度条按当前城市和关键词计算；累计数量覆盖本次任务。未投递包含过滤、跳过及未完成发送的岗位。</p>
        </div>
        <div>
          <div className="mb-2 flex items-center justify-between text-sm">
            <span className="font-medium">运行日志</span>
            <span className="flex items-center gap-1 text-xs text-muted-foreground"><BiTime />
              {progress?.taskId ? `最近更新 ${time(progress.updatedAt)}` : '等待任务开始'}
            </span>
          </div>
          <div ref={logPanel} onScroll={event => {
            const panel = event.currentTarget
            followLogs.current = panel.scrollHeight - panel.scrollTop - panel.clientHeight < 40
          }} className="max-h-64 overflow-y-auto rounded-xl border bg-muted/20 p-3 font-mono text-xs leading-6">
            {!progress?.logs.length ? <p className="py-5 text-center text-muted-foreground">任务开始后，这里会显示搜索、分析和投递进度。</p>
              : progress.logs.map(entry => <div key={entry.id} className="flex gap-3">
                <time className="shrink-0 text-muted-foreground">{time(entry.timestamp)}</time>
                <span className={`min-w-0 break-words ${entry.type === 'error' ? 'text-red-600 dark:text-red-400' : entry.type === 'warning' ? 'text-amber-700 dark:text-amber-400' : entry.type === 'success' ? 'text-teal-700 dark:text-teal-400' : ''}`}>{entry.message}</span>
              </div>)}
          </div>
          {progress && progress.logs.length >= 200 && <p className="mt-1 text-xs text-muted-foreground">显示最近 200 条记录。</p>}
        </div>
      </CardContent>
    </Card>
  )
}
