'use client'

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { API_BASE } from '@/lib/api'
import { bossUrl, moveQueueAccount, queueStateLabel, type BossAccount, type BossQueue } from '@/lib/boss-accounts'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Copy, UserRound } from 'lucide-react'
import { useWorkspaceEdits, useWorkspaceEditor } from '@/app/components/WorkspaceEdits'
import { writeWorkspaceJson } from '@/lib/workspace-save'
import { useBossFollowUp } from '@/lib/use-boss-follow-up'
import BossInboxOverview from './BossInboxOverview'

export default function AccountsWorkspace({ children }: {
  children: (id: number, queue: BossQueue | null, name: string, management: ReactNode, initialSection: string,
    followUp: ReturnType<typeof useBossFollowUp>) => ReactNode
}) {
  const { confirmLeave, saving, editors } = useWorkspaceEdits()
  const hasDraft = editors.some(editor => editor.dirty)
  const editorsReady = editors.length >= 3 && editors.every(editor => editor.ready)
  const [accounts, setAccounts] = useState<BossAccount[]>([])
  const [selected, setSelected] = useState(1)
  const [queue, setQueue] = useState<BossQueue | null>(null)
  const [order, setOrder] = useState<number[]>([])
  const [name, setName] = useState('')
  const [copy, setCopy] = useState(false)
  const [rename, setRename] = useState('')
  const [pending, setPending] = useState(false)
  const [syncSourceId, setSyncSourceId] = useState('')
  const [syncingConfigs, setSyncingConfigs] = useState(false)
  const [configSyncVersion, setConfigSyncVersion] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const alive = useRef(false)
  const followUp = useBossFollowUp(selected)
  const deliveryBusy = queue?.isRunning ?? false
  const busy = deliveryBusy || followUp.activeAccountId !== null || followUp.command === 'start'
  const selectedAccount = accounts.find(a => a.id === selected)
  const locked = (deliveryBusy && !!queue?.items.some(item => item.accountId === selected)) || followUp.activeAccountId === selected
  const syncSources = accounts.filter(account => account.id !== selected)
  const syncSource = syncSources.find(account => String(account.id) === syncSourceId)

  const refresh = useCallback(async () => {
    if (request.current) return
    const controller = new AbortController()
    request.current = controller
    const timeout = window.setTimeout(() => controller.abort(), 8000)
    try {
      const [accountResponse, queueResponse] = await Promise.all([
        fetch(`${API_BASE}/api/boss/accounts`, { signal: controller.signal, cache: 'no-store' }),
        fetch(`${API_BASE}/api/boss/queue`, { signal: controller.signal, cache: 'no-store' }),
      ])
      if (!accountResponse.ok || !queueResponse.ok) throw new Error('无法读取账号和队列，请确认已重启更新后的后端')
      const nextAccounts: BossAccount[] = await accountResponse.json()
      const nextQueue: BossQueue = await queueResponse.json()
      if (!alive.current || request.current !== controller) return
      setAccounts(nextAccounts)
      setQueue(previous => previous?.instanceId === nextQueue.instanceId && previous.revision > nextQueue.revision ? previous : nextQueue)
      setError('')
    } catch (e) {
      if (alive.current && request.current === controller) setError(e instanceof Error ? e.message : '连接后端失败')
    } finally {
      window.clearTimeout(timeout)
      if (request.current === controller) request.current = null
    }
  }, [])

  useEffect(() => {
    alive.current = true
    queueMicrotask(() => {
      if (!alive.current) return
      const stored = Number(localStorage.getItem('boss-account-id'))
      if (Number.isSafeInteger(stored) && stored > 0) setSelected(stored)
      void refresh()
    })
    const timer = window.setInterval(() => { void refresh() }, 3000)
    return () => { alive.current = false; request.current?.abort(); request.current = null; window.clearInterval(timer) }
  }, [refresh])

  const select = async (id: number) => {
    if (id === selected || syncingConfigs || followUp.command || !(await confirmLeave())) return
    setSelected(id); setRename(''); localStorage.setItem('boss-account-id', String(id)); setMessage('')
    setSyncSourceId(''); setConfigSyncVersion(0)
  }
  const action = async (path: string, body?: unknown, method = 'POST') => {
    setPending(true); setMessage(''); setError('')
    try {
      const response = await fetch(`${API_BASE}${path}`, {
        method, headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      const result = await response.json()
      if (!response.ok || result.success === false) throw new Error(result.message || result.detail || '操作失败')
      request.current?.abort(); request.current = null
      await refresh()
      setMessage(result.message || '操作成功')
      return result
    } catch (e) { setError(e instanceof Error ? e.message : '操作失败'); return null }
    finally { setPending(false) }
  }

  const syncConfigs = async () => {
    if (!syncSource || !selectedAccount || pending || saving || hasDraft || locked) return
    setSyncingConfigs(true)
    try {
      const result = await action(`/api/boss/accounts/${selected}/sync-config`, { sourceAccountId: syncSource.id })
      if (result) setConfigSyncVersion(version => version + 1)
    } finally {
      setSyncingConfigs(false)
    }
  }

  const management = <div className="form-columns">
    <Card className="lg:col-span-2">
      <CardHeader>
        <CardTitle>同步全部配置</CardTitle>
        <CardDescription>用来源账号的已保存配置覆盖当前账号「{selectedAccount?.name || '当前账号'}」。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {syncSources.length ? <>
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-full max-w-xs space-y-1.5">
              <label htmlFor="boss-config-sync-source" className="block text-sm font-medium">来源账号</label>
              <Select id="boss-config-sync-source" value={syncSourceId} placeholder="选择要复制的账号"
                disabled={pending || saving || locked} onChange={event => setSyncSourceId(event.target.value)}
              >
                {syncSources.map(account => <option key={account.id} value={account.id}>{account.name}（账号 {account.id}）</option>)}
              </Select>
            </div>
            <Button type="button" disabled={!syncSource || pending || saving || hasDraft || locked}
              onClick={() => void syncConfigs()}
            ><Copy className="h-4 w-4" />同步全部到当前账号</Button>
          </div>
          <p className="text-xs leading-5 text-muted-foreground">同步投递筛选、城市计划、HR 活跃规则、精准审核、Token 设置、黑名单、招呼语、跟进配置、个人介绍、AI 提示词和图片简历。</p>
          {hasDraft && <p className="text-sm text-amber-700 dark:text-amber-300">当前账号有未保存修改，请先保存或撤销。</p>}
          {locked && <p className="text-sm text-amber-700 dark:text-amber-300">当前账号有投递或跟进任务，结束后可以同步。</p>}
        </> : <p className="text-sm text-muted-foreground">添加第二个 Boss 账号后，即可同步配置。</p>}
      </CardContent>
    </Card>
    <Card>
      <CardHeader><CardTitle>账号与登录 <span className="instant-badge ml-2">操作即时生效</span></CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <Button disabled={pending || saving || busy || !selectedAccount} onClick={() => void action(`/api/boss/accounts/${selected}/login`)}>打开该账号浏览器登录</Button>
          <Button variant="outline" disabled={pending || saving || busy || !selectedAccount} onClick={() => void action(`/api/boss/accounts/${selected}/save-login`)}>确认并保存登录</Button>
        </div>
        <p className="text-sm text-muted-foreground">每个账号分别扫码登录。切换查看不会改变正在执行的账号；下面的配置、简历和投递记录均属于当前查看账号。</p>
        <p className="text-xs text-muted-foreground">无窗口运行时，可点击“确认并保存登录”检查此前保存的登录状态；需要重新扫码时再打开登录窗口。</p>
        <div className="flex flex-wrap items-center gap-2 rounded-lg border p-3">
          <Input aria-label="新账号名称" placeholder="新账号名称" maxLength={40} value={name} onChange={e => setName(e.target.value)} className="max-w-52" />
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={copy} onChange={e => setCopy(e.target.checked)} />复制当前账号已保存的投递配置</label>
          <Button disabled={pending || !name.trim()} onClick={async () => {
            const result = await action('/api/boss/accounts', { name, copyFromAccountId: copy ? selected : null })
            if (result) { select(result.id); setName('') }
          }}>添加账号</Button>
          <span className="text-xs text-muted-foreground">个人介绍、图片简历和登录状态需单独设置。</span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input aria-label="修改账号名称" placeholder={`重命名：${selectedAccount?.name || ''}`} maxLength={40} value={rename} onChange={e => setRename(e.target.value)} className="max-w-52" />
          <Button variant="outline" disabled={pending || locked || !rename.trim()} onClick={async () => {
            if (await action(`/api/boss/accounts/${selected}`, { name: rename }, 'PUT')) setRename('')
          }}>重命名</Button>
        </div>
      </CardContent>
    </Card>
    <Card>
      <CardHeader><CardTitle>账号投递队列</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-3">
          {accounts.map(a => <label key={a.id} className="flex items-center gap-2 rounded border px-3 py-2 text-sm">
            <input type="checkbox" disabled={pending || saving || busy} checked={order.includes(a.id)} onChange={e => setOrder(ids => e.target.checked ? [...ids, a.id] : ids.filter(id => id !== a.id))} />
            {a.name}
          </label>)}
        </div>
        {order.length > 0 && <ol className="space-y-2">
          {order.map((id, index) => <li key={id} className="flex items-center gap-2 text-sm">
            <span className="min-w-40">{index + 1}. {accounts.find(a => a.id === id)?.name}</span>
            <Button size="sm" variant="outline" aria-label={`上移第 ${index + 1} 个账号`} disabled={busy || pending || index === 0} onClick={() => setOrder(ids => moveQueueAccount(ids, index, -1))}>↑</Button>
            <Button size="sm" variant="outline" aria-label={`下移第 ${index + 1} 个账号`} disabled={busy || pending || index === order.length - 1} onClick={() => setOrder(ids => moveQueueAccount(ids, index, 1))}>↓</Button>
          </li>)}
        </ol>}
        <div className="flex flex-wrap items-center gap-3">
          <Button disabled={pending || saving || hasDraft || !editorsReady || busy || !queue || order.length === 0} onClick={() => void action('/api/boss/queue/start', { accountIds: order })}>启动账号队列</Button>
          <Button variant="destructive" disabled={pending || !deliveryBusy || queue?.state === 'stopping'} onClick={() => void action('/api/boss/queue/stop')}>{queue?.state === 'stopping' ? '等待当前操作停止…' : '停止整个队列'}</Button>
          <span className="text-sm">{queue ? queueStateLabel[queue.state] || queue.state : '正在同步队列…'}</span>
        </div>
        <p className="text-sm text-muted-foreground">先保存各账号配置并确认登录，再按勾选顺序执行。当前账号有未保存修改时无法启动队列。任一账号失败或手动停止后，后续账号不会启动。重启程序不会自动恢复投递。</p>
        {queue && queue.items.length > 0 && <div className="space-y-2 rounded-lg border p-3" aria-live="polite">
          <p className="text-sm font-medium">{queue.message}</p>
          {queue.items.map((item, index) => <div key={item.accountId} className="flex flex-wrap items-center justify-between gap-2 border-t pt-2 text-sm">
            <div><strong>{index + 1}. {item.accountName}</strong> · {queueStateLabel[item.state] || item.state} · 已投递 {item.deliveredCount}<p className="text-xs text-muted-foreground">{item.message}</p></div>
            <Button size="sm" variant="outline" onClick={() => select(item.accountId)}>查看账号</Button>
          </div>)}
        </div>}
      </CardContent>
    </Card>
</div>
  return <div className="workbench space-y-5">
    <div className="flex flex-wrap items-center justify-between gap-4">
      <div><p className="section-eyebrow">Delivery workspace</p><h1 className="text-2xl font-semibold tracking-tight">Boss 投递工作台</h1><p className="mt-1.5 text-sm text-muted-foreground">管理账号与投递策略，专注每一次有效沟通。</p></div>
      <div className="flex items-center gap-3 rounded-xl border bg-card px-3.5 py-2.5 shadow-sm">
        <span className="flex h-9 w-9 items-center justify-center rounded-full bg-teal-50 text-teal-700 dark:bg-teal-950 dark:text-teal-300"><UserRound className="h-4 w-4" /></span>
        <div><label htmlFor="boss-account" className="block text-[10px] text-muted-foreground">当前账号</label><select id="boss-account" aria-label="当前查看账号" value={selected} disabled={saving || syncingConfigs || !!followUp.command} onChange={e => void select(Number(e.target.value))} className="max-w-52 border-0 bg-transparent py-0.5 pr-4 text-sm font-semibold focus:outline-none">{accounts.map(a => <option key={a.id} value={a.id}>{a.name}{a.isLoggedIn ? ' · 已登录' : ' · 待登录'}</option>)}</select></div>
      </div>
    </div>
    {message && <p role="status" className="rounded-lg border border-green-300 p-3 text-sm text-green-700">{message}</p>}
    {error && <p role="alert" className="rounded-lg border border-red-300 p-3 text-sm text-red-600">{error}</p>}
    {syncingConfigs && <p role="status" className="rounded-lg border p-3 text-sm text-muted-foreground">正在同步当前账号的全部配置…</p>}
    <BossInboxOverview accounts={accounts} />
    {selectedAccount ? <div key={`${selected}:${configSyncVersion}`} inert={syncingConfigs || undefined}>{children(selected, queue, selectedAccount.name, management, configSyncVersion ? 'accounts' : 'overview', followUp)}</div> : <div className="rounded-xl border bg-card p-10 text-center text-sm text-muted-foreground">正在读取账号，请确认后端服务已启动。{error && <Button variant="outline" className="ml-3" onClick={() => void refresh()}>重新连接</Button>}</div>}
  </div>
}

export function BossProfileSettings({ accountId, locked }: { accountId: number; locked: boolean }) {
  const [profile, setProfile] = useState({ introduce: '', prompt: '' })
  const [ready, setReady] = useState(false)
  const [retry, setRetry] = useState(0)
  const { saving } = useWorkspaceEdits()
  const [savedProfile, setSavedProfile] = useState<typeof profile | null>(null)
  const [message, setMessage] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    fetch(bossUrl(`/api/boss/accounts/${accountId}/profile`, accountId), { signal: controller.signal, cache: 'no-store' })
      .then(async response => { if (!response.ok) throw new Error('个人资料读取失败'); return response.json() })
      .then(data => { if (!controller.signal.aborted) { setProfile(data); setSavedProfile(data); setReady(true); setMessage('') } })
      .catch(() => { if (!controller.signal.aborted) setMessage('个人资料读取失败，请刷新重试') })
    return () => controller.abort()
  }, [accountId, retry])
  useWorkspaceEditor({
    id: `profile-${accountId}`, label: 'AI 个人资料', ready, validationError: !ready ? message : '',
    dirty: savedProfile !== null && JSON.stringify(profile) !== JSON.stringify(savedProfile),
    reset: () => { if (savedProfile) setProfile(savedProfile) },
    prepare: () => {
      const snapshot = { ...profile }
      return { save: async () => {
        await writeWorkspaceJson(bossUrl(`/api/boss/accounts/${accountId}/profile`, accountId), snapshot)
        setSavedProfile(snapshot)
      } }
    },
  })
  return <Card><CardHeader><CardTitle>当前账号的 AI 个人资料</CardTitle></CardHeader><CardContent className="space-y-3">
    <fieldset disabled={locked || !ready || saving} className="space-y-3 disabled:opacity-60">
      <label className="block text-sm">个人经历与技能介绍<textarea aria-label="当前账号个人介绍" rows={5} value={profile.introduce} onChange={e => setProfile(p => ({ ...p, introduce: e.target.value }))} className="mt-2 w-full rounded-md border bg-background p-3" /></label>
      <details><summary className="cursor-pointer text-sm">AI 提示词模板</summary><textarea aria-label="当前账号 AI 提示词" rows={6} value={profile.prompt} onChange={e => setProfile(p => ({ ...p, prompt: e.target.value }))} className="mt-2 w-full rounded-md border bg-background p-3" /><p className="text-xs text-muted-foreground">沿用原有模板格式，五个 %s 依次代表个人介绍、关键词、岗位名称、岗位描述、默认招呼语。</p></details>
    </fieldset>
    <p className="text-xs text-muted-foreground">此处资料随上方“保存全部修改”统一保存，仅用于当前 Boss 账号。服务地址、密钥和模型在“服务与通知”中设置。</p>
    {message && <div role="alert" className="text-sm text-amber-700">{message}<Button variant="outline" size="sm" className="ml-2" onClick={() => setRetry(value => value + 1)}>重新读取资料</Button></div>}
  </CardContent></Card>
}
