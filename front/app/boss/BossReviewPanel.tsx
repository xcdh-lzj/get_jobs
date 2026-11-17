'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { bossUrl } from '@/lib/boss-accounts'
import { defaultReviewEconomy, exclusionLines, reviewCategoryLabels, reviewDecisionLabels, reviewModeLabels, reviewReason, reviewRunLabels, type ResumeProposal, type ReviewDecision, type ReviewOptions, type ReviewRun } from '@/lib/boss-review'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useWorkspaceEdits, useWorkspaceEditor } from '@/app/components/WorkspaceEdits'
import { writeWorkspaceJson } from '@/lib/workspace-save'
import BossResumeDirections from './BossResumeDirections'
import { BossReviewEconomySettings, BossReviewUsagePanel } from './BossReviewCosts'

export default function BossReviewPanel({ accountId, locked, busy, loggedIn, onRefreshTask, active }: {
  accountId: number; locked: boolean; busy: boolean; loggedIn: boolean; onRefreshTask: () => Promise<void>; active: boolean
}) {
  const { saving, editors } = useWorkspaceEdits()
  const hasUnsaved = editors.some(editor => editor.dirty)
  const [view, setView] = useState<'settings' | 'results'>('settings')
  const [options, setOptions] = useState<ReviewOptions | null>(null)
  const [savedOptions, setSavedOptions] = useState<ReviewOptions | null>(null)
  const [exclusions, setExclusions] = useState('')
  const [directions, setDirections] = useState('')
  const [run, setRun] = useState<ReviewRun | null>(null)
  const [runId, setRunId] = useState('')
  const [filter, setFilter] = useState<'all' | ReviewDecision>('all')
  const [command, setCommand] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const mounted = useRef(false)
  const previewRunning = run?.state === 'running' && ['examples', 'history'].includes(run.mode)

  const loadSettings = useCallback(() => {
    const controller = new AbortController()
    fetch(bossUrl('/api/boss/review/settings', accountId), { signal: controller.signal, cache: 'no-store' })
      .then(async response => { if (!response.ok) throw new Error('精准审核设置读取失败，请确认已重启新版后端'); return response.json() })
      .then((data: ReviewOptions) => { if (!controller.signal.aborted) { const normalized = { ...data, economy: data.economy || defaultReviewEconomy }; setOptions(normalized); setSavedOptions(normalized); setDirections(data.policy.targetRoles.join('\n')); setExclusions(data.policy.excludedTitles.join('\n')); setError('') } })
      .catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '设置读取失败') })
    return () => controller.abort()
  }, [accountId])
  useEffect(loadSettings, [loadSettings])

  const refresh = useCallback(async () => {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    const timer = window.setTimeout(() => controller.abort(), 8000)
    try {
      const params = new URLSearchParams({ decision: filter })
      if (runId) params.set('runId', runId)
      const response = await fetch(bossUrl(`/api/boss/review/results?${params}`, accountId), { signal: controller.signal, cache: 'no-store' })
      if (!response.ok) throw new Error('审核记录读取失败')
      const data: { run: ReviewRun | null } = await response.json()
      if (mounted.current && request.current === controller) setRun(data.run)
    } catch (e) {
      if (mounted.current && request.current === controller && !controller.signal.aborted) setError(e instanceof Error ? e.message : '无法同步审核记录')
    } finally { window.clearTimeout(timer); if (request.current === controller) request.current = null }
  }, [accountId, filter, runId])

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; request.current?.abort() } }, [])
  useEffect(() => {
    if (!active && !busy && !previewRunning) return
    let live = true
    queueMicrotask(() => { if (live) void refresh() })
    const timer = window.setInterval(() => { void refresh() }, 3000)
    return () => { live = false; request.current?.abort(); window.clearInterval(timer) }
  }, [refresh, active, busy, previewRunning])

  useEffect(() => {
    if (!busy) return
    let active = true
    queueMicrotask(() => { if (active) { setRunId(''); setFilter('all'); setMessage('') } })
    return () => { active = false }
  }, [busy])

  const action = async (name: string, path: string, body?: unknown, method = 'POST') => {
    setCommand(name); setError(''); setMessage('')
    try {
      const response = await fetch(bossUrl(path, accountId), {
        method, headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      const data = await response.json()
      if (!response.ok || data.success === false) throw new Error(data.message || data.detail || '操作失败')
      if (!mounted.current) return
      setMessage(data.message || '操作成功')
      if (data.runId) { setFilter('all'); setRunId(data.runId) }
      else if (name === 'scan') { setRunId(''); await onRefreshTask() }
      await refresh()
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : '操作失败') }
    finally { if (mounted.current) setCommand(null) }
  }
  const draft = options ? { ...options.policy, targetRoles: exclusionLines(directions), excludedTitles: exclusionLines(exclusions) } : null
  const economy = options?.economy || defaultReviewEconomy
  const invalidEconomy = !Number.isInteger(economy.cacheDays) || economy.cacheDays < 1 || economy.cacheDays > 30
    || !Number.isInteger(economy.taskTokenLimit) || economy.taskTokenLimit < 0 || economy.taskTokenLimit > 10000000
  const dirty = options && savedOptions && JSON.stringify({ ...options, policy: draft }) !== JSON.stringify(savedOptions)
  const trial = (source: 'examples' | 'history') => {
    setView('results')
    if (draft) void action(source, '/api/boss/review/preview', { source, limit: source === 'examples' ? 8 : 20, policy: draft })
  }
  const adoptResume = (proposal: ResumeProposal) => {
    if (!options) return
    const next = { ...options, policy: { enabled: true, goal: proposal.goal, targetRoles: proposal.directions.map(d => d.name), excludedTitles: proposal.excludedTitles } }
    setOptions(next); setDirections(next.policy.targetRoles.join('\n')); setExclusions(next.policy.excludedTitles.join('\n'))
    setMessage('已填入简历建议，点击上方“保存全部修改”后生效。')
  }

  useWorkspaceEditor({
    id: `review-${accountId}`, label: '精准审核', ready: options !== null && savedOptions !== null,
    dirty: !!dirty,
    validationError: !options ? error : invalidEconomy ? '缓存天数须为 1–30 的整数，预算须为 0–10000000 的整数。'
      : draft && (draft.targetRoles.length === 0 || draft.targetRoles.length > 20 || draft.targetRoles.some(role => role.length > 80) || !draft.goal.trim()) ? '请填写目标职责和 1–20 个精投方向，每个方向不超过 80 字。'
      : draft && (draft.excludedTitles.length > 50 || draft.excludedTitles.some(title => title.length > 80)) ? '标题排除词最多 50 条，每条不超过 80 字。' : '',
    reset: () => { if (savedOptions) { setOptions(savedOptions); setDirections(savedOptions.policy.targetRoles.join('\n')); setExclusions(savedOptions.policy.excludedTitles.join('\n')) } },
    prepare: () => {
      const snapshot: ReviewOptions = { policy: draft!, generateGreeting: options!.generateGreeting, economy }
      return { save: async () => {
        await writeWorkspaceJson(bossUrl('/api/boss/review/settings', accountId), snapshot)
        setSavedOptions(snapshot)
      } }
    },
  })

  return <div aria-label="Boss 精准岗位审核" className="space-y-5">
    <div className="section-heading"><div><h2>精准岗位审核</h2><p>根据职位详情匹配实际职责，通过后才发起沟通。</p></div><div className="flex rounded-lg border bg-muted/50 p-1"><Button size="sm" variant={view === 'settings' ? 'secondary' : 'ghost'} onClick={() => setView('settings')}>方向与规则</Button><Button size="sm" variant={view === 'results' ? 'secondary' : 'ghost'} onClick={() => setView('results')}>试审与记录</Button></div></div>
    {message && <p role="status" className="rounded-lg bg-teal-50 p-3 text-sm text-teal-800 dark:bg-teal-950 dark:text-teal-200">{message}</p>}
    {error && <div role="alert" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950 dark:text-amber-200">{error}{!options && <Button variant="outline" size="sm" className="ml-3" onClick={() => { loadSettings() }}>重新读取</Button>}</div>}
    <div hidden={view !== 'settings'}>
      <div className="form-columns">
        <Card><CardHeader><div className="flex flex-wrap items-center justify-between gap-2"><CardTitle>目标方向与职责</CardTitle><span className={`rounded-md px-2 py-1 text-[10px] ${savedOptions?.policy.enabled ? 'bg-teal-50 text-teal-800 dark:bg-teal-950 dark:text-teal-200' : 'bg-muted text-muted-foreground'}`}>{savedOptions ? savedOptions.policy.enabled ? '已启用精准审核' : '未启用精准审核' : '读取中'}</span></div></CardHeader><CardContent>
          {options && <fieldset disabled={locked || saving} className="space-y-4 disabled:opacity-60">
        <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={options.policy.enabled} onChange={e => setOptions(p => p && ({ ...p, policy: { ...p.policy, enabled: e.target.checked } }))} />正式投递启用精准审核</label>
        <label className="block text-sm font-medium">精投方向（每行一个，可自由添加或修改）<textarea aria-label="精投方向" rows={3} value={directions} onChange={e => setDirections(e.target.value)} className="mt-2 w-full rounded-md border bg-background p-3 text-sm font-normal leading-6" /></label>
        <p className="text-xs text-muted-foreground">方向代表一类实际职责，最多 20 个；招聘标题可以不同。也可根据右侧简历分析生成方向，同时核对目标职责与标题排除词。</p>
        <label className="block text-sm font-medium">目标岗位主要职责<textarea aria-label="目标岗位主要职责" rows={3} maxLength={3000} value={options.policy.goal} onChange={e => setOptions(p => p && ({ ...p, policy: { ...p.policy, goal: e.target.value } }))} className="mt-2 w-full rounded-md border bg-background p-3 font-normal leading-6" /></label>
        <details><summary className="cursor-pointer text-sm">标题排除词（每行一个完整岗位词）</summary><textarea aria-label="标题排除词" rows={5} value={exclusions} onChange={e => setExclusions(e.target.value)} className="mt-2 w-full rounded-md border bg-background p-3 text-sm" /><p className="mt-1 text-xs text-muted-foreground">推荐使用“产品经理”“销售顾问”等岗位词，避免仅用“产品”误过滤产品研发工程师。城市、经验和期望薪资沿用当前账号搜索设置。</p></details>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={options.generateGreeting} onChange={e => setOptions(p => p && ({ ...p, generateGreeting: e.target.checked }))} />通过审核后，使用 AI 生成招呼语</label>
        <p className="text-xs text-muted-foreground">岗位审核独立于招呼语开关，使用公共 AI 服务。JD 缺失、职责不明确、AI 超时或缺少目标职责证据时，均自动跳过并继续后续岗位，无需逐条人工审核。</p>

          </fieldset>}
        </CardContent></Card>
        <div className="space-y-5"><Card><CardHeader><CardTitle>让简历帮你确定方向</CardTitle></CardHeader><CardContent><BossResumeDirections accountId={accountId} disabled={!options || locked || busy || command !== null || saving} onAdopt={adoptResume} /></CardContent></Card>
          <Card><CardHeader><CardTitle>审核费用与缓存</CardTitle></CardHeader><CardContent><fieldset disabled={locked || saving} className="disabled:opacity-60">{options && <BossReviewEconomySettings value={economy} onChange={economy => setOptions(previous => previous && ({ ...previous, economy }))} />}</fieldset><p className="mt-3 text-xs leading-6 text-muted-foreground">先按规则初筛，再复用有效缓存，只有候选岗位进入 AI 审核。</p><Button variant="outline" className="mt-4 w-full" onClick={() => setView('results')}>查看试审记录与 Token 用量 →</Button></CardContent></Card>
        </div>
      </div>
    </div>
    <div hidden={view !== 'results'}><Card><CardContent className="space-y-5 pt-5">
      <div className="space-y-3 rounded-xl border bg-muted/20 p-4">
        <p className="font-medium">试审工具 · 仅分析，不投递</p>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={!draft || command !== null || previewRunning || busy} onClick={() => trial('examples')}>{command === 'examples' ? '正在启动…' : '试审 8 个示例（不投递）'}</Button>
          <Button variant="outline" disabled={!draft || command !== null || previewRunning || busy} onClick={() => trial('history')}>试审最近 20 个历史岗位</Button>
          <Button variant="outline" disabled={!draft || command !== null || previewRunning || busy || !loggedIn || hasUnsaved || saving} onClick={() => void action('scan', '/api/boss/review/scan')}>采集并审核（不投递）</Button>
          {previewRunning && <Button variant="outline" disabled={command !== null} onClick={() => void action('stop', `/api/boss/review/runs/${run.id}/stop`)}>停止试审</Button>}
        </div>
        <p className="text-xs leading-6 text-muted-foreground">试审会调用当前 AI 服务。示例和历史试审使用当前填写的目标，不需要登录浏览器。示例只验证岗位方向，不套用城市薪资条件。采集审核使用已保存设置，需要先保存修改并登录；可在上方停止审核。</p>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div><p className="font-medium">审核结果与依据</p><p className="text-xs text-muted-foreground">{run ? `${reviewModeLabels[run.mode]} · ${reviewRunLabels[run.state] || run.state} · ${new Date(run.startedAt).toLocaleString('zh-CN')}` : '尚无记录，可先试审示例或历史岗位'}</p></div>
        <div className="flex gap-2"><Button size="sm" variant="outline" onClick={() => { setRunId(''); void refresh() }}>查看最新运行</Button><Button size="sm" variant="outline" onClick={() => void refresh()}>刷新审核结果</Button></div>
      </div>
      {run && <>
        <BossReviewUsagePanel usage={run.usage} />
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">{[['已审核', run.total], ['通过', run.passed], ['已过滤', run.rejected], ['自动跳过', run.pending], ['实际已投递', run.delivered]].map(([label, value]) => <div key={label} className="rounded-lg border p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p></div>)}</div>
        {run.error && <p role="alert" className="text-sm text-amber-700">{run.error}</p>}
        <div className="flex flex-wrap gap-2">{(['all', 'pass', 'reject', 'review'] as const).map(value => <Button key={value} size="sm" variant={filter === value ? 'default' : 'outline'} onClick={() => setFilter(value)}>{value === 'all' ? '全部' : reviewDecisionLabels[value]}</Button>)}</div>
        <div className="max-h-[32rem] space-y-3 overflow-y-auto" aria-live="polite">
          {run.rows.map(row => <article key={row.id} className="space-y-2 rounded-xl border p-4">
            <div className="flex flex-wrap items-center justify-between gap-2"><strong>{row.result.job.title || '名称缺失'}</strong><span className={`rounded-full px-3 py-1 text-xs ${row.result.decision === 'pass' ? 'bg-emerald-100 text-emerald-800' : row.result.decision === 'reject' ? 'bg-red-100 text-red-800' : 'bg-amber-100 text-amber-800'}`}>{reviewDecisionLabels[row.result.decision]}</span></div>
            <p className="text-xs text-muted-foreground">{[row.result.job.company, row.result.job.city, row.result.job.salary].filter(Boolean).join(' · ')} · HR：{row.result.job.activeStatus || '未读取到活跃信息'} · {reviewCategoryLabels[row.result.category] || row.result.category} · {row.result.stage === 'basic' ? '规则初筛' : 'JD 语义审核'}{row.cached ? ' · 复用相同文本和规则的审核' : ''}</p>
            <p className="text-sm leading-6">{reviewReason(row)}</p>
            {row.result.evidence.length > 0 && <ul className="space-y-1 border-l-2 border-emerald-300 pl-3 text-sm text-muted-foreground">{row.result.evidence.map((text, i) => <li key={i}>“{text}”</li>)}</ul>}
            <p className="text-xs text-muted-foreground">{row.deliveryState === 'sent' ? '本次已投递' : row.deliveryState === 'preview_only' || run.mode !== 'delivery' ? '仅审核，未投递' : row.result.decision !== 'pass' ? '已跳过，继续处理后续岗位' : '尚未发起投递'}</p>
            {row.result.job.description && <details><summary className="cursor-pointer text-xs text-muted-foreground">查看本次审核使用的 JD</summary><p className="mt-2 whitespace-pre-wrap text-sm leading-6">{row.result.job.description}</p></details>}
          </article>)}
          {run.rows.length === 0 && <p className="p-4 text-sm text-muted-foreground">{run.state === 'running' ? '正在处理岗位，请稍候…' : '当前条件下没有审核记录'}</p>}
        </div>
        <p className="text-xs text-muted-foreground">仅显示本账号本次运行最近 200 条记录。通过表示方向审核通过；试审和仅采集审核都不会投递。</p>
      </>}
    </CardContent></Card></div>
  </div>
}
