export interface FollowUpPolicy {
  enabled: boolean
  schedule: { frequency: 'daily' | 'weekly'; weekdays: number[]; times: string[] }
  dateSelection: { daysAgo: number[]; dates: string[] }
}

export const defaultFollowUpPolicy: FollowUpPolicy = {
  enabled: false,
  schedule: { frequency: 'daily', weekdays: [], times: [] },
  dateSelection: { daysAgo: [1], dates: [] },
}

export interface FollowUpRun {
  id: number
  accountId: number
  trigger: 'scheduled' | 'manual' | 'legacy'
  scheduledAt: number | null
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  state: string
  dates: string[]
  total: number
  checked: number
  confirmed: number
  unconfirmed: number
  failed: number
  message: string
}

export interface FollowUpStatus {
  activeAccountId: number | null
  activeRunId: number | null
  stopping: boolean
  progress: { checked: number; total: number; confirmed: number; unconfirmed: number; failed: number; message: string; jobName: string | null } | null
  lastRun: FollowUpRun | null
  recentRuns: FollowUpRun[]
  nextScheduledAt: number | null
  timeZone: string
}

export function followUpError(policy: FollowUpPolicy, messages: string): string {
  const { dateSelection, schedule } = policy
  if (!dateSelection.daysAgo.length && !dateSelection.dates.length) return '请选择至少一个跟进日期。'
  if (schedule.times.some(time => !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time))) return '请选择有效的 24 小时制时间。'
  if (policy.enabled && !schedule.times.length) return '请选择至少一个执行时间。'
  if (policy.enabled && schedule.frequency === 'weekly' && !schedule.weekdays.length) return '请选择每周执行的星期。'
  if (policy.enabled && !messages.trim()) return '开启定时跟进时，请至少填写一条跟进消息。'
  return ''
}

export const followUpStateLabels: Record<string, string> = {
  queued: '等待浏览器', running: '跟进中', completed: '已完成', partial: '部分失败',
  failed: '执行失败', stopped: '已停止', interrupted: '已中断', skipped: '已取消', quota_reached: '已达沟通上限',
}

export function followUpTime(timestamp: number, timeZone?: string): string {
  const options: Intl.DateTimeFormatOptions = {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }
  try { return new Date(timestamp).toLocaleString('zh-CN', { ...options, timeZone }) }
  catch { return new Date(timestamp).toLocaleString('zh-CN', options) }
}
