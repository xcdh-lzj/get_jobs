export type InboxState = 'offline' | 'stopped' | 'opening' | 'connecting' | 'watching' | 'unavailable' | 'closed' | 'stale'

export interface BossInboxAccount {
  accountId: number
  name: string
  state: InboxState
  browserOpen: boolean
  unread: number | null
  capped: boolean
  updatedAt: number | null
  notificationId: number
}

export interface BossInboxSnapshot {
  instanceId: string
  enabled: boolean
  selectedAccountIds: number[]
  accounts: BossInboxAccount[]
}

export function newInboxAlerts(previous: BossInboxSnapshot | null, current: BossInboxSnapshot): BossInboxAccount[] {
  if (!previous?.enabled || !current.enabled || previous.instanceId !== current.instanceId) return []
  const seen = new Map(previous.accounts.map(account => [account.accountId, account.notificationId]))
  return current.accounts.filter(account => account.state === 'watching' && !!account.unread
    && account.notificationId > (seen.get(account.accountId) ?? 0))
}
