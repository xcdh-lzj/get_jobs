"use client"

import AnalysisContent from "@/app/boss/analysis/AnalysisContent"
import { bossHistoryProgressKey } from "@/lib/boss-progress"
import { useBossProgress } from "@/lib/use-boss-progress"

import AccountsWorkspace from "../AccountsWorkspace"

function AccountAnalysis({ id }: { id: number }) {
  const { status, connection } = useBossProgress(id)
  return <AnalysisContent accountId={id} showHeader progressKey={bossHistoryProgressKey(status?.progress)} progressConnection={connection} />
}

export default function BossAnalysisPage() {
  return <AccountsWorkspace>{id => <AccountAnalysis key={id} id={id} />}</AccountsWorkspace>
}
