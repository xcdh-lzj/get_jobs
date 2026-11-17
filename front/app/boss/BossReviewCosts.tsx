'use client'

import type { ReviewEconomy, ReviewUsage } from '@/lib/boss-review'

export function BossReviewEconomySettings({ value, onChange }: {
  value: ReviewEconomy; onChange: (value: ReviewEconomy) => void
}) {
  return <div className="space-y-3 rounded-xl border bg-muted/20 p-4" aria-label="岗位审核省钱设置">
    <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={value.enabled} onChange={event => onChange({ ...value, enabled: event.target.checked })} />省钱审核（模型支持时关闭深度思考）</label>
    <details><summary className="text-xs">省钱审核如何生效？</summary><p className="mt-2 text-xs leading-6 text-muted-foreground">默认开启，先用简短结论和一条 JD 原文证据审核职责；信息不明确时自动跳过，不等待人工处理。当前支持关闭深度思考的模型为 DeepSeek Flash / V4，其它模型保留供应商默认推理设置。所有模式都限制输出长度。</p></details>
    <div className="grid gap-3 sm:grid-cols-2">
      <label className="text-sm">相同 JD 的结论复用天数<input aria-label="审核缓存天数" type="number" min={1} max={30} value={value.cacheDays} onChange={event => onChange({ ...value, cacheDays: Number(event.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
      <label className="text-sm">本账号每次任务的 token 预算<input aria-label="单次审核 token 预算" type="number" min={0} max={10000000} value={value.taskTokenLimit} onChange={event => onChange({ ...value, taskTokenLimit: Number(event.target.value) })} className="mt-2 block w-full rounded-md border bg-background p-2" /></label>
    </div>
    <p className="text-xs leading-6 text-muted-foreground">0 表示不限制；达到预算会停止本次任务，后续城市和账号不再执行。</p><details><summary className="text-xs">预算统计范围与停止规则</summary><p className="mt-2 text-xs leading-6 text-muted-foreground">预算仅包含岗位职责审核；按供应商返回的输入与输出 token 结算，达到后结束本次任务，后续城市和账号不再执行。启用预算时逐批请求，当前已发出的请求可能使总量超过预算；供应商未返回完整用量时也会停止追加调用。点击上方“保存全部修改”后生效。</p></details>
  </div>
}

export function BossReviewUsagePanel({ usage }: { usage?: ReviewUsage | null }) {
  if (!usage?.tracked) return <p className="rounded-xl border p-4 text-xs text-muted-foreground">这条历史记录未采集 token 用量；新任务会显示请求数、结论复用和实际返回的用量。</p>
  const number = (value: number) => value.toLocaleString('zh-CN')
  return <section aria-label="本次岗位审核用量" className="space-y-3 rounded-xl border p-4">
    <div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-medium">本次岗位审核用量</p><span className="text-xs text-muted-foreground">任务设置：{usage.settings.enabled ? '省钱审核' : '常规审核'} · 预算 {usage.settings.taskTokenLimit ? number(usage.settings.taskTokenLimit) : '不限'}</span></div>
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">{[
      ['AI 请求', `${number(usage.requests)} 次`], ['本地复用结论', `${number(usage.reusedJobs)} 岗位`],
      ['已记录总 token', number(usage.totalTokens)], ['输入 token', number(usage.inputTokens)],
      ['输出 token', number(usage.outputTokens)], ['失败或截断请求', `${number(usage.failedRequests)} 次`],
    ].map(([label, value]) => <div key={label} className="rounded-lg bg-muted/30 p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="mt-1 text-lg font-semibold tabular-nums">{value}</p></div>)}</div>
    <p className="text-xs leading-6 text-muted-foreground">推理 token：{usage.reasoningTokens == null ? '供应商未提供' : number(usage.reasoningTokens)} · 服务端缓存输入 token：{usage.cachedInputTokens == null ? '供应商未提供' : number(usage.cachedInputTokens)} · 已确认关闭深度思考：{usage.economyAppliedRequests} 次。推理量属于输出明细，缓存量属于输入明细，均不重复计入总量。</p>
    {usage.unreportedRequests > 0 && <p role="alert" className="text-xs text-amber-700">有 {usage.unreportedRequests} 次请求未返回完整用量，上面的 token 总量可能不完整；以供应商账单为准。</p>}
    <p className="text-xs leading-6 text-muted-foreground">本地复用不调用 AI；城市、薪资和黑名单每次重新检查。仅统计职责审核，不包含简历分析和 AI 招呼语。已停止、格式异常或输出截断的请求，只要返回用量也会计入。</p>
  </section>
}
