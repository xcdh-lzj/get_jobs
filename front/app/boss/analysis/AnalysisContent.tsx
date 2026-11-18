"use client"

import { memo, useEffect, useMemo, useRef, useState } from "react"
import type { Chart, ChartDataset } from "chart.js"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Label } from "@/components/ui/label"
import PageHeader from "@/app/components/PageHeader"
import { BiRefresh, BiReset, BiDownload, BiBarChart, BiLineChart, BiPieChart, BiBriefcase } from "react-icons/bi"

import BossJobHistoryTable from "./BossJobHistoryTable"
import { bossUrl } from "@/lib/boss-accounts"
import { BOSS_HISTORY_SORT_OPTIONS, type BossHistoryJob as BossJob, type BossHistorySort } from "@/lib/boss-history"
import { sameAnalysisChart, type AnalysisChartProps } from "@/lib/boss-analysis-chart"

type NameValue = { name: string; value: number }
type BucketValue = { bucket: string; value: number }

type StatsResponse = {
  kpi: {
    total: number
    delivered: number
    pending: number
    filtered: number
    failed: number
    avgMonthlyK?: number | null
  }
  charts: {
    byStatus: NameValue[]
    byCity: NameValue[]
    byIndustry: NameValue[]
    byCompany: NameValue[]
    byExperience: NameValue[]
    byDegree: NameValue[]
    salaryBuckets: BucketValue[]
    dailyTrend: NameValue[]
    hrActivity: NameValue[]
  }
}


type PagedResult = {
  items: BossJob[]
  total: number
  page: number
  size: number
}

// 通用分类颜色（用于柱状/饼状图每个分类不同颜色）
const CATEGORY_COLORS = [
  "#3b82f6",
  "#10b981",
  "#f59e0b",
  "#ef4444",
  "#6366f1",
  "#22c55e",
  "#fb7185",
  "#a78bfa",
  "#f97316",
  "#06b6d4",
  "#4ade80",
  "#2dd4bf",
  "#f472b6",
  "#64748b",
]

type AnalysisChart = Chart<AnalysisChartProps["type"], number[], string>
type ChartWindow = Window & { Chart?: typeof Chart }

async function ensureChart(): Promise<typeof Chart> {
  const chartWindow = window as ChartWindow
  if (chartWindow.Chart) return chartWindow.Chart
  return new Promise((resolve, reject) => {
    const onLoad = () => chartWindow.Chart
      ? resolve(chartWindow.Chart)
      : reject(new Error("Chart.js CDN did not expose Chart"))
    const onError = () => reject(new Error("Chart.js CDN load error"))
    const existing = document.querySelector<HTMLScriptElement>("script[data-chartjs-cdn='true']")
    if (existing) {
      existing.addEventListener("load", onLoad, { once: true })
      existing.addEventListener("error", onError, { once: true })
      return
    }
    const script = document.createElement("script")
    script.src = "https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"
    script.async = true
    script.setAttribute("data-chartjs-cdn", "true")
    script.addEventListener("load", onLoad, { once: true })
    script.addEventListener("error", onError, { once: true })
    document.head.appendChild(script)
  })
}

const ChartCanvas = memo(function ChartCanvas({
  type,
  labels,
  data,
  title,
  color = "#3b82f6",
  colors,
}: AnalysisChartProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const chartRef = useRef<AnalysisChart | null>(null)
  // 颜色统一使用纯色（不透明）
  const toSolid = (hex: string) => hex

  useEffect(() => {
    const ctx = canvasRef.current?.getContext("2d")
    if (!ctx) return

    // 销毁旧图表
    if (chartRef.current) {
      chartRef.current.destroy()
      chartRef.current = null
    }

    let cancelled = false

    const pieColorsBase = [
      "#3b82f6",
      "#10b981",
      "#f59e0b",
      "#ef4444",
      "#6366f1",
      "#22c55e",
      "#fb7185",
      "#a78bfa",
      "#f97316",
      "#06b6d4",
    ]

    const backgroundColor = (() => {
      if (type === "pie") {
        const arr = (colors && colors.length ? colors : pieColorsBase).slice(0, labels.length)
        return arr
      }
      if (type === "bar" && colors && colors.length) {
        // 柱状图每个分类使用纯色
        return colors.slice(0, data.length).map((c) => toSolid(c))
      }
      // 折线图/默认均使用纯色
      return toSolid(color ?? "#3b82f6")
    })()

    const borderColor = (() => {
      if (type === "pie") {
        // 饼图无需边框或统一边框
        return undefined
      }
      if (type === "bar" && colors && colors.length) {
        return colors.slice(0, data.length)
      }
      return color
    })()

    const dataset: ChartDataset<AnalysisChartProps["type"], number[]> = {
      label: title || "",
      data,
      backgroundColor,
      borderColor,
      // 线形图不使用区域填充，点与线均为纯色。
      ...(type === "line" ? {
        fill: false,
        pointBackgroundColor: toSolid(color),
        pointBorderColor: toSolid(color),
      } : {}),
    }

    ;(async () => {
      try {
        const Chart = await ensureChart()
        // 检查组件是否已卸载
        if (cancelled) return

        chartRef.current = new Chart<AnalysisChartProps["type"], number[], string>(ctx, {
          type,
          data: {
            labels,
            datasets: [dataset],
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
              legend: { display: type === "pie" },
              title: { display: !!title, text: title },
            },
            scales: type !== "pie" ? { x: { ticks: { autoSkip: true } }, y: { beginAtZero: true } } : undefined,
          },
        })
      } catch (error) {
        console.error("Failed to create chart:", error)
      }
    })()

    return () => {
      cancelled = true
      if (chartRef.current) {
        chartRef.current.destroy()
        chartRef.current = null
      }
    }
  }, [type, labels, data, title, color, colors])

  return <canvas ref={canvasRef} className="w-full h-64" />
}, sameAnalysisChart)

