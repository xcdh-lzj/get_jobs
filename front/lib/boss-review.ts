export type ReviewDecision = 'pass' | 'reject' | 'review'
export interface ReviewPolicy {
  enabled: boolean
  goal: string
  targetRoles: string[]
  excludedTitles: string[]
}
export interface ReviewEconomy { enabled: boolean; cacheDays: number; taskTokenLimit: number }
export const defaultReviewEconomy: ReviewEconomy = { enabled: true, cacheDays: 7, taskTokenLimit: 0 }
export interface ReviewOptions { policy: ReviewPolicy; generateGreeting: boolean; economy?: ReviewEconomy }
export interface ReviewUsage {
  tracked: boolean; requests: number; failedRequests: number; unreportedRequests: number
  inputTokens: number; outputTokens: number; totalTokens: number
  reasoningTokens: number | null; cachedInputTokens: number | null; reusedJobs: number
  economyAppliedRequests: number; settings: ReviewEconomy
}
export interface ResumeProposal {
  summary: string
  skills: string[]
  directions: { name: string; reason: string; evidence: string[] }[]
  goal: string
  excludedTitles: string[]
}
export interface ReviewJob {
  id: string; title: string; company: string; city: string; salary: string
  activeStatus?: string
  description: string; detailUrl: string; keyword: string
}
export interface ReviewRow {
  id: number
  result: { job: ReviewJob; decision: ReviewDecision; category: string; stage: string; reason: string; evidence: string[] }
  deliveryState: string
  cached: boolean
}
export interface ReviewRun {
  id: string; mode: 'examples' | 'history' | 'scan' | 'delivery'; state: string
  startedAt: number; finishedAt: number | null; error: string; policy: ReviewPolicy
  total: number; passed: number; rejected: number; pending: number; delivered: number; rows: ReviewRow[]
  usage?: ReviewUsage | null
}
export const reviewDecisionLabels: Record<ReviewDecision, string> = { pass: '通过', reject: '已过滤', review: '自动跳过' }
export const reviewCategoryLabels: Record<string, string> = {
  agent_development: 'Agent 开发', llm_application_development: '大模型应用开发', product: '产品', sales: '销售',
  presales: '售前', operations: '运营', research: '模型研究', other: '其他方向', uncertain: '职责不明确', excluded: '规则排除', basic_unknown: '基础信息未确认',
}
export const reviewModeLabels = { examples: '示例试审', history: '历史岗位试审', scan: '仅采集审核', delivery: '投递前审核' }
export const reviewRunLabels: Record<string, string> = {
  running: '审核中', completed: '已完成', stopped: '已停止', interrupted: '已中断', failed: '执行失败', quota_reached: '账号今日沟通已达上限',
  budget_reached: '审核预算已触发',
}
export function exclusionLines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map(s => s.trim()).filter(Boolean))]
}

// 兼容旧记录的文案，历史数据保持原样；这些结果始终跳过，不等待人工操作。
export function reviewReason(row: ReviewRow): string {
  if (row.result.decision !== 'review') return row.result.reason
  return row.result.reason
    .replace('需人工核对月薪条件', '无法确认月薪条件，已自动跳过')
    .replace('需人工核对', '已自动跳过')
    .replace('保留人工审核', '已自动跳过')
    .replace('保留待审、不自动投递', '已自动跳过')
}
