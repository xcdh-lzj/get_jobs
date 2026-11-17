'use client'

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { AlertCircle, CheckCircle2, Loader2, RotateCcw, Save, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { saveWorkspace, type WorkspaceEditor } from '@/lib/workspace-save'

interface WorkspaceContextValue {
  editors: WorkspaceEditor[]
  saving: boolean
  notice: { error: boolean; text: string } | null
  register: (editor: WorkspaceEditor) => () => void
  save: () => Promise<boolean>
  confirmLeave: () => Promise<boolean>
  discard: () => void
}
const WorkspaceContext = createContext<WorkspaceContextValue | null>(null)

export function WorkspaceEditsProvider({ children }: { children: ReactNode }) {
  const [editors, setEditors] = useState<WorkspaceEditor[]>([])
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const [notice, setNotice] = useState<WorkspaceContextValue['notice']>(null)
  const [confirming, setConfirming] = useState(false)
  const resolver = useRef<((answer: boolean) => void) | null>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  const dirty = editors.some(editor => editor.dirty)
  const scope = editors.map(editor => editor.id).sort().join(',')
  useEffect(() => { queueMicrotask(() => setNotice(null)) }, [scope])
  const register = useCallback((editor: WorkspaceEditor) => {
    setEditors(previous => [...previous.filter(item => item.id !== editor.id), editor])
    return () => setEditors(previous => previous.filter(item => item !== editor))
  }, [])
  const discard = useCallback(() => {
    editors.forEach(editor => { if (editor.dirty) editor.reset() })
    setNotice(null)
  }, [editors])
  const save = useCallback(async () => {
    if (savingRef.current) return false
    savingRef.current = true; setSaving(true); setNotice(null)
    try {
      const result = await saveWorkspace(editors)
      if (result.failed.length) {
        setNotice({ error: true, text: `${result.saved.length ? `已保存${result.saved.join('、')}。` : ''}${result.failed.map(item => `${item.label}：${item.message}`).join('；')}。未成功的修改仍保留，可重试。` })
        return false
      }
      setNotice({ error: false, text: result.saved.length ? '当前账号的全部修改已保存，下次启动投递时生效。' : '当前没有需要保存的修改。' })
      return true
    } catch (error) {
      setNotice({ error: true, text: error instanceof Error ? error.message : '保存失败，修改仍保留。' })
      return false
    } finally { savingRef.current = false; setSaving(false) }
  }, [editors])
  const confirmLeave = useCallback(async () => {
    if (savingRef.current) return false
    if (!dirty) return true
    if (resolver.current) return false
    setConfirming(true)
    return new Promise<boolean>(resolve => { resolver.current = resolve })
  }, [dirty])
  const answer = (value: boolean) => {
    resolver.current?.(value); resolver.current = null; setConfirming(false)
  }
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  useEffect(() => {
    if (!confirming) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !savingRef.current) { resolver.current?.(false); resolver.current = null; setConfirming(false) }
      if (event.key === 'Tab') {
        const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
        if (!buttons?.length) return
        const first = buttons[0], last = buttons[buttons.length - 1]
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }
    }
    window.addEventListener('keydown', close)
    return () => { document.body.style.overflow = previousOverflow; window.removeEventListener('keydown', close) }
  }, [confirming])

  return <WorkspaceContext.Provider value={{ editors, saving, notice, register, save, confirmLeave, discard }}>
    <div inert={confirming || undefined}>{children}</div>
    {confirming && <div className="fixed inset-0 z-[200] flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-labelledby="unsaved-title">
      <div ref={dialogRef} className="w-full max-w-md rounded-2xl border bg-card p-6 shadow-xl">
        <div className="mb-4 flex items-center justify-between"><AlertCircle className="h-6 w-6 text-amber-600" /><Button variant="ghost" size="icon" aria-label="留在当前页面" autoFocus disabled={saving} onClick={() => answer(false)}><X /></Button></div>
        <h2 id="unsaved-title" className="text-lg font-semibold">还有未保存的修改</h2>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">{editors.filter(editor => editor.dirty).map(editor => editor.label).join('、')}尚未保存。离开前可以保存，也可以放弃这些修改。</p>
        <div className="mt-6 flex flex-wrap justify-end gap-2"><Button variant="ghost" disabled={saving} onClick={() => answer(false)}>继续编辑</Button><Button variant="outline" disabled={saving} onClick={() => { discard(); answer(true) }}>放弃并离开</Button><Button disabled={saving} onClick={async () => { if (await save()) answer(true) }}>{saving ? '保存中…' : '保存并离开'}</Button></div>
        {notice?.error && <p role="alert" className="mt-3 text-sm text-red-600">{notice.text}</p>}
      </div>
    </div>}
  </WorkspaceContext.Provider>
}