const STATUS_OPTIONS = ["未投递", "已投递", "已过滤", "投递失败"]

function AnalysisContent({ showHeader = false, accountId = 1, progressKey, progressConnection }: {
  showHeader?: boolean
  accountId?: number
  progressKey: string
  progressConnection: "connecting" | "live" | "reconnecting"
}) {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [statsRefresh, setStatsRefresh] = useState(0)
  const [list, setList] = useState<(PagedResult & { url: string; syncedAt: number }) | null>(null)
  const [errorUrl, setErrorUrl] = useState<string | null>(null)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [inputPage, setInputPage] = useState("1")
  const [appliedFilter, setAppliedFilter] = useState("")
  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState("")
  const [experience, setExperience] = useState("")
  const [degree, setDegree] = useState("")
  const [minK, setMinK] = useState("")
  const [maxK, setMaxK] = useState("")
  const [keyword, setKeyword] = useState("")
  const [processedFrom, setProcessedFrom] = useState("")
  const [processedTo, setProcessedTo] = useState("")
  const [sort, setSort] = useState<BossHistorySort>("RECORDED_DESC")
  const [filterHeadhunter, setFilterHeadhunter] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState(false)
  const refreshList = useRef<() => void>(() => {})
  const filterParams = new URLSearchParams(appliedFilter)
  if (filterHeadhunter) filterParams.set("filterHeadhunter", "true")
  const filterQuery = filterParams.toString()
  const historyParams = new URLSearchParams(filterQuery)
  historyParams.set("sort", sort)
  const historyQuery = historyParams.toString()
  const listParams = new URLSearchParams(historyQuery)
  listParams.set("page", String(page))
  listParams.set("size", String(size))
  const listUrl = bossUrl("/api/boss/list?" + listParams, accountId)
  const statsUrl = bossUrl("/api/boss/stats?" + filterQuery, accountId)
  const currentList = list?.url === listUrl ? list : null
  const total = currentList?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / size))
  const listError = errorUrl === listUrl
  const invalidDateRange = !!processedFrom && !!processedTo && processedFrom > processedTo

  const goToPage = (value: number) => {
    const next = Math.max(1, Math.min(value, pageCount))
    setPage(next)
    setInputPage(String(next))
  }

  const applyFilters = () => {
    if (invalidDateRange) return
    const params = new URLSearchParams()
    if (statuses.length) params.set("statuses", statuses.join(","))
    for (const [key, value] of Object.entries({ location, experience, degree, minK, maxK, keyword, processedFrom, processedTo })) {
      if (value.trim()) params.set(key, value.trim())
    }
    setAppliedFilter(params.toString())
    goToPage(1)
  }

  const resetFilters = () => {
    setStatuses([])
    setLocation("")
    setExperience("")
    setDegree("")
    setMinK("")
    setMaxK("")
    setKeyword("")
    setProcessedFrom("")
    setProcessedTo("")
    setFilterHeadhunter(false)
    setAppliedFilter("")
    goToPage(1)
  }

  useEffect(() => {
    let active = true
    let timer: number | undefined
    let request: AbortController | null = null
    let queued = false

    // 一次只读取当前页，密集的进度事件合并成下一次刷新。
    function scheduleRefresh() {
      if (!active || timer !== undefined) return
      if (request) {
        queued = true
        return
      }
      timer = window.setTimeout(() => {
        timer = undefined
        void load()
      }, 180)
    }

    async function load() {
      const controller = new AbortController()
      request = controller
      const timeout = window.setTimeout(() => controller.abort(), 8000)
      try {
        const response = await fetch(listUrl, { signal: controller.signal, cache: "no-store" })
        if (!response.ok) throw new Error("无法读取岗位记录")
        const data: PagedResult = await response.json()
        if (!Array.isArray(data.items)) throw new Error("岗位数据格式有误")
        if (active) {
          setList({ ...data, url: listUrl, syncedAt: Date.now() })
          setErrorUrl(null)
          if (data.page !== page) {
            setPage(data.page)
            setInputPage(String(data.page))
          }
        }
      } catch {
        if (active) setErrorUrl(listUrl)
      } finally {
        window.clearTimeout(timeout)
        request = null
        if (queued) {
          queued = false
          scheduleRefresh()
        }
      }
    }

    refreshList.current = scheduleRefresh
    scheduleRefresh()
    return () => {
      active = false
      window.clearTimeout(timer)
      request?.abort()
    }
  }, [listUrl, page])

  useEffect(() => { refreshList.current() }, [progressKey, progressConnection])

  useEffect(() => {
    const request = new AbortController()
    async function loadStats() {
      setLoadingStats(true)
      try {
        const response = await fetch(statsUrl, { signal: request.signal, cache: "no-store" })
        if (!response.ok) throw new Error("无法读取投递统计")
        const data: StatsResponse = await response.json()
        if (!request.signal.aborted) setStats(data)
      } catch (error) {
        if (!request.signal.aborted) console.error("fetch stats failed", error)
      } finally {
        if (!request.signal.aborted) setLoadingStats(false)
      }
    }
    queueMicrotask(() => { if (!request.signal.aborted) void loadStats() })
    return () => request.abort()
  }, [statsUrl, statsRefresh])

  const refreshData = () => {
    refreshList.current()
    setStatsRefresh(value => value + 1)
  }

  const exportCSV = async () => {
    setExporting(true)
    setExportError(false)
    try {
      const response = await fetch(bossUrl("/api/boss/jobs.csv?" + historyQuery, accountId), { cache: "no-store" })
      if (!response.ok || !response.headers.get("Content-Type")?.startsWith("text/csv")) {
        throw new Error("无法导出岗位记录")
      }
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement("a")
      link.href = url
      link.download = "boss_jobs_" + accountId + "_" + new Date().toISOString().slice(0, 10) + ".csv"
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch {
      setExportError(true)
    } finally {
      setExporting(false)
    }
  }

  const kpiCards = useMemo(() => {
    const k = stats?.kpi
    return [
      { title: "总岗位数", value: k?.total ?? 0 },
      { title: "已投递", value: k?.delivered ?? 0 },
      { title: "未投递", value: k?.pending ?? 0 },
      { title: "已过滤", value: k?.filtered ?? 0 },
      { title: "投递失败", value: k?.failed ?? 0 },
      { title: "平均月薪(K)", value: k?.avgMonthlyK ?? 0 },
    ]
  }, [stats])

  return (
    <div className="min-w-0 space-y-8">
      {showHeader && (
        <PageHeader
          title="Boss 投递分析"
          subtitle="查看当前账号的投递统计与岗位记录"
          icon={<BiBarChart size={28} />}
        />
      )}

      {/* KPI 卡片 */}
      <div className="grid grid-cols-2 md:grid-cols-6 gap-4" aria-busy={loadingStats}>
        {kpiCards.map((c, idx) => (
          <Card key={idx} className="border">
            <CardHeader>
              <CardTitle className="text-sm">{c.title}</CardTitle>
              <CardDescription className="text-xl font-semibold">{c.value}</CardDescription>
            </CardHeader>
          </Card>
        ))}
      </div>

      {/* 操作栏 */}
      <Card>
        <CardHeader className="space-y-0">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <CardTitle className="text-base">筛选与操作</CardTitle>
              <CardDescription>按岗位条件与处理日期筛选记录</CardDescription>
            </div>
            <div className="flex flex-wrap gap-3 rounded-full px-3 py-2 border border-white/20 bg-white/5 backdrop-blur-md shadow-sm">
              {STATUS_OPTIONS.map((s) => (
                <label
                  key={s}
                  className={`group inline-flex items-center gap-2 text-sm rounded-full px-3 py-1.5 transition-all border backdrop-blur-sm ${
                    statuses.includes(s)
                      ? "border-cyan-300/60 bg-gradient-to-r from-cyan-500/15 to-violet-500/15 text-cyan-900 dark:text-cyan-200 shadow"
                      : "border-white/20 bg-white/8 text-foreground/80 hover:bg-white/12"
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={statuses.includes(s)}
                    onChange={(e) => {
                      setStatuses((prev) => {
                        if (e.target.checked) return Array.from(new Set([...prev, s]))
                        return prev.filter((x) => x !== s)
                      })
                    }}
                    className="sr-only peer"
                  />
                  <span className="inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all peer-checked:bg-cyan-400/60 peer-checked:border-cyan-300/80"></span>
                  {s}
                </label>
              ))}
              <label
                className={`group inline-flex items-center gap-2 text-sm rounded-full px-3 py-1.5 transition-all border backdrop-blur-sm ${
                  filterHeadhunter
                    ? "border-teal-300/60 bg-gradient-to-r from-teal-500/15 to-emerald-500/15 text-teal-900 dark:text-teal-200 shadow"
                    : "border-white/20 bg-white/8 text-foreground/80 hover:bg-white/12"
                }`}
              >
                <input
                  type="checkbox"
                  checked={filterHeadhunter}
                  onChange={(e) => { setFilterHeadhunter(e.target.checked); goToPage(1) }}
                  className="sr-only peer"
                />
                <span className="inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all peer-checked:bg-teal-400/60 peer-checked:border-teal-300/80"></span>
                过滤猎头岗位
              </label>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-6 gap-4">
            <div>
              <Label>城市</Label>
              <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="如：深圳" />
            </div>
            <div>
              <Label>经验</Label>
              <Input value={experience} onChange={(e) => setExperience(e.target.value)} placeholder="如：3-5年" />
            </div>
            <div>
              <Label>学历</Label>
              <Input value={degree} onChange={(e) => setDegree(e.target.value)} placeholder="如：本科" />
            </div>
            <div>
              <Label>最低月薪(K)</Label>
              <Input type="number" value={minK} onChange={(e) => setMinK(e.target.value)} placeholder="10" />
            </div>
            <div>
              <Label>最高月薪(K)</Label>
              <Input type="number" value={maxK} onChange={(e) => setMaxK(e.target.value)} placeholder="30" />
            </div>
            <div>
              <Label>关键词</Label>
              <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="公司/岗位/HR" />
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-end gap-3">
            <div className="w-44 space-y-1.5">
              <Label htmlFor="boss-processed-from">处理起始日期</Label>
              <Input id="boss-processed-from" type="date" value={processedFrom}
                onChange={event => setProcessedFrom(event.target.value)}
                aria-invalid={invalidDateRange} aria-describedby={invalidDateRange ? "boss-processing-date-error" : undefined}
              />
            </div>
            <div className="w-44 space-y-1.5">
              <Label htmlFor="boss-processed-to">处理结束日期</Label>
              <Input id="boss-processed-to" type="date" value={processedTo}
                onChange={event => setProcessedTo(event.target.value)}
                aria-invalid={invalidDateRange} aria-describedby={invalidDateRange ? "boss-processing-date-error" : undefined}
              />
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" onClick={applyFilters} disabled={invalidDateRange}>
                <BiBarChart /> 应用筛选
              </Button>
              <Button type="button" variant="outline" onClick={resetFilters}>
                <BiReset /> 重置筛选
              </Button>
            </div>
          </div>
          {invalidDateRange && <p id="boss-processing-date-error" role="alert" className="mt-2 text-sm text-destructive">
            处理起始日期须早于或等于结束日期。
          </p>}
        </CardContent>
      </Card>

      <Card className="min-w-0 overflow-hidden">
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 space-y-0 pb-4">
          <div className="space-y-1.5">
            <CardTitle className="flex items-center gap-2 text-base"><BiBriefcase className="text-primary" />岗位数据</CardTitle>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <span aria-hidden="true" className={"h-1.5 w-1.5 rounded-full " + (progressConnection === "live" ? "bg-emerald-600" : "bg-amber-500")} />
                {progressConnection === "live" ? "实时同步" : progressConnection === "reconnecting" ? "进度连接重试中" : "正在连接进度"}
              </span>
              {currentList && <span className="tabular-nums">更新于 {new Date(currentList.syncedAt).toLocaleTimeString("zh-CN", { hour12: false })}</span>}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-2">
              <Label htmlFor="boss-history-sort" className="text-muted-foreground">排序</Label>
              <Select id="boss-history-sort" className="h-9 w-auto" value={sort}
                title="月薪按薪资区间中点排序"
                onChange={event => {
                  const option = BOSS_HISTORY_SORT_OPTIONS.find(item => item.value === event.target.value)
                  if (!option) return
                  setSort(option.value)
                  goToPage(1)
                }}
              >
                {BOSS_HISTORY_SORT_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </div>
            <Button type="button" variant="outline" size="sm" onClick={refreshData}><BiRefresh />刷新数据</Button>
            <Button type="button" size="sm" onClick={exportCSV} disabled={exporting}>
              <BiDownload />{exporting ? "正在导出…" : "导出 CSV"}
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {listError && <p role="alert" className="mb-3 text-sm text-destructive">岗位数据同步失败，请稍后重试。</p>}
          {exportError && <p role="alert" className="mb-3 text-sm text-destructive">CSV 导出失败，请稍后重试。</p>}
          <BossJobHistoryTable items={currentList?.items ?? []} loading={!currentList} error={listError} scrollKey={listUrl} sort={sort} />
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm">
            <p className="text-muted-foreground tabular-nums">{currentList ? "共 " + total.toLocaleString("zh-CN") + " 条岗位" : "—"}</p>
            <div className="flex flex-wrap items-center gap-2">
              <Label htmlFor="boss-history-size" className="text-muted-foreground">每页</Label>
              <Select id="boss-history-size" value={size} className="h-8 w-[5.25rem]"
                onChange={event => { setSize(Number(event.target.value)); goToPage(1) }}
              >
                {[20, 50, 100, 200].map(count => <option key={count} value={count}>{count} 条</option>)}
              </Select>
              <Button type="button" size="sm" variant="outline" onClick={() => goToPage(1)} disabled={!currentList || page <= 1}>首页</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => goToPage(page - 1)} disabled={!currentList || page <= 1}>上一页</Button>
              <Label htmlFor="boss-history-page" className="sr-only">页码</Label>
              <Input id="boss-history-page" type="number" min={1} max={pageCount} value={inputPage} className="h-8 w-[4rem]"
                onChange={event => setInputPage(event.target.value)}
                onKeyDown={event => { if (event.key === "Enter") goToPage(Math.trunc(Number(inputPage)) || 1) }}
              />
              <span className="tabular-nums text-muted-foreground">/ {pageCount} 页</span>
              <Button type="button" size="sm" variant="outline" onClick={() => goToPage(page + 1)} disabled={!currentList || page >= pageCount}>下一页</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => goToPage(pageCount)} disabled={!currentList || page >= pageCount}>末页</Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 图表区：6个图表（已移除每日趋势与HR活跃度） */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiPieChart /> 投递状态分布</CardTitle>
            <CardDescription>已投递/未投递/已过滤/失败等占比</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas
                type="pie"
                labels={stats.charts.byStatus.map((x) => x.name)}
                data={stats.charts.byStatus.map((x) => x.value)}
              />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">
                加载中...
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 行业TOP10</CardTitle>
            <CardDescription>岗位按行业聚合</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas
                type="bar"
                labels={stats.charts.byIndustry.map((x) => x.name)}
                data={stats.charts.byIndustry.map((x) => x.value)}
                colors={CATEGORY_COLORS}
              />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">加载中...</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 公司岗位数TOP10</CardTitle>
            <CardDescription>按公司名称聚合</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas type="bar" labels={stats.charts.byCompany.map((x) => x.name)} data={stats.charts.byCompany.map((x) => x.value)} colors={CATEGORY_COLORS} />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">加载中...</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 经验分布</CardTitle>
            <CardDescription>不同经验要求的岗位数</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas type="bar" labels={stats.charts.byExperience.map((x) => x.name)} data={stats.charts.byExperience.map((x) => x.value)} colors={CATEGORY_COLORS} />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">加载中...</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 学历分布</CardTitle>
            <CardDescription>不同学历要求的岗位数</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas type="bar" labels={stats.charts.byDegree.map((x) => x.name)} data={stats.charts.byDegree.map((x) => x.value)} colors={CATEGORY_COLORS} />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">加载中...</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiLineChart /> 薪资区间分布</CardTitle>
            <CardDescription>基于中位数K的桶聚合</CardDescription>
          </CardHeader>
          <CardContent>
            {stats ? (
              <ChartCanvas type="line" labels={stats.charts.salaryBuckets.map((x) => x.bucket)} data={stats.charts.salaryBuckets.map((x) => x.value)} color="#ef4444" />
            ) : (
              <div className="h-64 flex items-center justify-center border-2 border-dashed rounded-lg text-muted-foreground">加载中...</div>
            )}
          </CardContent>
        </Card>
      </div>

    </div>
  )
}

export default memo(AnalysisContent)
