'use client'

import { useState, type ReactNode } from 'react'
import { Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { followUpError, type FollowUpPolicy } from '@/lib/boss-follow-up'

const daysAgoLabels = ['今天', '昨天', '前天', '3 天前', '4 天前', '5 天前', '6 天前']
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const numbers = (length: number) => Array.from({ length }, (_, number) => String(number).padStart(2, '0'))
const hours = numbers(24)
const minutes = numbers(60)

function SelectedValue({ children, removeLabel, onRemove }: { children: ReactNode; removeLabel: string; onRemove: () => void }) {
  return <li className="flex items-center gap-1 rounded-md border bg-muted/30 py-0.5 pl-2 text-sm tabular-nums">
    {children}<Button type="button" size="icon" variant="ghost" className="h-7 w-7" aria-label={removeLabel}
      onClick={onRemove}><X /></Button>
  </li>
}

export default function BossFollowUpSettings({ policy, messages, disabled, timeZone, onPolicyChange, onMessagesChange }: {
  policy: FollowUpPolicy
  messages: string
  disabled: boolean
  timeZone?: string
  onPolicyChange: (policy: FollowUpPolicy) => void
  onMessagesChange: (messages: string) => void
}) {
  const [date, setDate] = useState('')
  const [hour, setHour] = useState('10')
  const [minute, setMinute] = useState('00')
  const { schedule, dateSelection } = policy
  const time = `${hour}:${minute}`
  const canAddDate = /^\d{4}-\d{2}-\d{2}$/.test(date) && !dateSelection.dates.includes(date)
  const error = followUpError(policy, messages)
  const updateSchedule = (patch: Partial<FollowUpPolicy['schedule']>) =>
    onPolicyChange({ ...policy, schedule: { ...schedule, ...patch } })
  const updateDates = (patch: Partial<FollowUpPolicy['dateSelection']>) =>
    onPolicyChange({ ...policy, dateSelection: { ...dateSelection, ...patch } })

  return <div className="form-columns">
    <Card>
      <CardHeader>
        <CardTitle>执行计划</CardTitle>
        <CardDescription>按{timeZone === 'Asia/Shanghai' ? '北京时间' : timeZone ? `服务时间（${timeZone}）` : '本机服务时间'}触发。</CardDescription>
      </CardHeader>
      <CardContent>
        <fieldset disabled={disabled} className="space-y-4 disabled:opacity-60">
          <label className="flex items-center gap-2 text-sm font-medium">
            <input type="checkbox" role="switch" checked={policy.enabled}
              onChange={event => onPolicyChange({ ...policy, enabled: event.target.checked })} />
            开启定时跟进
          </label>
          <div className="space-y-2">
            <Label htmlFor="boss-follow-up-frequency">执行频次</Label>
            <Select id="boss-follow-up-frequency" value={schedule.frequency}
              onChange={event => updateSchedule({ frequency: event.target.value === 'weekly' ? 'weekly' : 'daily' })}>
              <option value="daily">每天</option>
              <option value="weekly">每周指定星期</option>
            </Select>
          </div>
          {schedule.frequency === 'weekly' && <div className="flex flex-wrap gap-2" role="group" aria-label="每周执行的星期">
            {weekdays.map((label, index) => {
              const day = index + 1
              const selected = schedule.weekdays.includes(day)
              return <Button key={day} type="button" size="sm" variant={selected ? 'default' : 'outline'} aria-pressed={selected}
                onClick={() => updateSchedule({ weekdays: selected ? schedule.weekdays.filter(value => value !== day)
                  : [...schedule.weekdays, day].sort((a, b) => a - b) })}>{label}</Button>
            })}
          </div>}
          <div className="space-y-2 border-t pt-4">
            <p className="text-sm font-medium">执行时间 <span className="text-xs font-normal text-muted-foreground">24 小时制，可添加多个</span></p>
            <div className="flex flex-wrap items-end gap-2">
              <div className="grid gap-1.5">
                <Label htmlFor="boss-follow-up-hour">小时</Label>
                <Select id="boss-follow-up-hour" value={hour} onChange={event => setHour(event.target.value)} className="w-20">
                  {hours.map(value => <option key={value}>{value}</option>)}
                </Select>
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="boss-follow-up-minute">分钟</Label>
                <Select id="boss-follow-up-minute" value={minute} onChange={event => setMinute(event.target.value)} className="w-20">
                  {minutes.map(value => <option key={value}>{value}</option>)}
                </Select>
              </div>
              <Button type="button" variant="outline" disabled={schedule.times.includes(time)}
                onClick={() => updateSchedule({ times: [...schedule.times, time].sort() })}><Plus />添加时间</Button>
            </div>
            {!!schedule.times.length && <ul className="flex flex-wrap gap-2 pt-1" aria-label="已选执行时间">
              {schedule.times.map(value => <SelectedValue key={value} removeLabel={`移除执行时间 ${value}`}
                onRemove={() => updateSchedule({ times: schedule.times.filter(item => item !== value) })}>{value}</SelectedValue>)}
            </ul>}
          </div>
          <p className="text-xs leading-5 text-muted-foreground">保存后按设定时刻排队，逐一核对所选日期的全部候选岗位。</p>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </fieldset>
      </CardContent>
    </Card>
    <div className="space-y-5">
      <Card>
        <CardHeader>
          <CardTitle>跟进的投递日期</CardTitle>
          <CardDescription>按首次投递的日历日期筛选，可多选。</CardDescription>
        </CardHeader>
        <CardContent>
          <fieldset disabled={disabled} className="space-y-3 disabled:opacity-60">
            <div className="flex flex-wrap gap-2" role="group" aria-label="相对投递日期">
              {daysAgoLabels.map((label, day) => {
                const selected = dateSelection.daysAgo.includes(day)
                return <Button key={day} type="button" size="sm" variant={selected ? 'default' : 'outline'} aria-pressed={selected}
                  onClick={() => updateDates({ daysAgo: selected ? dateSelection.daysAgo.filter(offset => offset !== day)
                    : [...dateSelection.daysAgo, day].sort((a, b) => a - b) })}>{label}</Button>
              })}
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <div className="grid gap-1.5">
                <Label htmlFor="boss-follow-up-date">指定投递日期</Label>
                <Input id="boss-follow-up-date" type="date" value={date} onChange={event => setDate(event.target.value)} />
              </div>
              <Button type="button" variant="outline" disabled={!canAddDate} onClick={() => {
                if (!canAddDate) return
                updateDates({ dates: [...dateSelection.dates, date].sort() })
                setDate('')
              }}><Plus />添加日期</Button>
            </div>
            {!!dateSelection.dates.length && <ul className="flex flex-wrap gap-2" aria-label="已选指定日期">
              {dateSelection.dates.map(value => <SelectedValue key={value} removeLabel={`移除投递日期 ${value}`}
                onRemove={() => updateDates({ dates: dateSelection.dates.filter(item => item !== value) })}>
                <time dateTime={value}>{value}</time>
              </SelectedValue>)}
            </ul>}
            <p className="text-xs leading-5 text-muted-foreground">「昨天」「前天」随计划日期滚动。指定日期固定，可和它们一起选。</p>
          </fieldset>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>跟进消息</CardTitle>
          <CardDescription>招聘者从未回复，且距最后一条本人消息超过 12 小时，可以再次跟进。</CardDescription>
        </CardHeader>
        <CardContent>
          <fieldset disabled={disabled} className="space-y-2 disabled:opacity-60">
            <Label htmlFor="repeatChatMessages">每行一条</Label>
            <textarea id="repeatChatMessages" rows={4} value={messages}
              onChange={event => onMessagesChange(event.target.value)} placeholder="填写要发送的跟进文案"
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-7 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" />
            <p className="text-xs text-muted-foreground">每次随机发送一条非空文案。配置完成后点击「保存全部修改」。</p>
          </fieldset>
        </CardContent>
      </Card>
    </div>
  </div>
}
