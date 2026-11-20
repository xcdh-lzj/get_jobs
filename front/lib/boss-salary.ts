export interface SalaryPolicy {
  mode: 'selection' | 'custom'
  minMonthlyK: number | null
  maxMonthlyK: number | null
  minPostedMonthlyK?: number | null
  maxPostedMonthlyK?: number | null
}
export const defaultSalaryPolicy: SalaryPolicy = { mode: 'selection', minMonthlyK: null, maxMonthlyK: null }

const invalidAmount = (amount: number | null | undefined) =>
  amount != null && (!Number.isFinite(amount) || amount <= 0 || amount > 1_000_000)

export function salaryPolicyError(policy: SalaryPolicy): string {
  const { minPostedMonthlyK, maxPostedMonthlyK } = policy
  if (invalidAmount(minPostedMonthlyK) || invalidAmount(maxPostedMonthlyK)) return '岗位薪资上下限须为大于 0 的数值，单位 K/月。'
  if (minPostedMonthlyK != null && maxPostedMonthlyK != null && minPostedMonthlyK > maxPostedMonthlyK) return '岗位最低月薪的限制不能高于最高月薪的限制。'
  if (policy.mode !== 'custom') return ''
  if (invalidAmount(policy.minMonthlyK) || invalidAmount(policy.maxMonthlyK)) return '自定义月薪须为大于 0 的数值，单位 K/月。'
  if (policy.minMonthlyK !== null && policy.maxMonthlyK !== null && policy.minMonthlyK > policy.maxMonthlyK) return '最低月薪不能高于最高月薪。'
  return ''
}

// 切换到自定义时从当前可见档位填入初值，不使用旧版隐藏的上下限。
export function customSalaryFromSelection(names: string[]): SalaryPolicy {
  if (!names.length || names.includes('不限')) return { mode: 'custom', minMonthlyK: null, maxMonthlyK: null }
  const ranges = names.map(name => {
    const match = name.match(/^(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?K(以下|以上)?$/i)
    if (!match) return { min: null, max: null }
    return { min: match[3] === '以下' ? null : Number(match[1]), max: match[3] === '以上' ? null : Number(match[2] || match[1]) }
  })
  return { mode: 'custom', minMonthlyK: ranges.some(r => r.min === null) ? null : Math.min(...ranges.map(r => r.min!)), maxMonthlyK: ranges.some(r => r.max === null) ? null : Math.max(...ranges.map(r => r.max!)) }
}

export function salaryPolicySummary(policy: SalaryPolicy, names: string[]): string {
  if (policy.mode === 'selection') return `当前生效：${names.length ? names.join('、') : '不限'}`
  if (policy.minMonthlyK === null && policy.maxMonthlyK === null) return '当前生效：不限月薪'
  return `当前生效：${policy.minMonthlyK === null ? '下限不限' : `${policy.minMonthlyK}K`} ～ ${policy.maxMonthlyK === null ? '上限不限' : `${policy.maxMonthlyK}K`} / 月`
}