export function useWorkspaceEdits() {
  const context = useContext(WorkspaceContext)
  if (!context) throw new Error('WorkspaceEditsProvider is required')
  return context
}

export function useWorkspaceEditor(editor: WorkspaceEditor) {
  const { register } = useWorkspaceEdits()
  const latest = useRef(editor)
  useEffect(() => { latest.current = editor })
  const { id, label, dirty, ready, validationError } = editor
  useEffect(() => register({ id, label, dirty, ready, validationError, prepare: () => latest.current.prepare(), reset: () => latest.current.reset() }), [register, id, label, dirty, ready, validationError])
}

export function WorkspaceSaveBar({ locked, children }: { locked: boolean; children?: ReactNode }) {
  const { editors, saving, notice, save, discard } = useWorkspaceEdits()
  const changed = editors.filter(editor => editor.dirty)
  const ready = editors.length >= 3 && editors.every(editor => editor.ready)
  const barRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const bar = barRef.current, workspace = bar?.closest<HTMLElement>('.workbench')
    if (!bar || !workspace) return
    const measure = () => workspace.style.setProperty('--workspace-save-height', `${bar.getBoundingClientRect().height + 12}px`)
    const observer = new ResizeObserver(measure)
    observer.observe(bar); measure()
    return () => { observer.disconnect(); workspace.style.removeProperty('--workspace-save-height') }
  }, [])
  return <div ref={barRef} className="workspace-savebar">
    <div className="flex min-w-0 items-center gap-2.5 text-sm">
      {saving ? <Loader2 className="h-4 w-4 animate-spin text-primary" /> : changed.length ? <span className="h-2 w-2 shrink-0 rounded-full bg-amber-500" /> : <CheckCircle2 className="h-4 w-4 shrink-0 text-teal-600" />}
      <div className="min-w-0"><p className="font-medium">{saving ? '正在保存当前账号…' : changed.length ? `${changed.length} 项配置有未保存修改` : ready ? '所有配置已保存' : '正在读取账号配置…'}</p><p className="mt-0.5 hidden truncate text-xs text-muted-foreground sm:block">{locked ? '队列执行中，当前账号配置已锁定' : changed.length ? changed.map(editor => editor.label).join(' · ') : '修改任意分区后，在此统一保存'}</p></div>
    </div>
    <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">
      {children}
      {changed.length > 0 && <Button variant="ghost" size="sm" disabled={locked || saving} onClick={discard}><RotateCcw className="h-3.5 w-3.5" /><span className="hidden sm:inline">撤销修改</span></Button>}
      <Button disabled={locked || saving || !ready || changed.length === 0} onClick={() => void save()}><Save className="h-4 w-4" />{saving ? '保存中…' : '保存全部修改'}</Button>
    </div>
    {notice && (notice.error ? changed.length > 0 : changed.length === 0) && <p role={notice.error ? 'alert' : 'status'} className={`basis-full rounded-lg px-3 py-2 text-sm ${notice.error ? 'bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-200' : 'bg-teal-50 text-teal-800 dark:bg-teal-950 dark:text-teal-200'}`}>{notice.text}</p>}
    {!ready && editors.some(editor => !editor.ready && editor.validationError) && <p role="alert" className="basis-full text-xs text-amber-700">{editors.filter(editor => !editor.ready).map(editor => `${editor.label}：${editor.validationError || '读取中'}`).join('；')}。请到相应分区重试。</p>}
  </div>
}
