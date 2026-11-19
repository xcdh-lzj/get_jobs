export interface BossHistoryJob {
  id: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  hrPosition?: string
  hrActiveStatus?: string
  deliveryStatus?: string
  deliveryReason?: string
  processedAt?: string
  jobUrl?: string
  recruitmentStatus?: string
  companyAddress?: string
  industry?: string
  introduce?: string
  financingStage?: string
  companyScale?: string
  jobDescription?: string
  createdAt?: string
}

export const BOSS_HISTORY_SORT_OPTIONS = [
  { value: "RECORDED_DESC", label: "最近收录" },
  { value: "PROCESSED_DESC", label: "处理时间 · 最新优先" },
  { value: "PROCESSED_ASC", label: "处理时间 · 最早优先" },
  { value: "SALARY_DESC", label: "月薪 · 从高到低" },
  { value: "SALARY_ASC", label: "月薪 · 从低到高" },
] as const

export type BossHistorySort = (typeof BOSS_HISTORY_SORT_OPTIONS)[number]["value"]

export const BOSS_PROCESSING_DATE_PRESETS = [
  { value: "today", label: "今天" },
  { value: "yesterday", label: "昨天" },
  { value: "previousSevenDays", label: "前 7 天" },
  { value: "previousMonth", label: "前 1 个月" },
  { value: "thisMonth", label: "本月" },
] as const

export type BossProcessingDatePreset = (typeof BOSS_PROCESSING_DATE_PRESETS)[number]["value"]

export function bossProcessingDateRange(preset: BossProcessingDatePreset, reference = new Date()): [string, string] {
  const year = reference.getFullYear()
  const month = reference.getMonth()
  const day = reference.getDate()
  // 先确定本地日历日期，再用 UTC 做日期加减，避免跨时区或夏令时偏移。
  const dateAt = (monthOffset: number, date: number) =>
    new Date(Date.UTC(year, month + monthOffset, date)).toISOString().slice(0, 10)
  const today = dateAt(0, day)
  const yesterday = dateAt(0, day - 1)
  switch (preset) {
    case "today": return [today, today]
    case "yesterday": return [yesterday, yesterday]
    case "previousSevenDays": return [dateAt(0, day - 7), yesterday]
    case "previousMonth": {
      const daysInPreviousMonth = new Date(Date.UTC(year, month, 0)).getUTCDate()
      return [dateAt(-1, Math.min(day, daysInPreviousMonth)), yesterday]
    }
    case "thisMonth": return [dateAt(0, 1), today]
  }
}

export function formatBossProcessingTime(value?: string): string {
  return value?.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:$|[.Z+-])/)?.slice(1, 3).join(' ') ?? ''
}

// Validate the saved detail address without probing it; retain its original query parameters.
export function bossHistoryUrl(value?: string): string | null {
  if (!value || /[\u0000-\u0020\u007f]/.test(value)) return null
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.hostname !== 'www.zhipin.com' || url.username || url.password || url.port) return null
    if (!/^\/job_detail\/[A-Za-z0-9_~=-]{5,180}\.html$/.test(url.pathname)) return null
    return url.href
  } catch { return null }
}

export function bossHistorySearchText(job: Pick<BossHistoryJob, 'companyName' | 'jobName'>): string {
  return [job.companyName, job.jobName].map(value => value?.trim()).filter(Boolean).join(' ')
}
