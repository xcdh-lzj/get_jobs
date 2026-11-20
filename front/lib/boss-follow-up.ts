export interface FollowUpPolicy {
  enabled: boolean
  waitHours: number
  intervalHours: number
  batchSize: number
  dateSelection?: { daysAgo: number[]; dates: string[] } | null
}

export const defaultFollowUpDates: NonNullable<FollowUpPolicy['dateSelection']> = { daysAgo: [1], dates: [] }

export const defaultFollowUpPolicy: FollowUpPolicy = {
  enabled: false,
  waitHours: 48,
  intervalHours: 24,
  batchSize: 20,
  dateSelection: defaultFollowUpDates,
}

export interface FollowUpStatus {
  activeAccountId: number | null
  stopping: boolean
  progress: { checked: number; total: number; confirmed: number; message: string; jobName: string | null } | null
  lastRun: { startedAt: number; state: string; checked: number; confirmed: number; unconfirmed: number; message: string } | null
}

export function followUpError(policy: FollowUpPolicy, messages: string): string {
  const within = (value: number, limit: number) => Number.isInteger(value) && value >= 1 && value <= limit
  const selection = policy.dateSelection
  if (selection && !selection.daysAgo.length && !selection.dates.length) return '请选择至少一个跟进日期。'
  if (!selection && !within(policy.waitHours, 720)) return '首次联系后的等待时间须为 1 至 720 小时。'
  if (!within(policy.intervalHours, 720)) return '自动检查间隔须为 1 至 720 小时。'
  if (!within(policy.batchSize, 100)) return '每轮检查上限须为 1 至 100 个岗位。'
  if (policy.enabled && !messages.trim()) return '开启定期跟进时，请至少填写一条跟进消息。'
  return ''
}
