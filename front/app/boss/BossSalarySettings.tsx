'use client'

import { customSalaryFromSelection, defaultSalaryPolicy, salaryPolicyError, salaryPolicySummary, type SalaryPolicy } from '@/lib/boss-salary'

export default function BossSalarySettings({ policy, selectedNames, onChange }: {
  policy?: SalaryPolicy; selectedNames: string[]; onChange: (policy: SalaryPolicy) => void
}) {
  const value = policy || defaultSalaryPolicy
  const error = salaryPolicyError(value)
  return <div className="space-y-3 rounded-lg border bg-muted/20 p-3" aria-label="薪资自定义过滤设置">
    <label className="block text-sm">薪资过滤方式<select aria-label="薪资过滤方式" value={value.mode} onChange={e => {
      const range = e.target.value === 'custom' ? customSalaryFromSelection(selectedNames) : defaultSalaryPolicy
      onChange({ ...value, ...range })
    }} className="mt-2 block w-full rounded-md border bg-background p-2">
      <option value="selection">使用薪资档位</option><option value="custom">自定义月薪范围</option>
    </select></label>
    {value.mode === 'custom' && <div className="grid grid-cols-2 gap-3">
      <label className="block text-sm">最低目标月薪（K/月）<input aria-label="最低目标月薪" type="number" min="0.1" max="1000000" step="any" placeholder="留空不限" value={value.minMonthlyK ?? ''} onChange={e => onChange({ ...value, minMonthlyK: e.target.value === '' ? null : Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
      <label className="block text-sm">最高目标月薪（K/月）<input aria-label="最高目标月薪" type="number" min="0.1" max="1000000" step="any" placeholder="留空不限" value={value.maxMonthlyK ?? ''} onChange={e => onChange({ ...value, maxMonthlyK: e.target.value === '' ? null : Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
    </div>}
    <p className="text-sm font-medium" aria-live="polite">{error ? '请修正薪资范围' : salaryPolicySummary(value, selectedNames).replace('当前生效', '筛选范围')}</p>
    <div className="space-y-2 border-t pt-3" role="group" aria-labelledby="boss-posted-salary-title">
      <p id="boss-posted-salary-title" className="text-sm font-medium">岗位薪资上下限（可选）</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block text-sm">岗位最低月薪不得低于（K/月）
          <input type="number" min="0" max="1000000" step="any" placeholder="留空不限" value={value.minPostedMonthlyK ?? ''}
            onChange={e => onChange({ ...value, minPostedMonthlyK: e.target.value === '' ? null : Number(e.target.value) })}
            className="mt-2 block w-full rounded-md border bg-background p-2" />
        </label>
        <label className="block text-sm">岗位最高月薪不得高于（K/月）
          <input type="number" min="0" max="1000000" step="any" placeholder="留空不限" value={value.maxPostedMonthlyK ?? ''}
            onChange={e => onChange({ ...value, maxPostedMonthlyK: e.target.value === '' ? null : Number(e.target.value) })}
            className="mt-2 block w-full rounded-md border bg-background p-2" />
        </label>
      </div>
      <p className="text-xs leading-5 text-muted-foreground">检查岗位标注的月薪起点和上限，等于阈值时可通过。留空的一侧不限。</p>
    </div>
    {error && <p role="alert" className="text-xs text-amber-700">{error}</p>}
    <details><summary className="text-xs">薪资匹配规则</summary><p className="mt-2 text-xs leading-6 text-muted-foreground">1K = 1000 元。自定义月薪范围会转换成 BOSS 网站的搜索档位。精投或设置了岗位薪资上下限时，还会检查岗位月薪与期望区间是否相交，并应用填写的上下限。点击上方“保存全部修改”后对当前账号生效。</p></details>
  </div>
}
