"use client"

import AnalysisContent from "@/app/boss/analysis/AnalysisContent"

import AccountsWorkspace from "../AccountsWorkspace"

export default function BossAnalysisPage() {
  return <AccountsWorkspace>{id => <AnalysisContent key={id} accountId={id} showHeader />}</AccountsWorkspace>
}