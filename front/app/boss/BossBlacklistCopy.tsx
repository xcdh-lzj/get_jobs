'use client'

import { useEffect, useRef, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { bossUrl, type BossAccount } from '@/lib/boss-accounts'
import { Button } from '@/components/ui/button'

interface BlacklistItem { id: number; type: string; value: string }
interface CopyResult {
  sourceAccountId: number; sourceAccountName: string; targetAccountId: number; targetAccountName: string
  sourceCount: number; copiedCount: number; skippedCount: number; blacklist: BlacklistItem[]
}
const categories = [['company', '公司'], ['job', '岗位'], ['recruiter', 'HR']] as const

export default function BossBlacklistCopy({ accountId, locked, pending, onPendingChange, onCopied }: {
  accountId: number; locked: boolean; pending: boolean; onPendingChange: (pending: boolean) => void;
  onCopied: (items: BlacklistItem[]) => void
}) {
  const [accounts, setAccounts] = useState<BossAccount[]>([])
  const [sourceId, setSourceId] = useState<number | null>(null)
  const [types, setTypes] = useState<string[]>(categories.map(([type]) => type))
  const [loading, setLoading] = useState(true)
  const [reload, setReload] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const command = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetch(`${API_BASE}/api/boss/accounts`, { signal: controller.signal, cache: 'no-store' })
      .then(async response => { if (!response.ok) throw new Error('账号列表读取失败'); return response.json() })
      .then((items: BossAccount[]) => { if (!controller.signal.aborted) { setAccounts(items); setError('') } })
      .catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '无法读取来源账号') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); command.current?.abort() }
  }, [accountId, reload])

  const available = accounts.filter(account => account.id !== accountId)
  const source = available.find(account => account.id === sourceId) || available[0]
  const target = accounts.find(account => account.id === accountId)
  const copy = async () => {
    if (!source || !types.length || pending || locked) return
    const controller = new AbortController(); command.current = controller
    onPendingChange(true); setError(''); setMessage('')
    try {
      const response = await fetch(bossUrl('/api/boss/config/blacklist/copy', accountId), {
        method: 'POST', signal: controller.signal, headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sourceAccountId: source.id, types }),
      })
      const data = await response.json()
      if (!response.ok) throw new Error(data.message || data.detail || '复制黑名单失败')
      if (controller.signal.aborted) return
      const result = data as CopyResult
      onCopied(result.blacklist)
      setMessage(`已从“${result.sourceAccountName}”（ID ${result.sourceAccountId}）合并到“${result.targetAccountName}”（ID ${result.targetAccountId}）：来源 ${result.sourceCount} 条有效去重项，新增 ${result.copiedCount} 条，已存在跳过 ${result.skippedCount} 条。`)
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '复制失败，请重试')
    } finally {
      if (!controller.signal.aborted) onPendingChange(false)
      if (command.current === controller) command.current = null
    }
  }

  return <section aria-label="从其他账号复制黑名单" className="space-y-3 rounded-xl border bg-muted/20 p-4">
    <div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-semibold">从其他账号复制黑名单</p><span className="text-xs text-muted-foreground">合并到：{target?.name || '当前账号'}（ID {accountId}）</span></div>
    <p className="text-xs leading-6 text-muted-foreground">选择来源账号和分类，保留当前账号已有条目，相同分类和关键词自动去重。复制后各账号独立维护。</p>
    <fieldset disabled={locked || pending || loading} className="space-y-3 disabled:opacity-60">
      <label className="block text-sm">来源账号<select aria-label="黑名单来源账号" value={source?.id || ''} onChange={event => { setSourceId(Number(event.target.value)); setMessage('') }} className="mt-2 w-full rounded-md border bg-background p-2 text-sm">
        {!available.length && <option value="">{loading ? '正在读取账号…' : '暂无其他账号'}</option>}
        {available.map(account => <option key={account.id} value={account.id}>{account.name}（ID {account.id}）</option>)}
      </select></label>
      <div className="flex flex-wrap gap-4">{categories.map(([type, name]) => <label key={type} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={types.includes(type)} onChange={event => { setTypes(previous => event.target.checked ? [...previous, type] : previous.filter(value => value !== type)); setMessage('') }} />复制{name}黑名单</label>)}</div>
      <Button onClick={() => void copy()} disabled={!source || !types.length}>{pending ? '正在复制…' : '复制并合并到当前账号'}</Button>
      {!types.length && <p className="text-xs text-amber-700">请至少勾选一类黑名单。</p>}
    </fieldset>
    {!loading && !error && !available.length && <p className="text-xs text-muted-foreground">添加第二个 Boss 账号后，可在这里复制黑名单。</p>}
    {message && <p role="status" className="text-sm leading-6 text-emerald-700 dark:text-emerald-300">{message}</p>}
    {error && <div role="alert" className="space-y-2 text-sm text-amber-700"><p>{error}</p><Button size="sm" variant="outline" disabled={pending} onClick={() => setReload(value => value + 1)}>刷新账号列表</Button></div>}
  </section>
}
