export interface CitySwitchPolicy { enabled: boolean; windowSize: number; maxNewJobs: number }
export const defaultCityPolicy: CitySwitchPolicy = { enabled: true, windowSize: 30, maxNewJobs: 1 }
export function citySequence(raw?: string): string[] {
  const text = (raw || '').trim().replace(/^\[/, '').replace(/\]$/, '')
  return [...new Set(text.split(/[,，]/).map(value => value.trim().replace(/^"|"$/g, '')).filter(Boolean))]
}
export function moveCity(cities: string[], index: number, offset: number): string[] {
  const target = index + offset
  if (index < 0 || index >= cities.length || target < 0 || target >= cities.length) return cities
  const next = [...cities]; [next[index], next[target]] = [next[target], next[index]]; return next
}
export interface CityProgress {
  currentIndex: number; autoSwitch: boolean; windowSize: number; maxNewJobs: number
  cities: { code: string; name: string; state: string; processed: number; newJobs: number; uncertain: number
    lowYieldKeywords: number; windowCount: number; windowNewJobs: number; windowUncertain: number; reason: string }[]
}
export const cityStateLabels: Record<string, string> = {
  pending: '待执行', running: '处理中', completed: '已完成', low_yield: '低产出结束', stopped: '已停止',
  failed: '执行失败', quota_reached: '账号额度已满', cancelled: '未继续执行',
  budget_reached: '审核预算已触发',
}
