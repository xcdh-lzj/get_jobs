'use client'

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { Menu, ChevronRight } from 'lucide-react'
import { usePathname } from 'next/navigation'
import Sidebar from './Sidebar'

const ShellContext = createContext<{ collapsed: boolean; toggle: () => void; mobileOpen: boolean; setMobileOpen: (value: boolean) => void }>({ collapsed: false, toggle: () => {}, mobileOpen: false, setMobileOpen: () => {} })
export const useAppShell = () => useContext(ShellContext)
const pages: Record<string, string> = { '/boss': 'Boss 直聘', '/liepin': '猎聘', '/51job': '前程无忧', '/zhilian': '智联招聘', '/env-config': '服务与通知', '/ai-config': '公共 AI 资料' }

export default function AppShell({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const pathname = usePathname()
  useEffect(() => { queueMicrotask(() => setCollapsed(localStorage.getItem('getjobs-sidebar-collapsed') === 'true')) }, [])
  const toggle = () => setCollapsed(value => {
    localStorage.setItem('getjobs-sidebar-collapsed', String(!value))
    return !value
  })
  useEffect(() => {
    if (!mobileOpen) return
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') setMobileOpen(false) }
    window.addEventListener('keydown', close)
    return () => { document.body.style.overflow = previous; window.removeEventListener('keydown', close) }
  }, [mobileOpen])
  return <ShellContext.Provider value={{ collapsed, toggle, mobileOpen, setMobileOpen }}>
    <div className={`app-shell ${collapsed ? 'sidebar-collapsed' : ''}`}>
      <a className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[200] focus:rounded-lg focus:bg-card focus:p-3" href="#main-content">跳转到主要内容</a>
      {mobileOpen && <button className="fixed inset-0 z-40 bg-slate-950/50 backdrop-blur-sm lg:hidden" aria-label="关闭导航菜单" onClick={() => setMobileOpen(false)} />}
      <Sidebar />
      <div className="app-main" inert={mobileOpen || undefined}>
        <header className="app-topbar">
          <div className="flex min-w-0 items-center gap-3"><button aria-label="打开导航菜单" onClick={() => setMobileOpen(true)} className="rounded-md p-1.5 hover:bg-muted lg:hidden"><Menu className="h-5 w-5" /></button><span className="hidden text-xs text-muted-foreground sm:inline">工作空间</span><ChevronRight className="hidden h-3.5 w-3.5 text-slate-400 sm:block" /><span className="truncate text-sm font-medium">{pages[pathname] || 'Get Jobs'}</span></div>
          <div className="flex items-center gap-2 text-xs text-muted-foreground"><span className="h-1.5 w-1.5 rounded-full bg-teal-500" />本地工作台</div>
        </header>
        <main id="main-content" className="app-content">{children}</main>
      </div>
    </div>
  </ShellContext.Provider>
}
