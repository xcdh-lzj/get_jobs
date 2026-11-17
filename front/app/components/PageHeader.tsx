'use client'

import type { ReactNode } from 'react'

export default function PageHeader({ icon, title, subtitle, actions }: {
  icon: ReactNode; title: string; subtitle?: string; iconClass?: string; accentBgClass?: string; actions?: ReactNode
}) {
  return <div className="page-header"><div className="flex min-w-0 items-center gap-3"><div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border bg-card text-xl text-primary">{icon}</div><div><h1 className="text-2xl font-semibold tracking-tight">{title}</h1>{subtitle && <p className="mt-1 text-sm leading-6 text-muted-foreground">{subtitle}</p>}</div></div>{actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}</div>
}
