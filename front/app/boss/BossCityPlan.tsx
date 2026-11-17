'use client'

import { useState } from 'react'
import { citySequence, defaultCityPolicy, moveCity, type CitySwitchPolicy } from '@/lib/boss-cities'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

export default function BossCityPlan({ rawCities, policy, options, locked, onChange }: {
  rawCities?: string; policy?: CitySwitchPolicy; options: { code: string; name: string }[]; locked: boolean
  onChange: (cities: string, policy: CitySwitchPolicy) => void
}) {
  const [query, setQuery] = useState('')
  const [choice, setChoice] = useState('')
  const cities = citySequence(rawCities), rule = policy || defaultCityPolicy
  const available = options.filter(option => !cities.includes(option.code) && !cities.includes(option.name) && option.name.includes(query.trim()))
  const selected = available.find(option => option.code === choice) || available[0]
  const special = (city: string) => ['0', '100010000', '不限', '全国'].includes(city)
  const change = (next: string[], nextRule = rule) => onChange(`[${next.join(',')}]`, nextRule)
  const invalid = cities.length === 0 || rule.windowSize < 5 || rule.windowSize > 200 || rule.maxNewJobs < 0 || rule.maxNewJobs >= rule.windowSize
  return <Card id="boss-city-plan" aria-label="同账号多城市投递设置">
    <CardHeader><CardTitle>同账号多城市投递 · 按顺序切换</CardTitle><p className="text-sm text-muted-foreground">城市 A → 城市 B → 城市 C；本账号的关键词、薪资、经验、黑名单和简历精投方向沿用。</p></CardHeader>
    <CardContent className="space-y-4"><fieldset disabled={locked} className="space-y-4 disabled:opacity-60">
      <div className="flex flex-wrap gap-2">
        <input aria-label="搜索可添加城市" value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索城市名称" className="rounded-md border bg-background px-3 py-2 text-sm" />
        <select aria-label="待添加城市" value={selected?.code || ''} onChange={e => setChoice(e.target.value)} className="rounded-md border bg-background px-3 py-2 text-sm">{available.map(option => <option key={option.code} value={option.code}>{option.name}</option>)}</select>
        <Button variant="outline" disabled={!selected || cities.length >= 20} onClick={() => selected && change(special(selected.code) ? [selected.code] : [...cities.filter(city => !special(city)), selected.code])}>添加城市</Button>
      </div>
      <ol className="grid max-h-64 gap-2 overflow-y-auto pr-1 sm:grid-cols-2 lg:grid-cols-1">{cities.map((city, index) => <li key={city} className="flex flex-wrap items-center gap-2 rounded-lg border p-3"><span className="min-w-28 text-sm font-medium">{index + 1}. {options.find(option => option.code === city || option.name === city)?.name || city}</span><Button size="sm" variant="outline" aria-label={`上移城市 ${city}`} disabled={index === 0} onClick={() => change(moveCity(cities, index, -1))}>上移</Button><Button size="sm" variant="outline" aria-label={`下移城市 ${city}`} disabled={index === cities.length - 1} onClick={() => change(moveCity(cities, index, 1))}>下移</Button><Button size="sm" variant="outline" aria-label={`移除城市 ${city}`} onClick={() => change(cities.filter(value => value !== city))}>移除</Button></li>)}</ol>
      <p className="text-xs text-muted-foreground">最多 20 城。全国或不限单独使用；添加具体城市会替换全国或不限。保存后按此顺序执行。</p>
      <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={rule.enabled} onChange={e => change(cities, { ...rule, enabled: e.target.checked })} />启用低产出时提前结束搜索</label>
      <div className="grid gap-4 sm:grid-cols-2"><label className="text-sm">观察最近多少个岗位<input aria-label="低产出观察样本数" type="number" min={5} max={200} value={rule.windowSize} disabled={!rule.enabled} onChange={e => change(cities, { ...rule, windowSize: Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label><label className="text-sm">新增可投岗位不超过<input aria-label="新增可投岗位阈值" type="number" min={0} max={rule.windowSize - 1} value={rule.maxNewJobs} disabled={!rule.enabled} onChange={e => change(cities, { ...rule, maxNewJobs: Number(e.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label></div>
      <details><summary className="text-xs">如何判断城市低产出？</summary>      <p className="text-xs leading-6 text-muted-foreground">多城市时，当前关键词最近 {rule.windowSize} 个岗位里，新增可投 ≤ {rule.maxNewJobs} 且没有信息不确定或读取失败，就在本批结束后提前结束该关键词。本城仍有关键词时先尝试剩余关键词，全部结束后进入下一城。样本不足时继续处理；列表耗尽则正常切换。</p>
      <p className="text-xs leading-6 text-muted-foreground">“新增可投”不含本账号已有投递或网站显示已沟通的岗位。仅采集审核时按新匹配岗位统计。每日沟通额度属于账号，额度满或手动停止会结束本账号剩余城市。单城市或关闭提前结束时，按原方式处理完整列表。</p>
</details>
      {invalid && <p role="alert" className="text-sm text-amber-700">至少选择一个城市；样本数为 5–200，新增可投阈值须小于样本数。</p>}

    </fieldset></CardContent>
  </Card>
}
