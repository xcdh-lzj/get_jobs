'use client'

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Copy, FileText, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { bossHistorySearchText, bossHistoryUrl, type BossHistoryJob } from '@/lib/boss-history'

/** A local snapshot: opening a record must never contact Boss or launch another tab. */
export default function BossJobRecordDialog({ job, onClose }: { job: BossHistoryJob; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const copyArea = useRef<HTMLTextAreaElement>(null)
  const [copyValue, setCopyValue] = useState('')
  const [notice, setNotice] = useState('')
  const searchText = bossHistorySearchText(job)
  const originalUrl = bossHistoryUrl(job.jobUrl)

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    element.showModal()
    return () => { element.close(); document.body.style.overflow = previousOverflow }
  }, [])

  const copy = async (value: string, label: string) => {
    setNotice(''); setCopyValue(value)
    try {
      await navigator.clipboard.writeText(value)
      setNotice(`${label}已复制。`)
    } catch {
      // A visible fallback also works when clipboard permissions are unavailable.
      requestAnimationFrame(() => { copyArea.current?.focus(); copyArea.current?.select() })
      setNotice('浏览器未允许自动复制，已选中文字，可按 ⌘C / Ctrl+C 复制。')
    }
  }

  return createPortal(<dialog
    ref={dialog}
    aria-labelledby="boss-record-title"
    aria-describedby="boss-record-description"
    onCancel={event => { event.preventDefault(); onClose() }}
    className="m-auto max-h-[90dvh] w-[calc(100%_-_2rem)] max-w-4xl overflow-y-auto rounded-2xl border bg-card p-0 text-foreground shadow-2xl backdrop:bg-slate-950/45 backdrop:backdrop-blur-sm"
  >
    <header className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b bg-card p-5">
      <div><p className="mb-1 flex items-center gap-1.5 text-xs font-medium text-primary"><FileText className="h-3.5 w-3.5" />本地岗位记录</p><h2 id="boss-record-title" className="text-lg font-semibold leading-7">{job.jobName || '未命名岗位'}</h2><p id="boss-record-description" className="mt-1 text-xs leading-5 text-muted-foreground">显示采集时已保存的信息，不访问 Boss 网站，不刷新原网页。</p></div>
      <Button type="button" size="icon" variant="ghost" autoFocus aria-label="关闭岗位详情" onClick={onClose}><X className="h-4 w-4" /></Button>
    </header>
    <div className="space-y-5 p-5">
      <dl className="grid grid-cols-2 gap-x-5 gap-y-4 rounded-xl border bg-muted/20 p-4 sm:grid-cols-3">
        {[
          ['公司', job.companyName], ['薪资', job.salary], ['地点', job.location],
          ['经验', job.experience], ['学历', job.degree], ['投递状态', job.deliveryStatus],
        ].map(([label, value]) => <div key={label}><dt className="mb-1 text-xs text-muted-foreground">{label}</dt><dd className="break-words text-sm font-medium">{value || '未记录'}</dd></div>)}
      </dl>
      <section aria-labelledby="boss-record-jd"><div className="mb-3 flex flex-wrap items-center justify-between gap-2"><h3 id="boss-record-jd" className="font-semibold">已保存的岗位职责与要求</h3><Button type="button" size="sm" variant="outline" disabled={!job.jobDescription?.trim()} onClick={() => void copy(job.jobDescription || '', '岗位描述')}><Copy className="h-3.5 w-3.5" />复制岗位描述</Button></div><p className="whitespace-pre-wrap break-words rounded-xl border p-4 text-sm leading-7">{job.jobDescription?.trim() || '这条历史记录未保存完整岗位描述。可以复制公司与岗位名称，到已登录的 Boss 中手动搜索。'}</p></section>
      <details className="rounded-xl border p-4"><summary className="cursor-pointer text-sm font-medium">公司与招聘信息</summary><dl className="mt-4 grid gap-4 text-sm sm:grid-cols-2">{[
        ['HR', [job.hrName, job.hrPosition].filter(Boolean).join(' · ')], ['HR 活跃', job.hrActiveStatus],
        ['招聘状态', job.recruitmentStatus], ['行业', job.industry], ['公司规模', job.companyScale], ['融资阶段', job.financingStage],
        ['公司地址', job.companyAddress], ['记录时间', job.createdAt],
      ].map(([label, value]) => <div key={label}><dt className="mb-1 text-xs text-muted-foreground">{label}</dt><dd className="break-words">{value || '未记录'}</dd></div>)}</dl>{job.introduce && <p className="mt-4 whitespace-pre-wrap break-words text-sm leading-7">{job.introduce}</p>}</details>
      <section className="space-y-3 rounded-xl border bg-muted/20 p-4" aria-labelledby="boss-record-search"><h3 id="boss-record-search" className="text-sm font-semibold">需要查看最新岗位？</h3><p className="text-xs leading-6 text-muted-foreground">复制公司与岗位名称，到你已登录的 Boss 中手动搜索。历史网址可能已失效，或依赖采集时的登录与访问状态。</p><div className="flex flex-wrap items-center gap-3"><p className="min-w-0 flex-1 break-words text-sm">{searchText || '公司与岗位名称未记录'}</p><Button type="button" size="sm" variant="outline" disabled={!searchText} onClick={() => void copy(searchText, '公司与岗位名称')}><Copy className="h-3.5 w-3.5" />复制公司与岗位</Button></div><details><summary className="cursor-pointer text-xs text-muted-foreground">历史原链接（仅复制）</summary><p className="mt-2 text-xs leading-6 text-muted-foreground">此处不会自动打开、刷新或检测原站。手动访问如出现安全验证或反复跳转，请关闭该标签页，避免反复刷新。</p><p className="mt-2 select-all break-all rounded-lg border bg-card p-3 font-mono text-xs">{job.jobUrl || '未保存原链接'}</p><Button type="button" size="sm" variant="outline" className="mt-2" disabled={!originalUrl} onClick={() => originalUrl && void copy(originalUrl, '历史原链接')}>复制原链接</Button>{job.jobUrl && !originalUrl && <p className="mt-2 text-xs text-amber-700">原地址不是有效的 Boss 岗位详情网址，已禁用复制。</p>}</details></section>
      {copyValue && <label className="block text-xs text-muted-foreground">待复制内容<textarea ref={copyArea} aria-label="待复制内容" readOnly rows={2} value={copyValue} className="mt-2 block w-full rounded-lg border bg-background p-3 text-sm text-foreground" /></label>}
      {notice && <p role="status" className="text-sm text-primary">{notice}</p>}
    </div>
    <footer className="flex justify-end border-t bg-card p-4"><Button type="button" onClick={onClose}>关闭详情</Button></footer>
  </dialog>, document.body)
}
