'use client'

import { customSalaryFromSelection, defaultSalaryPolicy, salaryPolicyError, salaryPolicySummary, type SalaryPolicy } from '@/lib/boss-salary'

export default function BossSalarySettings({ policy, selectedNames, onChange }: {
  policy?: SalaryPolicy; selectedNames: string[]; onChange: (policy: SalaryPolicy) => void
}) {
  const value = policy || defaultSalaryPolicy
  const error = salaryPolicyError(value)
  return <div className="space-y-3 rounded-lg border bg-muted/20 p-3" aria-label="薪资自定义过滤设置">
    <label className="block text-sm">薪资过滤方式<select aria-label="薪资过滤方式" value={value.mode} onChange={e => onChange(e.target.value === 'custom' ? customSalaryFromSelection(selectedNames) : defaultSalaryPolicy)} className="mt-2 block w-full rounded-md border bg-background p-2">
      <option value="selection">使用薪资档位</option><option value="custom">自定义月薪范围</option>
    </select></label>
    {value.mode === 'custom' && <div className="grid grid-cols-2 gap-3">
      <label className="block text-sm">最低目标月薪（K/月）<input aria-label="最低目标月薪" type="number" min="0.1" max="1000000" step="any" placeholder="留空不限" value={value.minMonthlyK ?? ''} onChange={e => onChange({ ...value, minMonthlyK: e.target.value === '' ? null : Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
      <label className="block text-sm">最高目标月薪（K/月）<input aria-label="最高目标月薪" type="number" min="0.1" max="1000000" step="any" placeholder="留空不限" value={value.maxMonthlyK ?? ''} onChange={e => onChange({ ...value, maxMonthlyK: e.target.value === '' ? null : Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
    </div>}
    <p className="text-sm font-medium" aria-live="polite">{error ? '请修正薪资范围' : salaryPolicySummary(value, selectedNames).replace('当前生效', '筛选范围')}</p>
    {error && <p role="alert" className="text-xs text-amber-700">{error}</p>}
    <details><summary className="text-xs">薪资匹配规则</summary><p className="mt-2 text-xs leading-6 text-muted-foreground">1K = 1000 元。岗位月薪区间与设置有交集才继续筛选。支持小数；只填一侧即只限制该边界，两侧留空表示不限。自定义时自动调整网站搜索档位以覆盖该范围，本地再按填写的数值筛选。点击上方“保存全部修改”后对当前账号生效。</p></details>
  </div>
}
