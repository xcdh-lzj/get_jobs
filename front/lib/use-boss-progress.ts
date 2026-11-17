'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { bossUrl } from './boss-accounts'
import { createSSEWithBackoff } from './sse'
import { isBossTaskStatus, mergeBossStatus, type BossTaskStatus } from './boss-progress'

export function useBossProgress(accountId = 1) {
  const [status, setStatus] = useState<BossTaskStatus | null>(null)
  const [connection, setConnection] = useState<'connecting' | 'live' | 'reconnecting'>('connecting')
  const [error, setError] = useState<string | null>(null)
  const mounted = useRef(false)
  const request = useRef<AbortController | null>(null)

  const applyStatus = useCallback((value: unknown) => {
    if (!mounted.current || !isBossTaskStatus(value) || (value.accountId ?? 1) !== accountId) return false
    setStatus(previous => mergeBossStatus(previous, value))
    setError(null)
    return true
  }, [accountId])

  const refreshStatus = useCallback(async (force = true) => {
    if (request.current) {
      if (!force) return
      request.current.abort()
    }
    const controller = new AbortController()
    request.current = controller
    const timer = window.setTimeout(() => controller.abort(), 8000)
    try {
      const response = await fetch(bossUrl('/api/boss/status', accountId), { signal: controller.signal, cache: 'no-store' })
      if (!response.ok) throw new Error('任务状态暂时不可用')
      const data: unknown = await response.json()
      if (request.current !== controller) return
      if (!applyStatus(data) && mounted.current) setError('后端尚未提供进度信息，请重启更新后的服务')
    } catch {
      if (mounted.current && request.current === controller) setError('无法同步任务状态，请检查后端服务')
    } finally {
      window.clearTimeout(timer)
      if (request.current === controller) request.current = null
    }
  }, [applyStatus, accountId])

  useEffect(() => {
    mounted.current = true
    let active = true
    queueMicrotask(() => { if (active) void refreshStatus() })
    const client = createSSEWithBackoff(bossUrl('/api/boss/stream', accountId), {
      onError: () => { if (active) setConnection('reconnecting') },
      listeners: [
        ...['connected', 'progress', 'status'].map(name => ({
          name,
          handler: (event: MessageEvent) => {
            if (!active) return
            try {
              const payload = JSON.parse(event.data) as { status?: unknown }
              if (applyStatus(payload.status)) setConnection('live')
            } catch { if (mounted.current) setError('收到的进度数据无法解析，正在等待同步') }
          },
        })),
        { name: 'ping', handler: () => { if (active) setConnection('live') } },
      ],
    })
    // 轮询用于补偿丢失的终态推送，也支持不提供 EventSource 的浏览器。
    const poll = window.setInterval(() => { void refreshStatus(false) }, 15000)
    return () => {
      active = false
      mounted.current = false
      client.close()
      window.clearInterval(poll)
      request.current?.abort()
      request.current = null
    }
  }, [applyStatus, refreshStatus, accountId])

  return { status, connection, error, refreshStatus }
}
