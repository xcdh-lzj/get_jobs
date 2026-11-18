"use client"

import { useEffect, useRef, useState } from "react"
import { FileText } from "lucide-react"
import { Button } from "@/components/ui/button"
import { formatBossDeliveryTime, type BossHistoryJob } from "@/lib/boss-history"
import BossJobLink from "./BossJobLink"
import BossJobRecordDialog from "./BossJobRecordDialog"

const columns = [
  ["公司名称", 132],
  ["公司规模", 86],
  ["岗位名称", 194],
  ["薪资", 90],
  ["地点", 92],
  ["学历", 64],
  ["经验", 80],
  ["HR职位", 100],
  ["HR活跃", 92],
  ["投递状态", 88],
  ["投递或过滤原因", 212],
  ["职位描述", 94],
  ["岗位链接", 86],
  ["投递时间", 120],
] as const

const statusColors: Record<string, string> = {
  已投递: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300",
  已过滤: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-300",
  投递失败: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300",
  未投递: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200",
}

function text(value?: string) {
  return value?.trim() || <span className="text-muted-foreground">—</span>
}

export default function BossJobHistoryTable({ items, loading, error, scrollKey }: {
  items: BossHistoryJob[]
  loading: boolean
  error: boolean
  scrollKey: string
}) {
  const [selected, setSelected] = useState<BossHistoryJob | null>(null)
  const container = useRef<HTMLDivElement>(null)
  useEffect(() => { if (container.current) container.current.scrollTop = 0 }, [scrollKey])
  return <>
    <div
      ref={container}
      role="region"
      aria-label="岗位数据表格，可横向滚动"
      tabIndex={0}
      className="relative max-h-[34rem] min-w-0 max-w-full overflow-auto rounded-xl border focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
    >
      <table className="w-full min-w-[1530px] table-fixed border-separate border-spacing-0 text-left text-[13px] leading-5 [&_td]:border-b [&_td]:border-border/50 [&_td]:px-3 [&_td]:py-3 [&_td]:align-top [&_td]:[overflow-wrap:anywhere] [&_tr:last-child_td]:border-b-0">
        <caption className="sr-only">Boss 岗位投递记录</caption>
        <colgroup>{columns.map(([label, width]) => <col key={label} style={{ width }} />)}</colgroup>
        <thead className="sticky top-0 z-10 bg-slate-100 text-slate-700 dark:bg-slate-900 dark:text-slate-200">
          <tr>
            {columns.map(([label]) => (
              <th key={label} scope="col" className={"border-b px-3 py-3 text-xs font-semibold whitespace-nowrap"
                + (label === "岗位名称" ? " lg:sticky lg:left-0 lg:bg-slate-100 dark:lg:bg-slate-900" : "")}
              >{label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.length === 0 ? (
            <tr><td colSpan={columns.length} className="h-28 !align-middle text-center text-muted-foreground">
              {error ? "暂时无法读取岗位数据" : loading ? "正在加载岗位数据…" : "当前筛选条件下暂无岗位"}
            </td></tr>
          ) : items.map(job => {
            const time = formatBossDeliveryTime(job.deliveredAt)
            const status = job.deliveryStatus?.trim()
            const recent = /^(在线|当前在线|刚刚?活跃|今[日天]活跃)$/.test(job.hrActiveStatus?.trim() ?? "")
            return (
              <tr key={job.id} className="odd:bg-card even:bg-slate-50 dark:even:bg-slate-900 hover:odd:bg-teal-50 hover:even:bg-teal-50 dark:hover:odd:bg-teal-950 dark:hover:even:bg-teal-950">
                <td className="font-medium">{text(job.companyName)}</td>
                <td className="text-muted-foreground">{text(job.companyScale)}</td>
                <td className="font-semibold text-foreground lg:sticky lg:left-0 lg:z-[1] lg:bg-inherit">{text(job.jobName)}</td>
                <td className="font-semibold tabular-nums text-teal-800 dark:text-teal-300">{text(job.salary)}</td>
                <td>{text(job.location)}</td>
                <td>{text(job.degree)}</td>
                <td>{text(job.experience)}</td>
                <td>{text(job.hrPosition)}</td>
                <td className={recent ? "font-medium text-emerald-700 dark:text-emerald-300" : "text-muted-foreground"}>
                  {text(job.hrActiveStatus)}
                </td>
                <td>
                  {status ? <span className={"inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold "
                    + (statusColors[status] ?? "bg-muted text-muted-foreground")}>{status}</span> : text()}
                </td>
                <td className="text-muted-foreground">{text(job.deliveryReason)}</td>
                <td>
                  {job.jobDescription?.trim() ? (
                    <Button type="button" variant="ghost" size="sm" className="-ml-1.5 -mt-1.5 h-8 gap-1 px-1.5 text-xs text-primary"
                      aria-label={"查看职位描述：" + (job.jobName || "未命名岗位")} onClick={() => setSelected(job)}
                    ><FileText className="h-3.5 w-3.5" />查看全文</Button>
                  ) : text()}
                </td>
                <td><BossJobLink url={job.jobUrl} title={job.jobName} /></td>
                <td className="tabular-nums">
                  {time ? <time dateTime={time.replace(" ", "T")} className="block text-xs leading-5">
                    <span className="block text-muted-foreground">{time.slice(0, 10)}</span>
                    <span className="font-medium">{time.slice(11)}</span>
                  </time> : text()}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
    {selected && <BossJobRecordDialog job={selected} onClose={() => setSelected(null)} />}
  </>
}
