import type { BossQueue } from './boss-accounts'
import type { CityProgress } from './boss-cities'
export type BossTaskState = 'idle' | 'starting' | 'running' | 'stopping' | 'completed' | 'stopped' | 'failed' | 'quota_reached' | 'budget_reached'

export interface BossProgressLog {
  id: number
  type: string
  message: string
  timestamp: number
}

export interface BossTaskProgress {
  instanceId: string
  revision: number
  taskId: string | null
  state: BossTaskState
  isRunning: boolean
  startedAt: number | null
  finishedAt: number | null
  updatedAt: number
  elapsedMillis: number
  message: string
  city: string | null
  keyword: string | null
  jobName: string | null
  companyName: string | null
  current: number
  total: number
  processedCount: number
  deliveredCount: number
  skippedCount: number
  logs: BossProgressLog[]
  cityProgress?: CityProgress | null
}

export interface BossTaskStatus {
  accountId?: number
  busy?: boolean
  locked?: boolean
  queue?: BossQueue
  platform: string
  isRunning: boolean
  isLoggedIn: boolean
  progress: BossTaskProgress
}

export function isBossTaskStatus(value: unknown): value is BossTaskStatus {
  if (!value || typeof value !== 'object') return false
  const status = value as Partial<BossTaskStatus>
  const progress = status.progress
  return typeof status.isRunning === 'boolean' && typeof status.isLoggedIn === 'boolean'
    && !!progress && typeof progress.instanceId === 'string' && typeof progress.revision === 'number'
    && ['idle', 'starting', 'running', 'stopping', 'completed', 'stopped', 'failed', 'quota_reached', 'budget_reached'].includes(progress.state)
    && Array.isArray(progress.logs)
}

/** 避免慢轮询/旧连接的响应覆盖更新的推送；后端重启后接受新的实例。 */
export function mergeBossStatus(previous: BossTaskStatus | null, next: BossTaskStatus): BossTaskStatus {
  if (previous?.accountId === next.accountId && previous?.queue?.instanceId === next.queue?.instanceId
    && previous?.queue && next.queue && previous.queue.revision > next.queue.revision) return previous
  if (previous?.accountId === next.accountId && previous?.progress.instanceId === next.progress.instanceId
    && previous.progress.revision > next.progress.revision) return previous
  return next
}

export function batchProgressPercent(progress: Pick<BossTaskProgress, 'current' | 'total'>): number | null {
  return progress.total > 0
    ? Math.max(0, Math.min(100, Math.round(progress.current / progress.total * 100)))
    : null
}

export function bossHistoryProgressKey(progress?: BossTaskProgress): string {
  if (!progress) return ''
  return [progress.instanceId, progress.taskId, progress.state, progress.processedCount].join('/')
}
