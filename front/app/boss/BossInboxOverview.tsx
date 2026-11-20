'use client'

import { useEffect, useRef, useState } from 'react'
import { Bell, ExternalLink, Inbox, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { apiUrl } from '@/lib/api'
import { newInboxAlerts, type BossInboxAccount, type BossInboxSnapshot, type InboxState } from '@/lib/boss-inbox'
import { bossUrl, type BossAccount } from '@/lib/boss-accounts'
import { createSSEWithBackoff } from '@/lib/sse'

const states: Record<InboxState, string> = {
  offline: '待登录',
  stopped: '未监听',
  opening: '正在打开浏览器',
  connecting: '连接中',
  watching: '监听中',
  unavailable: '请检查消息页',
  closed: '消息页已关闭',
  stale: '更新暂停',
}

type Command = 'start' | 'stop' | 'open' | 'refresh-unread'
const selectionKey = 'boss-inbox-selection'

async function inboxCommand(command: Command, accountId?: number, selectedAccountIds?: number[]) {
  const path = `/api/boss/inbox/${command}`
  const response = await fetch(accountId === undefined ? apiUrl(path) : bossUrl(path, accountId), {
    method: 'POST',
    ...(command === 'start' && {
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountIds: selectedAccountIds }),
    }),
  })
  const result = await response.json()
  if (!response.ok || result.success === false) throw new Error(result.message || '无法操作 Boss 消息监听')
  return result as { message?: string; enabled?: boolean } & Partial<BossInboxSnapshot>
}

function showUnreadNotification(account: BossInboxAccount, instanceId: string) {
  const storageKey = `boss-inbox-alert-${account.accountId}`
  const alertId = `${instanceId}:${account.notificationId}`
  const show = () => {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
    try { if (localStorage.getItem(storageKey) === alertId) return } catch {}
    try {
      const notification = new Notification(`Boss 新未读 · ${account.name}`, {
        body: `当前有 ${account.unread}${account.capped ? '+' : ''} 个未读会话`,
        tag: `boss-inbox-${account.accountId}`,
      })
      try { localStorage.setItem(storageKey, alertId) } catch {}
      notification.onclick = event => {
        event.preventDefault()
        notification.close()
        void inboxCommand('open', account.accountId).catch(() => { window.focus() })
      }
    } catch {
      // 系统或浏览器拒绝通知时，工作台仍正常显示未读数。
    }
  }
  if (navigator.locks) void navigator.locks.request(storageKey, show).catch(show)
  else show()
}

