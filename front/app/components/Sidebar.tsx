'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { BriefcaseBusiness, Search, Building2, Users, Settings2, Sparkles, PanelLeftClose, PanelLeftOpen, Moon, Sun, Clover, X } from 'lucide-react'
import { useTheme } from 'next-themes'
import { API_BASE } from '@/lib/api'
import { useAppShell } from './AppShell'
import { useWorkspaceEdits } from './WorkspaceEdits'

const groups = [
  { title: '投递平台', items: [
    { href: '/boss', icon: BriefcaseBusiness, label: 'Boss 直聘' },
    { href: '/liepin', icon: Search, label: '猎聘' },
    { href: '/51job', icon: Building2, label: '前程无忧' },
    { href: '/zhilian', icon: Users, label: '智联招聘' },
  ] },
  { title: '通用设置', items: [
    { href: '/env-config', icon: Settings2, label: '服务与通知' },
    { href: '/ai-config', icon: Sparkles, label: '公共 AI 资料' },
  ] },
]

export default function Sidebar() {
  const pathname = usePathname(), router = useRouter()
  const { theme, setTheme } = useTheme()
  const { collapsed, toggle, mobileOpen, setMobileOpen } = useAppShell()
  const { confirmLeave } = useWorkspaceEdits()
  const [mounted, setMounted] = useState(false)
  const [health, setHealth] = useState<'up' | 'degraded' | 'down' | 'unknown'>('unknown')
  const checking = useRef(false)
  useEffect(() => { queueMicrotask(() => setMounted(true)) }, [])
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    const check = async () => {
      if (checking.current) return
      checking.current = true
      const request = new AbortController()
      const abort = () => request.abort()
      controller.signal.addEventListener('abort', abort)
      const timeout = setTimeout(abort, 3000)
      try {
        let response = await fetch(`${API_BASE}/api/health`, { signal: request.signal })
        if (response.status === 404) response = await fetch(`${API_BASE}/actuator/health`, { signal: request.signal })
        if (!response.ok) throw new Error('unavailable')
        const data = await response.json()
        const status = String(data.status || data.state || '').toUpperCase()
        if (active) setHealth(['UP', 'HEALTHY'].includes(status) ? 'up' : ['DEGRADED', 'WARN'].includes(status) ? 'degraded' : 'down')
      } catch { if (active) setHealth('unknown') }
      finally { clearTimeout(timeout); controller.signal.removeEventListener('abort', abort); checking.current = false }
    }
    void check()
    const timer = setInterval(check, 30000)
    return () => { active = false; controller.abort(); clearInterval(timer) }
  }, [])

  return <aside className={`app-sidebar ${mobileOpen ? 'is-open' : ''}`} aria-label="主导航">
    <div className="sidebar-brand"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-teal-400 text-slate-950"><Clover className="h-6 w-6" strokeWidth={1.8} /></span><div className="sidebar-label min-w-0"><p className="text-lg font-semibold tracking-tight text-white">Get Jobs<span className="ml-1 text-teal-400">.</span></p><p className="mt-0.5 text-[11px] text-slate-400">让每一次投递更有价值</p></div><button aria-label="关闭导航菜单" onClick={() => setMobileOpen(false)} className="ml-auto p-1 text-slate-400 lg:hidden"><X className="h-5 w-5" /></button></div>
    <nav className="flex-1 space-y-7 overflow-y-auto px-3 py-6">
      {groups.map(group => <div key={group.title}><p className="sidebar-group-title">{group.title}</p><div className="space-y-1">{group.items.map(item => <Link key={item.href} href={item.href} title={collapsed ? item.label : undefined} aria-label={item.label} aria-current={pathname === item.href ? 'page' : undefined} className={`sidebar-link ${pathname === item.href ? 'is-active' : ''}`} onClick={async event => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        event.preventDefault()
        if (pathname === item.href || await confirmLeave()) { setMobileOpen(false); router.push(item.href) }
      }}><item.icon className="h-[19px] w-[19px] shrink-0" strokeWidth={1.7} /><span className="sidebar-label">{item.label}</span>{pathname === item.href && <span className="sidebar-label ml-auto h-1.5 w-1.5 rounded-full bg-teal-400" />}</Link>)}</div></div>)}
    </nav>
    <div className="sidebar-bottom">
      <div className="sidebar-service" title={health === 'up' ? '后端服务已连接' : '后端服务未连接'}><span className={`h-2 w-2 shrink-0 rounded-full ${health === 'up' ? 'bg-teal-400' : health === 'degraded' ? 'bg-amber-400' : 'bg-slate-500'}`} /><div className="sidebar-label"><p className="text-xs text-slate-200">{health === 'up' ? '服务已连接' : health === 'degraded' ? '服务部分可用' : health === 'down' ? '服务异常' : '等待后端连接'}</p><p className="mt-1 text-[10px] text-slate-500">Get Jobs · 自动投递助手</p></div></div>
      <div className="sidebar-controls"><button onClick={toggle} aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'} aria-expanded={!collapsed} title={collapsed ? '展开侧边栏' : '收起侧边栏'} className="sidebar-collapse hidden lg:inline-flex">{collapsed ? <PanelLeftOpen className="h-[18px] w-[18px]" /> : <><PanelLeftClose className="h-[18px] w-[18px]" /><span className="sidebar-label text-xs">收起导航</span></>}</button>{mounted && <button onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} aria-label={theme === 'dark' ? '切换到浅色' : '切换到深色'} title={theme === 'dark' ? '切换到浅色' : '切换到深色'} className="sidebar-theme rounded-lg p-2 text-slate-400 hover:bg-white/5 hover:text-white">{theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}</button>}</div>
    </div>
  </aside>
}
