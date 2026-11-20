'use client'

import { useState } from 'react'
import { Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { bossProcessingDateRange } from '@/lib/boss-history'
import { defaultFollowUpDates, defaultFollowUpPolicy, followUpError, type FollowUpPolicy, type FollowUpStatus } from '@/lib/boss-follow-up'

const recentDays = ['今天', '昨天', '前天', '3 天前', '4 天前', '5 天前', '6 天前']

export default function BossFollowUpSettings({ accountId, policy, messages, disabled, status, loadError, runningAccountId,
  onPolicyChange, onMessagesChange }: {
  accountId: number
  policy: FollowUpPolicy
  messages: string
  disabled: boolean
  status: FollowUpStatus | null
  loadError: boolean
  runningAccountId: number | null
  onPolicyChange: (policy: FollowUpPolicy) => void
  onMessagesChange: (messages: string) => void
}) {
  const [date, setDate] = useState('')
  const [today] = bossProcessingDateRange('today')
  const selection = policy.dateSelection
  const canAddDate = !!selection && /^\d{4}-\d{2}-\d{2}$/.test(date) && date <= today
    && !selection.dates.includes(date) && selection.dates.length < 31
  const error = followUpError(policy, messages)

  return <div className="form-columns">
    <Card>
      <CardHeader>
        <CardTitle>跟进范围</CardTitle>
        <CardDescription>按当前账号的首次投递日期查找已投递岗位。</CardDescription>
      </CardHeader>
      <CardContent>
        <fieldset disabled={disabled} className="space-y-4 disabled:opacity-60">
          <div className="space-y-2">
            <Label htmlFor="boss-follow-up-date-mode">筛选方式</Label>
            <Select id="boss-follow-up-date-mode" value={selection ? 'dates' : 'wait'} onChange={({ target }) => {
              onPolicyChange({ ...policy,
                dateSelection: target.value === 'dates' ? defaultFollowUpDates : null,
                waitHours: policy.waitHours || defaultFollowUpPolicy.waitHours,
              })
            }}>
              <option value="dates">按投递日期</option>
              <option value="wait">按首次沟通后的等待时间</option>
            </Select>
          </div>
          {selection ? <div className="space-y-3">
            <div className="flex flex-wrap gap-2" role="group" aria-label="跟进日期，可多选">
              {recentDays.map((label, offset) => {
                const selected = selection.daysAgo.includes(offset)
                return <Button key={label} type="button" size="sm" variant={selected ? 'default' : 'outline'} aria-pressed={selected}
                  onClick={() => onPolicyChange({ ...policy, dateSelection: { ...selection,
                    daysAgo: selected ? selection.daysAgo.filter(day => day !== offset) : [...selection.daysAgo, offset],
                  } })}>{label}</Button>
              })}
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <label className="space-y-1.5 text-sm font-medium">
                <span>指定日期</span>
                <Input type="date" value={date} max={today} onChange={event => setDate(event.target.value)} />
              </label>
              <Button type="button" variant="outline" disabled={!canAddDate} onClick={() => {
                if (!canAddDate) return
                onPolicyChange({ ...policy, dateSelection: { ...selection, dates: [...selection.dates, date].sort() } })
                setDate('')
              }}><Plus />添加日期</Button>
            </div>
            {!!selection.dates.length && <ul className="flex flex-wrap gap-2" aria-label="已添加的指定日期">
              {selection.dates.map(selected => <li key={selected} className="flex items-center gap-1 rounded-md border bg-muted/30 py-0.5 pl-2 text-sm">
                <time dateTime={selected}>{selected}</time>
                <Button type="button" size="icon" variant="ghost" className="h-7 w-7" aria-label={`移除 ${selected}`}
                  onClick={() => onPolicyChange({ ...policy, dateSelection: { ...selection, dates: selection.dates.filter(day => day !== selected) } })}><X /></Button>
              </li>)}
            </ul>}
            <p className="text-xs leading-5 text-muted-foreground">昨天、前天等在每轮开始时重新计算。要持续跟进某批岗位，可以添加固定日期。</p>
          </div> : <label className="block space-y-1.5 text-sm font-medium">
            <span>首次沟通后等待（小时）</span>
            <Input type="number" min={1} max={720} value={policy.waitHours || ''}
              onChange={event => onPolicyChange({ ...policy, waitHours: Number(event.target.value) })} />
          </label>}
          <div className="grid gap-3 border-t pt-4 sm:grid-cols-2">
            <label className="space-y-1.5 text-sm font-medium">
              <span>每轮最多检查岗位数</span>
              <Input type="number" min={1} max={100} value={policy.batchSize || ''}
                onChange={event => onPolicyChange({ ...policy, batchSize: Number(event.target.value) })} />
            </label>
            <label className="space-y-1.5 text-sm font-medium">
              <span>自动检查间隔（小时）</span>
              <Input type="number" min={1} max={720} value={policy.intervalHours || ''}
                onChange={event => onPolicyChange({ ...policy, intervalHours: Number(event.target.value) })} />
            </label>
          </div>
          <label className="flex items-center gap-2 text-sm font-medium">
            <input type="checkbox" role="switch" checked={policy.enabled}
              onChange={event => onPolicyChange({ ...policy, enabled: event.target.checked })} />
            开启定期自动跟进
          </label>
          <p className="text-xs leading-5 text-muted-foreground">手动跟进可随时启动。开启自动跟进后，程序在账号已登录、浏览器空闲时按设定间隔运行。</p>
          {error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{error}</p>}
        </fieldset>
      </CardContent>
    </Card>
    <div className="space-y-5">
      <Card>
        <CardHeader>
          <CardTitle>跟进消息</CardTitle>
          <CardDescription>招聘者从未回复，且距最后一条本人消息超过 12 小时，可以再次跟进。</CardDescription>
        </CardHeader>
        <CardContent>
          <fieldset disabled={disabled} className="space-y-2 disabled:opacity-60">
            <Label htmlFor="repeatChatMessages">每行一条</Label>
            <textarea id="repeatChatMessages" rows={5} value={messages}
              onChange={event => onMessagesChange(event.target.value)} placeholder="填写要发送的跟进文案"
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-7 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
            <p className="text-xs text-muted-foreground">每次发送从非空行中随机选一条。修改后点击上方「保存全部修改」。</p>
          </fieldset>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3"><CardTitle>跟进进度</CardTitle></CardHeader>
        <CardContent aria-live="polite" className="space-y-2 text-sm">
          {loadError && <p className="text-muted-foreground">暂时无法读取跟进状态，正在重试。</p>}
          {runningAccountId !== null && runningAccountId !== accountId && <p className="text-muted-foreground">另一个账号正在跟进。</p>}
          {status?.progress ? <>
            <p className="font-medium">{status.stopping ? '正在停止跟进，等待当前操作结束…' : status.progress.message}</p>
            {status.progress.jobName && <p className="text-muted-foreground">{status.progress.jobName}</p>}
            <p className="text-muted-foreground">已核对 {status.progress.checked}{status.progress.total > 0 && ` / ${status.progress.total}`} 个岗位 · 已确认发送 {status.progress.confirmed} 条</p>
          </> : status?.lastRun ? <>
            <time dateTime={new Date(status.lastRun.startedAt).toISOString()} className="text-xs text-muted-foreground">
              {new Date(status.lastRun.startedAt).toLocaleString('zh-CN', { hour12: false })}
            </time>
            <p>{status.lastRun.message}</p>
          </> : status && <p className="text-muted-foreground">暂无跟进记录</p>}
        </CardContent>
      </Card>
    </div>
  </div>
}
