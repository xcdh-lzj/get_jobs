'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { bossUrl } from './boss-accounts'
import type { FollowUpStatus } from './boss-follow-up'

export function useBossFollowUp(accountId: number) {
  const [snapshot, setSnapshot] = useState<{ accountId: number; status: FollowUpStatus } | null>(null)
  const [syncError, setSyncError] = useState<number | null>(null)
  const [command, setCommand] = useState<'start' | 'stop' | null>(null)
  const [commandError, setCommandError] = useState<{ accountId: number; message: string } | null>(null)
  const pending = useRef<AbortController | null>(null)

  const refresh = useCallback(async (force = false) => {
    if (pending.current && !force) return
    pending.current?.abort()
    const request = new AbortController()
    pending.current = request
    const timeout = window.setTimeout(() => request.abort(), 8_000)
    try {
      const response = await fetch(bossUrl('/api/boss/follow-up', accountId), { cache: 'no-store', signal: request.signal })
      if (!response.ok) throw new Error('跟进状态暂时不可用')
      const status: FollowUpStatus = await response.json()
      if (pending.current === request) { setSnapshot({ accountId, status }); setSyncError(null) }
    } catch {
      if (pending.current === request) setSyncError(accountId)
    } finally {
      window.clearTimeout(timeout)
      if (pending.current === request) pending.current = null
    }
  }, [accountId])

  useEffect(() => {
    const initial = window.setTimeout(() => void refresh(), 0)
    const timer = window.setInterval(() => void refresh(), 3_000)
    return () => {
      window.clearTimeout(initial)
      window.clearInterval(timer)
      pending.current?.abort()
      pending.current = null
    }
  }, [refresh])

  const run = async (next: 'start' | 'stop') => {
    if (command) return false
    setCommand(next)
    setCommandError(null)
    const request = new AbortController()
    const timeout = window.setTimeout(() => request.abort(), 8_000)
    try {
      const response = await fetch(bossUrl(`/api/boss/follow-up/${next}`, accountId), { method: 'POST', signal: request.signal })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || '跟进操作失败')
      setSnapshot(previous => {
        const current = previous?.accountId === accountId ? previous.status : null
        return { accountId, status: { activeAccountId: accountId, stopping: next === 'stop',
          progress: next === 'start' ? { checked: 0, total: 0, confirmed: 0, message: '正在准备跟进任务', jobName: null }
            : current?.progress ?? null,
          lastRun: current?.lastRun ?? null,
        } }
      })
      return true
    } catch (error) {
      const message = request.signal.aborted ? '跟进操作等待超时，正在同步任务状态。'
        : error instanceof Error ? error.message : '跟进操作失败'
      setCommandError({ accountId, message })
      return false
    } finally {
      window.clearTimeout(timeout)
      void refresh(true)
      setCommand(null)
    }
  }

  return {
    status: snapshot?.accountId === accountId ? snapshot.status : null,
    activeAccountId: snapshot?.status?.activeAccountId ?? null,
    loadError: syncError === accountId,
    command,
    commandError: commandError?.accountId === accountId ? commandError.message : null,
    run,
  }
}
