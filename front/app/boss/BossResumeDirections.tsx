'use client'

import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { bossUrl } from '@/lib/boss-accounts'
import type { ResumeProposal } from '@/lib/boss-review'
import { Button } from '@/components/ui/button'

export default function BossResumeDirections({ accountId, disabled, onAdopt }: {
  accountId: number; disabled: boolean; onAdopt: (proposal: ResumeProposal) => void
}) {
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState('')
  const [editing, setEditing] = useState(false)
  const [notices, setNotices] = useState<string[]>([])
  const [proposal, setProposal] = useState<ResumeProposal | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [pending, setPending] = useState<'extract' | 'analyze' | null>(null)
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const input = useRef<HTMLInputElement | null>(null)
  useEffect(() => () => { const current = request.current; request.current = null; current?.abort() }, [])

  const call = async (type: 'extract' | 'analyze', body: FormData | string) => {
    const controller = new AbortController(); request.current?.abort(); request.current = controller
    setPending(type); setError(''); setProposal(null)
    const timer = window.setTimeout(() => controller.abort(), 45000)
    try {
      const response = await fetch(bossUrl(`/api/boss/review/resume/${type}`, accountId), {
        method: 'POST', body, signal: controller.signal,
        headers: typeof body === 'string' ? { 'Content-Type': 'application/json' } : undefined,
      })
      const data = await response.json()
      if (!response.ok || data.success === false) throw new Error(data.message || data.detail || '简历处理失败')
      if (controller.signal.aborted) return
      if (type === 'extract') { setText(data.text); setFileName(data.fileName); setNotices(data.notices); setEditing(true) }
      else { const result = data as ResumeProposal; setProposal(result); setSelected(result.directions.map(d => d.name)) }
    } catch (e) {
      if (request.current === controller) setError(controller.signal.aborted ? '请求已停止或超时，可以重试；当前审核设置未改变' : e instanceof Error ? e.message : '简历处理失败')
    } finally {
      window.clearTimeout(timer)
      if (request.current === controller) { request.current = null; setPending(null) }
    }
  }
  const upload = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]; event.target.value = ''
    if (!file) return
    setProposal(null)
    if (file.size > 10 * 1024 * 1024) { setError('简历不能超过 10MB'); return }
    const body = new FormData(); body.append('file', file); void call('extract', body)
  }
  return <div className="space-y-4 rounded-xl border border-blue-200 bg-blue-50/30 p-4 dark:border-blue-900 dark:bg-blue-950/20">
    <div><p className="font-medium">从简历生成精投方向</p><p className="mt-1 text-xs leading-6 text-muted-foreground">先在本机提取文字，检查后再交给当前配置的 AI 服务分析技能与项目经历。原文件不长期保存；采用建议后填入表单，统一保存后生效。</p></div>
    <input ref={input} type="file" accept=".pdf,.docx,.txt,.md" aria-label="上传用于分析精投方向的简历" onChange={upload} disabled={disabled || pending !== null} className="hidden" />
    <div className="flex flex-wrap gap-2">
      <Button variant="outline" disabled={disabled || pending !== null} onClick={() => input.current?.click()}>{pending === 'extract' ? '正在提取文字…' : '上传简历并提取文字'}</Button>
      <Button variant="outline" disabled={disabled || pending !== null} onClick={() => setEditing(true)}>粘贴简历文字</Button>
      <span className="self-center text-xs text-muted-foreground">PDF、DOCX、TXT、MD · 最大 10MB · 扫描件需先识别文字</span>
    </div>
    {editing && <div className="space-y-3">
      <label className="block text-sm font-medium">用于分析的技能与项目经历{fileName && <span className="ml-2 font-normal text-muted-foreground">{fileName}</span>}<textarea aria-label="用于分析的简历文字" rows={7} maxLength={30000} disabled={disabled || pending !== null} value={text} onChange={e => { setText(e.target.value); setProposal(null) }} placeholder="可粘贴技能、项目、工作经历和求职意向；分析前请检查并删去不需要发送的个人信息。" className="mt-2 w-full rounded-md border bg-background p-3 text-sm font-normal leading-6" /></label>
      {notices.map(notice => <p key={notice} className="text-xs text-muted-foreground">{notice}</p>)}
      <div className="flex flex-wrap items-center gap-3"><Button disabled={disabled || pending !== null || text.trim().length < 80} onClick={() => void call('analyze', JSON.stringify({ text }))}>{pending === 'analyze' ? '正在分析技能与项目…' : '分析简历，生成方向建议'}</Button><span className="text-xs text-muted-foreground">{text.length} / 30000 字 · 需要至少 80 字经历</span></div>
    </div>}
    {error && <p role="alert" className="text-sm text-amber-800 dark:text-amber-200">{error}</p>}
    {proposal && <div className="space-y-3" aria-label="根据简历生成的方向建议">
      <p className="text-sm leading-6">{proposal.summary}</p>
      <div className="flex flex-wrap gap-2">{proposal.skills.map((skill, index) => <span key={`${skill}-${index}`} className="rounded-full bg-blue-100 px-3 py-1 text-xs text-blue-900 dark:bg-blue-900 dark:text-blue-100">{skill}</span>)}</div>
      {proposal.directions.map(direction => <article key={direction.name} className="space-y-2 rounded-lg border bg-background/70 p-3"><label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" checked={selected.includes(direction.name)} disabled={disabled} onChange={e => setSelected(previous => e.target.checked ? [...previous, direction.name] : previous.filter(name => name !== direction.name))} />{direction.name}</label><p className="text-sm leading-6">{direction.reason}</p><details><summary className="cursor-pointer text-xs text-muted-foreground">查看简历依据</summary><ul className="mt-2 space-y-1 text-sm text-muted-foreground">{direction.evidence.map((quote, index) => <li key={index}>“{quote}”</li>)}</ul></details></article>)}
      <details><summary className="cursor-pointer text-sm">查看将用于 JD 审核的目标职责</summary><p className="mt-2 whitespace-pre-wrap text-sm leading-6">{proposal.goal}</p></details>
      <Button disabled={disabled || pending !== null || selected.length === 0} onClick={() => onAdopt({ ...proposal, directions: proposal.directions.filter(direction => selected.includes(direction.name)) })}>填入所选方向并启用审核</Button>
      <p className="text-xs text-muted-foreground">填入后点击上方“保存全部修改”，将用于当前账号的 JD 职责匹配；保存前仍可编辑。建议优先遵循简历中的求职意向，其他经历用于补充能力依据。</p>
    </div>}
  </div>
}
