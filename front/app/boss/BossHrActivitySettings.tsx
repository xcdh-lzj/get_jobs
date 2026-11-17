'use client'

import { useState } from 'react'

export interface HrActivityPolicy { maxDays: number }
export const defaultHrActivityPolicy: HrActivityPolicy = { maxDays: 3 }
const presets = [1, 3, 7, 14, 30]

export default function BossHrActivitySettings({ enabled, policy, onChange }: {
  enabled: boolean; policy?: HrActivityPolicy; onChange: (policy: HrActivityPolicy) => void
}) {
  const [customChosen, setCustomChosen] = useState(false)
  const value = policy || defaultHrActivityPolicy
  const custom = customChosen || !presets.includes(value.maxDays)
  const invalid = !Number.isInteger(value.maxDays) || value.maxDays < 1 || value.maxDays > 365
  return <div className="grid gap-3 rounded-lg border bg-muted/20 p-3 sm:grid-cols-2" aria-label="HR 最近活跃时间设置">
    <label className="block text-sm">允许的最近活跃范围<select aria-label="HR 最近活跃范围" value={custom ? 'custom' : String(value.maxDays)} onChange={event => {
      const next = event.target.value
      setCustomChosen(next === 'custom')
      if (next !== 'custom') onChange({ maxDays: Number(next) })
    }} className="mt-2 block w-full rounded-md border bg-background p-2">
      {presets.map(days => <option key={days} value={days}>近 {days} 天活跃{days === 3 ? '（默认）' : ''}</option>)}
      <option value="custom">自定义天数</option>
    </select></label>
    {custom && <label className="block text-sm">自定义最近活跃天数<input aria-label="自定义 HR 活跃天数" type="number" min={1} max={365} step={1} value={value.maxDays} onChange={event => onChange({ maxDays: Number(event.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>}
    {invalid && <p role="alert" className="text-xs text-amber-700 sm:col-span-2">请输入 1 至 365 的整数天数。</p>}
    <p className="text-xs leading-6 text-muted-foreground sm:col-span-2">{enabled ? `仅放行能确认近 ${value.maxDays} 天内活跃的 HR。` : '当前开关关闭，不按 HR 活跃时间过滤。'}“本周活跃”按 7 天范围、“本月活跃”按 31 天范围核对，范围超过设置则跳过。列表缺少活跃信息时先补读详情，仍未知则自动跳过并继续后续岗位。此筛选不消耗 AI token，点击上方“保存全部修改”后生效。</p>
  </div>
}
