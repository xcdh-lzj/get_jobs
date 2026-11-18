'use client'

import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { BiCheck, BiChevronDown, BiCopy, BiFile, BiPause, BiPlay, BiSearch, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { BossProgressLog } from '@/lib/boss-progress'
import { buildLogRows, countNewLogs, exportRunLogs, logTime, visibleLogRows, type LogKind, type LogRow, type LogView } from '@/lib/boss-run-logs'

const kinds: Record<LogKind, { label: string; classes: string; dot: string }> = {
  task: { label: '任务', classes: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300', dot: 'bg-slate-400' },
  detail: { label: '过程', classes: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400', dot: 'bg-slate-300 dark:bg-slate-600' },
  filtered: { label: '未投递', classes: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300', dot: 'bg-slate-400' },
  review: { label: '审核', classes: 'bg-sky-50 text-sky-700 dark:bg-sky-950/50 dark:text-sky-300', dot: 'bg-sky-500' },
  completed: { label: '流程完成', classes: 'bg-teal-50 text-teal-700 dark:bg-teal-950/50 dark:text-teal-300', dot: 'bg-teal-500' },
  warning: { label: '提醒', classes: 'bg-amber-50 text-amber-800 dark:bg-amber-950/50 dark:text-amber-300', dot: 'bg-amber-500' },
  error: { label: '异常', classes: 'bg-red-50 text-red-700 dark:bg-red-950/50 dark:text-red-300', dot: 'bg-red-500' },
}

const views: { value: LogView; label: string }[] = [
  { value: 'key', label: '关键日志' }, { value: 'all', label: '全部日志' }, { value: 'attention', label: '提醒与异常' },
]

const LogEntry = memo(function LogEntry({ row }: { row: LogRow }) {
  const [expanded, setExpanded] = useState(false)
  const style = kinds[row.kind]
  const long = row.message.length > 180 || row.message.split('\n').length > 3
  return (
    <li className={`grid grid-cols-[4rem_minmax(0,1fr)] gap-x-3 px-3 py-3 sm:px-4 ${row.kind === 'error' ? 'bg-red-50/40 dark:bg-red-950/15' : row.kind === 'warning' ? 'bg-amber-50/30 dark:bg-amber-950/10' : ''}`}>
      <time dateTime={new Date(row.timestamp).toISOString()} className="pt-0.5 font-mono text-[11px] tabular-nums text-muted-foreground">{logTime(row.timestamp)}</time>
      <div className="min-w-0">
        <div className="mb-1.5 flex flex-wrap items-center gap-2">
          <span className={`inline-flex items-center gap-1.5 rounded-md px-1.5 py-0.5 text-[10px] font-medium ${style.classes}`}>
            <span className={`h-1 w-1 rounded-full ${style.dot}`} />{style.label}
          </span>
          {row.count > 1 && <span className="text-[11px] text-muted-foreground" title={`最后一次出现于 ${logTime(row.lastTimestamp)}`}>连续重复 ×{row.count}</span>}
        </div>
        <p className={`whitespace-pre-wrap text-[13px] leading-[1.7] [overflow-wrap:anywhere] ${long && !expanded ? 'line-clamp-3' : ''}`}>{row.message}</p>
        {long && <button type="button" onClick={() => setExpanded(value => !value)} className="mt-1.5 text-xs font-medium text-primary hover:underline">{expanded ? '收起详情' : '展开完整内容'}</button>}
      </div>
    </li>
  )
})

function RunLogPanel({ logs, updatedAt }: { logs: BossProgressLog[]; updatedAt?: number }) {
  const [view, setView] = useState<LogView>('key')
  const [query, setQuery] = useState('')
  const [following, setFollowing] = useState(true)
  const [pausedAtId, setPausedAtId] = useState(0)
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const panel = useRef<HTMLDivElement>(null)
  const content = useRef<HTMLDivElement>(null)
  const followedSize = useRef<{ scrollHeight: number; clientHeight: number } | null>(null)
  const rows = useMemo(() => buildLogRows(logs), [logs])
  const visible = useMemo(() => visibleLogRows(rows, view, query), [rows, view, query])
  const latestId = logs.at(-1)?.id ?? 0
  const unseen = following ? 0 : countNewLogs(logs, pausedAtId)
  const folded = logs.length - rows.length

  const scrollToLatest = useCallback(() => {
    const node = panel.current
    if (!node || node.clientHeight === 0) {
      followedSize.current = null
      return
    }
    const { scrollHeight, clientHeight } = node
    node.scrollTop = Math.max(0, scrollHeight - clientHeight)
    followedSize.current = { scrollHeight, clientHeight }
  }, [])

  // 日志更新后先贴底再绘制，避免浏览器的滚动事件读到新高度和旧位置。
  useLayoutEffect(() => {
    if (following) scrollToLatest()
  }, [following, scrollToLatest, visible])

  useLayoutEffect(() => {
    if (!following || !panel.current || !content.current || typeof ResizeObserver === 'undefined') return
    // 展开日志、文字换行或切回任务概览也会改变高度。
    const observer = new ResizeObserver(scrollToLatest)
    observer.observe(panel.current)
    observer.observe(content.current)
    return () => observer.disconnect()
  }, [following, scrollToLatest])

  useEffect(() => {
    if (copyState === 'idle') return
    const timer = window.setTimeout(() => setCopyState('idle'), 2500)
    return () => window.clearTimeout(timer)
  }, [copyState])

  function pause() {
    setFollowing(false)
    setPausedAtId(latestId)
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(exportRunLogs(logs))
      setCopyState('copied')
    } catch { setCopyState('failed') }
  }

  return (
    <section aria-label="运行日志" className="overflow-hidden rounded-xl border bg-background">
      <div className="space-y-3 border-b px-3 py-3 sm:px-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="rounded-lg bg-primary/10 p-1.5 text-primary"><BiFile className="h-4 w-4" /></span>
            <h3 className="text-sm font-semibold">运行日志</h3>
            <span className="text-xs tabular-nums text-muted-foreground">{logs.length} 条</span>
          </div>
          <div className="flex items-center gap-1">
            <Button type="button" variant="ghost" size="sm" aria-label={following ? '暂停日志自动跟随' : '恢复日志自动跟随'} aria-pressed={following}
              onClick={() => following ? pause() : setFollowing(true)} className={following ? 'text-primary' : 'text-muted-foreground'}>
              {following ? <BiPlay /> : <BiPause />}{following ? '自动跟随' : '已暂停'}
            </Button>
            <Button type="button" variant="ghost" size="sm" disabled={!logs.length} onClick={() => { void copy() }} aria-label="复制全部原始日志">
              {copyState === 'copied' ? <BiCheck /> : <BiCopy />}{copyState === 'copied' ? '已复制' : '复制全部'}
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div role="group" aria-label="日志显示范围" className="inline-flex max-w-full flex-wrap gap-0.5 rounded-lg bg-muted/70 p-0.5">
            {views.map(item => <button key={item.value} type="button" aria-pressed={view === item.value} onClick={() => setView(item.value)}
              className={`rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${view === item.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
              {item.label}<span className="ml-1.5 text-[10px] tabular-nums opacity-60">{visibleLogRows(rows, item.value, '').length}</span>
            </button>)}
          </div>
          <div className="relative min-w-0 flex-1 basis-40 sm:max-w-56">
            <BiSearch aria-hidden="true" className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input aria-label="搜索运行日志" placeholder="搜索岗位、原因或关键词" value={query} onChange={event => setQuery(event.target.value)} className="h-9 pl-8 pr-8 text-xs" />
            {query && <button type="button" aria-label="清空日志搜索" onClick={() => setQuery('')} className="absolute right-1 top-1 rounded-md p-1.5 text-muted-foreground hover:bg-muted"><BiX className="h-4 w-4" /></button>}
          </div>
        </div>
      </div>
      <div className="relative">
        <div ref={panel} tabIndex={0} aria-label="日志内容" onScroll={event => {
          const { scrollHeight, clientHeight, scrollTop } = event.currentTarget
          const size = followedSize.current
          // 高度变化造成的位置偏差交给 ResizeObserver，只有列表尺寸稳定时才判断是否上滚。
          if (following && size?.scrollHeight === scrollHeight && size.clientHeight === clientHeight
            && scrollHeight - clientHeight - scrollTop > 40) pause()
        }} className={`max-h-[40rem] min-h-56 overflow-y-auto overscroll-contain focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring ${following ? '[overflow-anchor:none]' : ''}`}>
          <div ref={content}>
            {visible.length ? <ol className="divide-y divide-border/60 pb-10">{visible.map(row => <LogEntry key={row.id} row={row} />)}</ol>
              : <div className="flex min-h-40 flex-col items-center justify-center gap-2 px-5 py-7 text-center">
                <BiFile className="mb-1 h-7 w-7 text-muted-foreground/50" />
                <p className="text-sm font-medium">{!logs.length ? '等待任务开始' : query ? '没有匹配的日志' : view === 'attention' ? '当前没有提醒或异常' : '暂无关键日志'}</p>
                <p className="max-w-sm text-xs leading-5 text-muted-foreground">{!logs.length ? '任务开始后，这里会显示关键进展与每个岗位的处理结果。' : query ? '换一个关键词，或切换到全部日志查看。' : '可切换到全部日志查看当前保留的运行记录。'}</p>
              </div>}
          </div>
        </div>
        {!following && <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center px-4">
          <Button type="button" variant="outline" size="sm" onClick={() => setFollowing(true)} className="pointer-events-auto rounded-full bg-background shadow-md"><BiChevronDown />{unseen ? `${unseen} 条新日志 · 回到最新` : '回到最新并跟随'}</Button>
        </div>}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-t bg-muted/25 px-3 py-2 text-[11px] text-muted-foreground sm:px-4">
        <span>显示 {visible.length} 条{folded > 0 ? ` · 已折叠 ${folded} 条连续重复提示` : ''}{logs.length >= 200 ? ' · 仅保留最近 200 条' : ''}</span>
        <span role="status">{copyState === 'failed' ? '复制失败，可在全部日志中手动选择文本' : updatedAt && logs.length ? `更新于 ${logTime(updatedAt)}` : '等待实时更新'}</span>
      </div>
    </section>
  )
}

export default memo(RunLogPanel)
