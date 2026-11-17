import { API_BASE } from './api'

export interface BossAccount {
  id: number
  name: string
  defaultAccount: boolean
  isLoggedIn: boolean
}
export interface BossQueueItem {
  accountId: number
  accountName: string
  state: string
  deliveredCount: number
  message: string
}
export interface BossQueue {
  instanceId: string
  revision: number
  mode: 'single' | 'queue' | 'review_only'
  state: string
  isRunning: boolean
  activeAccountId: number | null
  message: string
  items: BossQueueItem[]
}
export function bossUrl(path: string, accountId: number): string {
  const [pathname, query = ''] = path.split('?')
  const params = new URLSearchParams(query)
  params.set('accountId', String(accountId))
  return `${API_BASE}${pathname}?${params}`
}
export function moveQueueAccount(ids: number[], index: number, offset: number): number[] {
  const target = index + offset
  if (index < 0 || index >= ids.length || target < 0 || target >= ids.length) return ids
  const result = [...ids]
  ;[result[index], result[target]] = [result[target], result[index]]
  return result
}
export const queueStateLabel: Record<string, string> = {
  idle: '未启动', pending: '等待执行', starting: '准备中', running: '执行中', stopping: '停止中',
  completed: '已完成', stopped: '已停止', failed: '失败', cancelled: '未执行',
  quota_reached: '今日沟通已达上限',
  budget_reached: '审核预算已触发',
}
