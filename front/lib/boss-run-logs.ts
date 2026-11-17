import type { BossProgressLog } from './boss-progress'

export type LogView = 'key' | 'all' | 'attention'
export type LogKind = 'task' | 'detail' | 'filtered' | 'review' | 'completed' | 'warning' | 'error'
export type LogRow = BossProgressLog & { kind: LogKind; count: number; lastTimestamp: number; important: boolean }

export function logKind(entry: BossProgressLog): LogKind {
  const text = entry.message.trim()
  if (entry.type === 'error' || /^(?:启动失败|投递失败|执行失败)|触发.*安全校验|审核失败.*停止/.test(text)) return 'error'
  if (entry.type === 'warning' || /沟通.*(?:上限|耗尽)|(?:剩余|还剩).*沟通|预算.*(?:触发|用尽)|请.*(?:登录|验证)|登录失效/.test(text)) return 'warning'
  if (/^(?:已过滤|自动跳过|已跳过|跳过|AI判断不匹配|当前岗位未投递)/.test(text)) return 'filtered'
  if (/^(?:待审核|仅审核通过|审核通过|职责审核|核对)/.test(text)) return 'review'
  if (/当前岗位投递流程完成/.test(text)) return 'completed'
  if (/^(?:正在读取岗位详情|正在加载岗位列表|正在投递[：:]|正在打开沟通页面|正在发送|补齐岗位详情|AI 正在分析)/.test(text)) return 'detail'
  return 'task'
}

/** Only adjacent identical raw events are folded. Never fold across another job's events. */
export function buildLogRows(logs: BossProgressLog[]): LogRow[] {
  const rows: LogRow[] = []
  for (const [index, entry] of logs.entries()) {
    const kind = logKind(entry)
    const previous = rows.at(-1)
    const duplicate = previous && previous.type === entry.type && previous.message === entry.message
      && entry.timestamp >= previous.lastTimestamp && entry.timestamp - previous.lastTimestamp <= 10_000
    if (duplicate) {
      previous.count++
      previous.lastTimestamp = entry.timestamp
      continue
    }
    const redundantSkip = entry.message.trim() === '当前岗位未投递' && index > 0
      && /^(?:已过滤|自动跳过|已跳过|跳过|待审核|仅审核通过|AI判断不匹配)/.test(logs[index - 1].message.trim())
    rows.push({ ...entry, kind, count: 1, lastTimestamp: entry.timestamp,
      important: kind !== 'detail' && (!redundantSkip || kind === 'warning' || kind === 'error') })
  }
  return rows
}

export function visibleLogRows(rows: LogRow[], view: LogView, query: string): LogRow[] {
  const term = query.trim().toLocaleLowerCase()
  return rows.filter(row => (view === 'all' || (view === 'key' ? row.important : row.kind === 'warning' || row.kind === 'error'))
    && (!term || row.message.toLocaleLowerCase().includes(term)))
}

export function logTime(timestamp: number): string {
  const date = new Date(timestamp)
  return Number.isNaN(date.getTime()) ? '--:--:--'
    : date.toLocaleTimeString('zh-CN', { hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Copy original events, including process details and folded repetitions. */
export function exportRunLogs(logs: BossProgressLog[]): string {
  return logs.map(entry => `${new Date(entry.timestamp).toLocaleDateString('zh-CN')} ${logTime(entry.timestamp)} [${entry.type}] ${entry.message}`).join('\n')
}

export function countNewLogs(logs: BossProgressLog[], pausedAtId: number): number {
  return logs.filter(entry => entry.id > pausedAtId).length
}