export default function BossInboxOverview({ accounts }: { accounts: BossAccount[] }) {
  const [snapshot, setSnapshot] = useState<BossInboxSnapshot | null>(null)
  const [selection, setSelection] = useState<number[] | null>(null)
  const [connected, setConnected] = useState(false)
  const [permission, setPermission] = useState<NotificationPermission | 'unsupported' | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null)
  const latest = useRef<BossInboxSnapshot | null>(null)

  useEffect(() => {
    const readPermission = () => setPermission(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
    readPermission()
    queueMicrotask(() => {
      try {
        const saved = JSON.parse(localStorage.getItem(selectionKey) ?? 'null')
        if (Array.isArray(saved) && saved.every(id => Number.isSafeInteger(id) && id > 0)) setSelection(saved)
      } catch {}
    })
    window.addEventListener('focus', readPermission)
    const stream = createSSEWithBackoff(apiUrl('/api/boss/inbox/stream'), {
      listeners: [{
        name: 'inbox',
        handler: event => {
          const next: BossInboxSnapshot = JSON.parse(event.data)
          for (const account of newInboxAlerts(latest.current, next)) showUnreadNotification(account, next.instanceId)
          latest.current = next
          setSnapshot(next)
          setConnected(true)
        },
      }],
      onError: () => setConnected(false),
    })
    return () => {
      stream.close()
      window.removeEventListener('focus', readPermission)
    }
  }, [])

  const enableNotifications = async () => {
    try {
      const selected = await Notification.requestPermission()
      setPermission(selected)
      if (selected === 'granted') setNotice({ error: false, text: '桌面提醒已开启，下次有新增未读时会通知。' })
    } catch {
      setNotice({ error: true, text: '无法开启桌面提醒，请检查浏览器的通知权限。' })
    }
  }

  const execute = async (command: Command, accountId?: number) => {
    setPending(accountId === undefined ? command : `${command}-${accountId}`)
    setNotice(null)
    try {
      const result = await inboxCommand(command, accountId, selectedIds)
      if ((command === 'start' || command === 'stop') && result.accounts) {
        const next = result as BossInboxSnapshot
        latest.current = next
        setSnapshot(next)
      }
      if (result.message && command === 'refresh-unread') setNotice({ error: false, text: result.message })
    } catch (error) {
      setNotice({ error: true, text: error instanceof Error ? error.message : '无法连接消息监听' })
    } finally {
      setPending(null)
    }
  }

  const enabled = snapshot?.enabled ?? false
  const rows: BossInboxAccount[] = snapshot?.accounts ?? accounts.map(account => ({
    accountId: account.id, name: account.name, state: account.isLoggedIn ? 'stopped' : 'offline',
    browserOpen: account.isLoggedIn, unread: null, capped: false, updatedAt: null, notificationId: 0,
  }))
  const allIds = rows.map(account => account.accountId)
  const selectedScope = enabled ? snapshot?.selectedAccountIds : selection
  const selectedIds = selectedScope == null ? allIds : allIds.filter(id => selectedScope.includes(id))
  const allSelected = allIds.length > 0 && selectedIds.length === allIds.length
  const active = connected ? rows.filter(account => account.state === 'watching').length : 0

  const changeSelection = (ids: number[] | null) => {
    setSelection(ids)
    try { localStorage.setItem(selectionKey, JSON.stringify(ids)) } catch {}
  }

  return <section aria-labelledby="boss-inbox-title" className="overflow-hidden rounded-xl border bg-card shadow-sm">
    <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2.5">
      <div className="flex items-center gap-2.5">
        <Inbox className="h-4 w-4 text-teal-600 dark:text-teal-400" aria-hidden="true" />
        <h2 id="boss-inbox-title" className="text-sm font-semibold">Boss 未读消息</h2>
        <span className="text-xs text-muted-foreground">{!connected ? '连接工作台中' : enabled ? `${active}/${selectedIds.length} 个账号监听中` : `已选 ${selectedIds.length} 个账号`}</span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {!enabled && <label className="mr-2 flex items-center gap-1.5 text-xs">
          <input type="checkbox" checked={allSelected} disabled={!connected || !!pending || !allIds.length}
            onChange={event => changeSelection(event.target.checked ? null : [])} />全选账号
        </label>}
        {permission === 'default' && <Button size="sm" variant="ghost" className="h-8" onClick={() => void enableNotifications()}><Bell />开启桌面提醒</Button>}
        {permission === 'granted' && enabled && <span className="flex items-center gap-1.5 px-2 text-xs text-muted-foreground"><Bell className="h-3.5 w-3.5" />桌面提醒已开启</span>}
        <Button size="sm" variant={enabled ? 'outline' : 'default'} className="h-8" disabled={!connected || !!pending || !enabled && !selectedIds.length}
          onClick={() => void execute(enabled ? 'stop' : 'start')}>{enabled ? '停止监听' : pending === 'start' ? '正在启动…' : '开始监听'}</Button>
      </div>
    </div>
    <ul className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,195px),1fr))] gap-2 px-4 py-3">
      {rows.map(account => {
        const state = !connected && account.state === 'watching' ? 'stale' : account.state
        const unread = account.unread === null ? null : `${account.unread}${account.capped ? '+' : ''}`
        const selected = selectedIds.includes(account.accountId)
        const canOpen = connected && account.browserOpen && !pending
        return <li key={account.accountId} className="flex min-w-0 items-stretch rounded-lg border bg-muted/20">
          <input type="checkbox" aria-label={`监听 ${account.name}`} checked={selected}
            title={enabled ? '停止监听后可更改所选账号' : undefined}
            className="ml-2.5 size-4 self-center" disabled={!connected || enabled || !!pending}
            onChange={event => {
              const ids = event.target.checked ? [...selectedIds, account.accountId] : selectedIds.filter(id => id !== account.accountId)
              changeSelection(ids.length === allIds.length ? null : ids)
            }} />
          <button type="button" disabled={!canOpen} className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-2.5 py-2 text-left hover:bg-accent focus-visible:outline-ring focus-visible:outline-2 disabled:cursor-default"
            title={`打开 ${account.name} 的 Boss 消息页`}
            onClick={() => void execute('open', account.accountId)}>
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5"><span className="truncate text-sm font-medium">{account.name}</span><ExternalLink aria-hidden="true" className="h-3 w-3 shrink-0 text-muted-foreground" /></div>
              <div className="text-xs text-muted-foreground">{pending === `open-${account.accountId}` ? '正在打开' : states[state]}</div>
            </div>
            {unread !== null && <span aria-label={`${unread} 个未读会话`} className={`rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums ${state === 'watching' && account.unread ? 'bg-teal-100 text-teal-800 dark:bg-teal-950 dark:text-teal-200' : 'bg-muted text-muted-foreground'}`}>{unread}</span>}
          </button>
          {enabled && selected && account.browserOpen && <Button size="icon" variant="ghost" className="my-auto mr-1 size-7 shrink-0 text-muted-foreground"
            title="重新筛选 Boss 未读列表" aria-label={`刷新 ${account.name} 的未读列表`}
            disabled={!connected || !!pending || state !== 'watching'} onClick={() => void execute('refresh-unread', account.accountId)}>
            <RefreshCw className={pending === `refresh-unread-${account.accountId}` ? 'animate-spin' : undefined} />
          </Button>}
        </li>
      })}
    </ul>
    {notice ? <p role={notice.error ? 'alert' : 'status'} className={`px-4 pb-3 text-xs ${notice.error ? 'text-destructive' : 'text-muted-foreground'}`}>{notice.text}</p>
      : enabled && <p className="px-4 pb-3 text-xs text-muted-foreground">
        {permission === 'denied' ? '浏览器已阻止通知，可在站点权限中开启。'
          : permission === 'unsupported' ? '当前浏览器不支持桌面提醒，仍可在这里查看未读。'
            : '保留工作台和 Boss 消息页，可在其他桌面收到提醒。'}
      </p>}
  </section>
}
