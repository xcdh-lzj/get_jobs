import { bossHistoryUrl } from '@/lib/boss-history'

export default function BossJobLink({ url, title }: { url?: string; title?: string }) {
  const href = bossHistoryUrl(url)
  if (!href) return <span className="text-xs text-muted-foreground">暂无链接</span>
  return <a
    href={href}
    target="_blank"
    rel="noopener noreferrer"
    aria-label={`打开 Boss 岗位：${title || '未命名岗位'}`}
    className="text-primary underline underline-offset-2 hover:text-primary/80"
  >岗位链接</a>
}
