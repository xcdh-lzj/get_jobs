'use client'

import { useState } from 'react'
import { ExternalLink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { apiUrl } from '@/lib/api'

export default function PlatformLoginButton({ platform, name, onLogin }: {
  platform: 'liepin' | '51job' | 'zhilian'
  name: string
  onLogin: () => void
}) {
  const [opening, setOpening] = useState(false)
  const [error, setError] = useState('')
  const open = async () => {
    setOpening(true)
    setError('')
    try {
      const response = await fetch(apiUrl(`/api/${platform}/login`), { method: 'POST' })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || '无法打开登录页')
      if (result.isLoggedIn) onLogin()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '无法打开登录页')
    } finally {
      setOpening(false)
    }
  }

  return <div className="flex flex-col gap-1">
    <Button size="sm" variant="outline" disabled={opening} onClick={() => void open()}>
      <ExternalLink className="h-4 w-4" />{opening ? '正在打开…' : `打开${name}登录页`}
    </Button>
    {error && <span role="alert" className="text-xs text-destructive">{error}</span>}
  </div>
}
