'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import Image from 'next/image'
import { createPortal } from 'react-dom'
import { BiBriefcase, BiSave, BiSearch, BiMoney, BiBuilding, BiTrash, BiPlus, BiPlay, BiStop, BiLogOut, BiUpload, BiDownload, BiZoomIn, BiX } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useWorkspaceEditor, useWorkspaceEdits, WorkspaceSaveBar } from '@/app/components/WorkspaceEdits'
import { writeWorkspaceJson } from '@/lib/workspace-save'
import { LayoutDashboard, SlidersHorizontal, Sparkles, MessageSquare, MessageCircleMore, ShieldBan, ChartNoAxesCombined, UsersRound, ArrowUpRight, MapPin, Check, FileText } from 'lucide-react'
import type { ReactNode } from 'react'
import AnalysisContent from '@/app/boss/analysis/AnalysisContent'
import { API_BASE } from '@/lib/api'
import { useBossProgress } from '@/lib/use-boss-progress'
import { bossHistoryProgressKey } from '@/lib/boss-progress'
import DeliveryProgress from './DeliveryProgress'
import BossReviewPanel from './BossReviewPanel'
import BossCityPlan from './BossCityPlan'
import BossBlacklistCopy from './BossBlacklistCopy'
import BossFollowUpSettings from './BossFollowUpSettings'
import BossFollowUpLog from './BossFollowUpLog'
import { defaultFollowUpPolicy, followUpError, type FollowUpPolicy } from '@/lib/boss-follow-up'
import type { useBossFollowUp } from '@/lib/use-boss-follow-up'
import BossHrActivitySettings, { defaultHrActivityPolicy, type HrActivityPolicy } from './BossHrActivitySettings'
import BossSalarySettings from './BossSalarySettings'
import { defaultSalaryPolicy, salaryPolicyError, type SalaryPolicy } from '@/lib/boss-salary'
import { citySequence, defaultCityPolicy, type CitySwitchPolicy } from '@/lib/boss-cities'
import AccountsWorkspace, { BossProfileSettings } from './AccountsWorkspace'
import { bossUrl, type BossQueue } from '@/lib/boss-accounts'

interface BossConfig {
  id?: number
  debugger?: number
  waitTime?: number
  keywords?: string
  cityCode?: string
  citySwitchPolicy?: CitySwitchPolicy
  industry?: string
  jobType?: string
  experience?: string
  degree?: string
  salary?: string
  salaryPolicy?: SalaryPolicy
  scale?: string
  stage?: string
  sayHi?: string
  enableAi?: number
  sendImgResume?: number
  allowRepeatChat?: number
  repeatChatMessages?: string
  followUpPolicy?: FollowUpPolicy
  filterDeadHr?: number
  hrActivityPolicy?: HrActivityPolicy
  deadStatus?: string
}

interface BossOption {
  id: number
  type: string
  name: string
  code: string
  // 可选的排序字段（来自后端/数据库），用于前端排序显示
  sort_order?: number
  sortOrder?: number
}

interface BossOptions {
  city: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  jobType: BossOption[]
  salary: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
}

interface BlacklistItem {
  id: number
  value: string
  type: string
}

function normalizeBlacklist(items: BlacklistItem[]): BlacklistItem[] {
  return items.map(item => item.type === 'boss' ? { ...item, type: 'company' } : item)
}

interface ResumeImageInfo {
  exists: boolean
  fileName?: string
  contentType?: string
  size?: number
  updatedAt?: number
  previewUrl?: string
}

  // 工具：解析括号列表字符串为数组，如 "[a,b]" 或 "a,b"
function parseListString(raw?: string): string[] {
    if (!raw) return []
    let s = raw.trim()
    if (s.startsWith('[') && s.endsWith(']')) {
      s = s.slice(1, -1)
    }
    if (!s) return []
    return s
      .split(',')
      .map(v => v.trim().replace(/^"|"$/g, ''))
      .filter(v => v.length > 0)
  }

  // 工具：将数组转为括号列表字符串
function toBracketList(list: string[]): string {
    if (!list || list.length === 0) return ''
    return `[${list.join(',')}]`
  }


export default function BossPage() {
  return <AccountsWorkspace>{(accountId, queue, accountName, management, initialSection, followUp) => <BossAccountPage key={accountId}
    accountId={accountId} queue={queue} accountName={accountName} management={management} initialSection={initialSection} followUp={followUp} />}</AccountsWorkspace>
}

function BossAccountPage({ accountId, queue, accountName, management, initialSection, followUp }: {
  accountId: number; queue: BossQueue | null; accountName: string; management: ReactNode; initialSection: string
  followUp: ReturnType<typeof useBossFollowUp>
}) {
  const [section, setSection] = useState(initialSection)
  const { saving, editors } = useWorkspaceEdits()
  const hasUnsaved = editors.some(editor => editor.dirty)
  const editorsReady = editors.length >= 3 && editors.every(editor => editor.ready)
  const [savedDraft, setSavedDraft] = useState<string | null>(null)
  const [blacklistError, setBlacklistError] = useState('')
  const [blacklistNotice, setBlacklistNotice] = useState('')
  const [blacklistPending, setBlacklistPending] = useState<'edit' | 'copy' | 'import' | 'export' | null>(null)
  const blacklistFileInput = useRef<HTMLInputElement>(null)
  const [config, setConfig] = useState<BossConfig>({
    keywords: '',
    cityCode: '',
    industry: '',
    jobType: '',
    experience: '',
    degree: '',
    salary: '',
    scale: '',
    stage: '',
    sendImgResume: 0,
    allowRepeatChat: 0,
    filterDeadHr: 0,
  })
  // 关键词显示用（无括号无引号，逗号分隔）
  const [keywordsDisplay, setKeywordsDisplay] = useState<string>('')
  // 多选选中的代码集合（按括号列表保存）
  const [selectedIndustry, setSelectedIndustry] = useState<string[]>([])
  const [selectedExperience, setSelectedExperience] = useState<string[]>([])
  const [selectedDegree, setSelectedDegree] = useState<string[]>([])
  const [selectedScale, setSelectedScale] = useState<string[]>([])
  const [selectedStage, setSelectedStage] = useState<string[]>([])
  const [selectedSalary, setSelectedSalary] = useState<string[]>([])
  const [options, setOptions] = useState<BossOptions>({
    city: [],
    industry: [],
    experience: [],
    jobType: [],
    salary: [],
    degree: [],
    scale: [],
    stage: [],
  })
  const [blacklist, setBlacklist] = useState<BlacklistItem[]>([])
  const [newBlacklistKeyword, setNewBlacklistKeyword] = useState('')
  const [blacklistType, setBlacklistType] = useState('company') // 默认为公司
  const [loading, setLoading] = useState(true)
  const [configError, setConfigError] = useState<string | null>(null)
  const { status: taskStatus, connection: progressConnection, error: progressError, refreshStatus } = useBossProgress(accountId)
  const isLoggedIn = taskStatus?.isLoggedIn ?? false
  const actualQueue = taskStatus?.queue?.instanceId === queue?.instanceId && (taskStatus?.queue?.revision ?? -1) >= (queue?.revision ?? 0)
    ? taskStatus?.queue : queue ?? taskStatus?.queue
  const isDelivering = actualQueue ? actualQueue.isRunning && actualQueue.activeAccountId === accountId : (taskStatus?.isRunning ?? false)
  const isStopping = actualQueue?.state === 'stopping' || taskStatus?.progress.state === 'stopping'
  const [deliveryCommand, setDeliveryCommand] = useState<'start' | 'stop' | null>(null)
  const isFollowing = followUp.activeAccountId === accountId
  const deliveryBusy = actualQueue?.isRunning ?? taskStatus?.busy ?? isDelivering
  const isBusy = deliveryBusy || followUp.activeAccountId !== null || followUp.command === 'start'
  const deliveryLocked = actualQueue ? actualQueue.isRunning && actualQueue.items.some(item => item.accountId === accountId) : (taskStatus?.locked ?? false)
  const locked = deliveryLocked || isFollowing
  const blacklistEditingDisabled = locked || saving || blacklistPending !== null
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  // title 可选：这个弹框既用于保存结果，也用于投递启动失败等提示
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string; title?: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [resumeImage, setResumeImage] = useState<ResumeImageInfo>({ exists: false })
  const [resumeImageUploading, setResumeImageUploading] = useState(false)
  const [resumeImageMessage, setResumeImageMessage] = useState<{ success: boolean; text: string } | null>(null)
  const [localResumePreviewUrl, setLocalResumePreviewUrl] = useState<string | null>(null)
  const [showResumePreview, setShowResumePreview] = useState(false)
  const resumeImageInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    return () => {
      if (localResumePreviewUrl) URL.revokeObjectURL(localResumePreviewUrl)
    }
  }, [localResumePreviewUrl])

  useEffect(() => {
    if (!showResumePreview) return

    const previousOverflow = document.body.style.overflow
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setShowResumePreview(false)
    }
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.body.style.overflow = previousOverflow
      document.removeEventListener('keydown', handleEscape)
    }
  }, [showResumePreview])

  const fetchAllData = useCallback(async () => {
    try {
      const response = await fetch(bossUrl(`/api/boss/config`, accountId))
      if (!response.ok) throw new Error('当前账号配置读取失败')
      const data = await response.json()
      setConfigError(null)


      const fetchedResumeImage: ResumeImageInfo = data.resumeImage?.exists
        ? data.resumeImage
        : { exists: false }
      setResumeImage(fetchedResumeImage)

      if (data.config) {
        // 城市保留完整有序列表，兼容旧单值。
        const normalizeCityCode = (raw?: string): string => {
          if (!raw) return ''
          const list = parseListString(raw)
          if (list.length > 0) return `[${list.join(',')}]`
          return raw
        }
        // 规范化职位类型：后端可能返回单值或括号列表，此处取第一个值用于下拉回显
        const normalizeJobType = (raw?: string): string => {
          if (!raw) return ''
          const list = parseListString(raw)
          if (list.length > 0) return list[0]
          return raw
        }
        setConfig({
          ...data.config,
          allowRepeatChat: data.config.allowRepeatChat ?? 0,
          repeatChatMessages: data.config.repeatChatMessages ?? '',
          cityCode: normalizeCityCode(data.config.cityCode),
          jobType: normalizeJobType(data.config.jobType),
          // 没有图片时不能保留开启状态，否则开关会处于“开启但不可用”。
          sendImgResume: fetchedResumeImage.exists ? (data.config.sendImgResume ?? 0) : 0,
        })
        // 将后端存储的关键词（可能是 JSON 数组或括号列表）转为展示用逗号分隔文本
        const toDisplayKeywords = (raw?: string): string => {
          if (!raw) return ''
          const s = raw.trim()
          // 尝试作为 JSON 数组解析
          if (s.startsWith('[') && s.endsWith(']')) {
            try {
              const arr = JSON.parse(s)
              if (Array.isArray(arr)) {
                return arr.map((v) => String(v).trim()).filter((v) => v.length > 0).join(', ')
              }
            } catch {
              // 非严格 JSON，如 [a,b]，走拆括号与逗号分隔
              const inner = s.slice(1, -1)
              return inner
                .split(',')
                .map((v) => v.trim().replace(/^"|"$/g, ''))
                .filter((v) => v.length > 0)
                .join(', ')
            }
          }
          // 普通文本：直接返回，去掉多余空格
          return s
        }
        setKeywordsDisplay(toDisplayKeywords(data.config.keywords))
        // 解析括号列表为数组
        setSelectedIndustry(parseListString(data.config.industry))
        setSelectedExperience(parseListString(data.config.experience))
        setSelectedDegree(parseListString(data.config.degree))
        setSelectedScale(parseListString(data.config.scale))
        setSelectedStage(parseListString(data.config.stage))
        setSelectedSalary(parseListString(data.config.salary))
      }
      if (data.options) {
        // 按 sort_order 或固定名称顺序排序；都没有时按名称兜底
        // 城市排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const cityList = data.options.city || []
        const cityHasOrder = cityList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        let sortedCity = cityHasOrder
          ? [...cityList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx // 都无排序，保持原序
                if (ar == null) return 1 // 无排序的排在已排序之后，保持原序
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx // 稳定排序
              })
              .map(({ o }) => o)
          : cityList

        // 兜底：如果后端未返回『不限』（code='0'），前端补充一个，确保可选
        const hasUnlimitedCity = sortedCity.some((c: BossOption) => c.code === '0' || c.name === '不限')
        if (!hasUnlimitedCity) {
          sortedCity = [{ id: -1, type: 'city', name: '不限', code: '0', sortOrder: 0 }, ...sortedCity]
        }

        // 行业排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const industryList = data.options.industry || []
        const industryHasOrder = industryList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        const sortedIndustry = industryHasOrder
          ? [...industryList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx
                if (ar == null) return 1
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx
              })
              .map(({ o }) => o)
          : industryList

        setOptions({
          ...data.options,
          city: sortedCity,
          industry: sortedIndustry,
        })

        // 将配置中的中文值映射为代码，用于UI显示与选择匹配
        const toCodes = (opts: BossOption[], items: string[]) => {
          const codeSet = new Set(opts.map(o => o.code))
          return items.map(it => {
            if (codeSet.has(it)) return it
            const byName = opts.find(o => o.name === it)
            return byName ? byName.code : it
          })
        }

        // 城市：若当前为中文名，转换为对应的 code 以在下拉中回显
        const currentCityRaw = data.config?.cityCode || ''
        const normalizedCityCode = `[${toCodes(sortedCity, citySequence(currentCityRaw)).join(',')}]`
        setConfig(prev => ({ ...prev, cityCode: normalizedCityCode }))

        // 职位类型：若当前为中文名，转换为对应的 code 以在下拉中回显
        const currentJobTypeRaw = data.config?.jobType || ''
        const currentJobTypeHead = (() => {
          const list = parseListString(currentJobTypeRaw)
          return list.length > 0 ? list[0] : currentJobTypeRaw
        })()
        const jobTypeMatchByCode = (data.options.jobType || []).find((t: BossOption) => t.code === currentJobTypeHead)
        const jobTypeMatchByName = (data.options.jobType || []).find((t: BossOption) => t.name === currentJobTypeHead)
        const normalizedJobType = jobTypeMatchByCode ? jobTypeMatchByCode.code : (jobTypeMatchByName ? jobTypeMatchByName.code : '')
        setConfig(prev => ({ ...prev, jobType: normalizedJobType }))

        // HR活跃过滤开关：后端为 0/1，前端直接回显为数字
        const normalizedFilterDeadHr = (data.config?.filterDeadHr ?? 0)
        setConfig(prev => ({ ...prev, filterDeadHr: normalizedFilterDeadHr }))

        // 其它多选选项：将中文名称转换为代码以匹配 MultiSelect 的 selected
        setSelectedIndustry(toCodes(data.options.industry || [], parseListString(data.config?.industry)))
        setSelectedExperience(toCodes(data.options.experience || [], parseListString(data.config?.experience)))
        setSelectedDegree(toCodes(data.options.degree || [], parseListString(data.config?.degree)))
        setSelectedScale(toCodes(data.options.scale || [], parseListString(data.config?.scale)))
        setSelectedStage(toCodes(data.options.stage || [], parseListString(data.config?.stage)))
        setSelectedSalary(toCodes(data.options.salary || [], parseListString(data.config?.salary)))
      }
      if (data.blacklist) {
        setBlacklist(normalizeBlacklist(data.blacklist))
      }
    } catch (error) {
      console.error('Failed to fetch data:', error)
      setConfigError('当前账号配置读取失败，请重试后再编辑。')
    } finally {
      setLoading(false)
    }
  }, [accountId])

  useEffect(() => {
    let active = true
    queueMicrotask(() => { if (active) void fetchAllData() })
    return () => { active = false }
  }, [fetchAllData])



  const currentDraft = JSON.stringify({ config, keywordsDisplay, selectedIndustry, selectedExperience, selectedDegree, selectedScale, selectedStage, selectedSalary })
  useEffect(() => {
    if (loading || configError || savedDraft !== null) return
    let active = true
    queueMicrotask(() => { if (active) setSavedDraft(currentDraft) })
    return () => { active = false }
  }, [loading, configError, savedDraft, currentDraft])
  const cityRule = config.citySwitchPolicy || defaultCityPolicy
  const hrDays = (config.hrActivityPolicy || defaultHrActivityPolicy).maxDays
  const followUpPolicy = config.followUpPolicy || defaultFollowUpPolicy
  const followUpMessages = config.repeatChatMessages ?? ''
  const followUpValidation = followUpError(followUpPolicy, followUpMessages)
  const baseValidation = salaryPolicyError(config.salaryPolicy || defaultSalaryPolicy)
    || followUpValidation
    || (!Number.isInteger(hrDays) || hrDays < 1 || hrDays > 365 ? 'HR 最近活跃范围须为 1 至 365 的整数天数。' : '')
    || (!citySequence(config.cityCode).length ? '请至少选择一个投递城市。' : '')
    || (!Number.isInteger(cityRule.windowSize) || cityRule.windowSize < 5 || cityRule.windowSize > 200 || !Number.isInteger(cityRule.maxNewJobs) || cityRule.maxNewJobs < 0 || cityRule.maxNewJobs >= cityRule.windowSize ? '城市观察样本须为 5–200 的整数，新增阈值须为小于样本数的非负整数。' : '')
  const restoreDraft = (raw: string) => {
    const previous = JSON.parse(raw)
    setConfig(previous.config); setKeywordsDisplay(previous.keywordsDisplay)
    setSelectedIndustry(previous.selectedIndustry); setSelectedExperience(previous.selectedExperience)
    setSelectedDegree(previous.selectedDegree); setSelectedScale(previous.selectedScale)
    setSelectedStage(previous.selectedStage); setSelectedSalary(previous.selectedSalary)
  }
  useWorkspaceEditor({
    id: `config-${accountId}`, label: '投递与跟进设置',
    ready: !loading && !configError && savedDraft !== null,
    dirty: savedDraft !== null && currentDraft !== savedDraft, validationError: baseValidation,
    reset: () => { if (savedDraft) restoreDraft(savedDraft) },
    prepare: () => {
      const snapshot = currentDraft
      const payload: BossConfig = { ...config, keywords: keywordsDisplay,
        industry: toBracketList(selectedIndustry), experience: toBracketList(selectedExperience),
        degree: toBracketList(selectedDegree), scale: toBracketList(selectedScale),
        stage: toBracketList(selectedStage), salary: toBracketList(selectedSalary) }
      // This switch belongs to the review editor. Do not overwrite it with a stale base config.
      delete payload.enableAi
      return { save: async () => {
        await writeWorkspaceJson(bossUrl('/api/boss/config', accountId), payload)
        setSavedDraft(snapshot)
      } }
    },
  })
  const refreshBlacklist = async () => {
    const response = await fetch(bossUrl('/api/boss/config/blacklist', accountId), { cache: 'no-store' })
    if (!response.ok) throw new Error('黑名单刷新失败，请重试')
    setBlacklist(normalizeBlacklist(await response.json()))
  }

  const handleResumeImageChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    const allowedTypes = new Set(['image/png', 'image/jpeg', 'image/gif'])
    const allowedExtension = /\.(png|jpe?g|gif)$/i.test(file.name)
    if ((!allowedTypes.has(file.type) && !allowedExtension) || file.size === 0) {
      setResumeImageMessage({ success: false, text: '请选择有效的 PNG、JPG、JPEG 或 GIF 图片。' })
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      setResumeImageMessage({ success: false, text: '图片大小不能超过 10MB。' })
      return
    }

    setLocalResumePreviewUrl(URL.createObjectURL(file))
    setResumeImageUploading(true)
    setResumeImageMessage(null)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const response = await fetch(bossUrl(`/api/boss/config/resume-image`, accountId), {
        method: 'POST',
        body: formData,
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok) {
        throw new Error(data.message || `上传失败（HTTP ${response.status}）`)
      }

      setResumeImage(data.resumeImage || { exists: true })
      setLocalResumePreviewUrl(null)
      setResumeImageMessage({ success: true, text: '图片简历上传成功，可以预览或重新上传。' })
    } catch (error) {
      setLocalResumePreviewUrl(null)
      setResumeImageMessage({
        success: false,
        text: error instanceof Error ? error.message : '图片简历上传失败，请确认后端服务已启动。',
      })
    } finally {
      setResumeImageUploading(false)
    }
  }

  const formatFileSize = (size?: number) => {
    if (size == null) return ''
    return size >= 1024 * 1024
      ? `${(size / 1024 / 1024).toFixed(2)} MB`
      : `${Math.max(1, Math.round(size / 1024))} KB`
  }

  const resumePreviewUrl = localResumePreviewUrl || (
    resumeImage.exists && resumeImage.previewUrl
      ? `${API_BASE}${resumeImage.previewUrl}${resumeImage.previewUrl.includes('?') ? '&' : '?'}v=${resumeImage.updatedAt || 0}`
      : null
  )

  const handleAddBlacklist = async () => {
    if (!newBlacklistKeyword.trim() || blacklistEditingDisabled) return
    setBlacklistPending('edit'); setBlacklistError(''); setBlacklistNotice('')
    try {
      await writeWorkspaceJson(bossUrl('/api/boss/config/blacklist', accountId), { value: newBlacklistKeyword.trim(), type: blacklistType }, 'POST')
      setNewBlacklistKeyword(''); await refreshBlacklist()
    } catch (error) { setBlacklistError(error instanceof Error ? error.message : '添加黑名单失败') }
    finally { setBlacklistPending(null) }
  }
  const handleDeleteBlacklist = async (id: number) => {
    if (blacklistEditingDisabled) return
    setBlacklistPending('edit'); setBlacklistError(''); setBlacklistNotice('')
    try {
      const response = await fetch(bossUrl(`/api/boss/config/blacklist/${id}`, accountId), { method: 'DELETE' })
      if (!response.ok) throw new Error('删除黑名单失败')
      await refreshBlacklist()
    } catch (error) { setBlacklistError(error instanceof Error ? error.message : '删除黑名单失败') }
    finally { setBlacklistPending(null) }
  }

  const handleImportBlacklist = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0]
    event.currentTarget.value = ''
    if (!file || blacklistEditingDisabled) return
    setBlacklistError('')
    setBlacklistNotice('')
    if (file.size > 10 * 1024 * 1024) {
      setBlacklistError('黑名单文件大小不能超过 10 MB。')
      return
    }

    const body = new FormData()
    body.append('file', file)
    setBlacklistPending('import')
    try {
      const response = await fetch(bossUrl('/api/boss/config/blacklist/import', accountId), { method: 'POST', body })
      const result = await response.json().catch(() => null)
      if (!response.ok || !Array.isArray(result?.blacklist)) {
        throw new Error(result?.message || result?.detail || '导入黑名单失败，请检查文件后重试')
      }
      setBlacklist(normalizeBlacklist(result.blacklist))
      setBlacklistNotice(`导入完成，新增 ${result.importedCount} 条，跳过 ${result.skippedCount} 条重复项。`)
    } catch (error) {
      setBlacklistError(error instanceof Error ? error.message : '导入黑名单失败，请重试')
    } finally {
      setBlacklistPending(null)
    }
  }

  const handleExportBlacklist = async () => {
    if (blacklistPending) return
    setBlacklistError('')
    setBlacklistNotice('')
    setBlacklistPending('export')
    try {
      const response = await fetch(bossUrl('/api/boss/config/blacklist/export', accountId), { cache: 'no-store' })
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('application/json')) {
        throw new Error('导出黑名单失败，请检查服务连接后重试')
      }
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a')
      link.href = url
      link.download = 'boss_blacklist.json'
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) {
      setBlacklistError(error instanceof Error ? error.message : '导出黑名单失败，请重试')
    } finally {
      setBlacklistPending(null)
    }
  }

  const handleStartDelivery = async () => {
    if (hasUnsaved || !editorsReady || saving || isBusy || followUp.command) return
    setDeliveryCommand('start')
    try {
      const response = await fetch(bossUrl(`/api/boss/start`, accountId), {
        method: 'POST',
      })
      const data = await response.json()

      if (data.success) {
        // 启动成功：不弹框，进度通过 SSE 推送
      } else {
        // 启动失败必须让用户看见。
        // 之前这里只写 console.warn，后端返回"请先登录"或"任务已在运行中"时，
        // 界面上一点反应都没有，看着就像按钮坏了。
        console.warn('启动失败：', data.message)
        setSaveResult({ success: false, title: '启动失败', message: data.message || '启动投递失败，请查看后端日志' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to start delivery:', error)
      setSaveResult({
        success: false,
        title: '启动失败',
        message: `无法连接后端服务（${API_BASE}），请确认服务已启动`,
      })
      setShowSaveDialog(true)
    } finally {
      await refreshStatus()
      setDeliveryCommand(null)
    }
  }

  const handleStopDelivery = async () => {
    setDeliveryCommand('stop')
    try {
      const response = await fetch(bossUrl(`/api/boss/stop`, accountId), {
        method: 'POST',
      })
      const data = await response.json()

      if (!data.success && data.message !== '没有正在运行的Boss任务') {
        setSaveResult({ success: false, title: '停止失败', message: data.message || '停止请求未成功，请重试' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      console.error('Failed to stop delivery:', error)
      setSaveResult({ success: false, title: '停止请求未确认', message: '无法连接后端，任务可能仍在运行，请恢复连接后重试。' })
      setShowSaveDialog(true)
    } finally {
      await refreshStatus()
      setDeliveryCommand(null)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(bossUrl(`/api/boss/logout`, accountId), { method: 'POST' })
      const data = await response.json()
      if (data.success) {
          await refreshStatus()
        console.info('已退出登录，数据库Cookie已置空')
        setLogoutResult({ success: true, message: '已退出登录，Cookie已清空。' })
        setShowLogoutResultDialog(true)
      } else {
        console.warn('退出登录失败：', data.message)
        setLogoutResult({ success: false, message: `退出登录失败：${data.message || '服务返回异常。'}` })
        setShowLogoutResultDialog(true)
      }
    } catch (error) {
      console.error('Failed to logout:', error)
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  const followUpBlocked = isBusy ? 'Boss 浏览器正被其他任务使用，请等待任务结束。'
    : hasUnsaved || saving ? '请先保存当前账号的修改。'
    : !followUp.status || !taskStatus || savedDraft === null ? '正在读取跟进状态和账号配置。'
    : !isLoggedIn ? '请先在「账号与队列」登录当前账号。'
    : config.debugger === 1 ? '请先关闭调试模式并保存。'
    : !followUpMessages.trim() ? '请填写至少一条跟进消息并保存。'
    : followUpValidation
  const handleFollowUp = (command: 'start' | 'stop') => {
    if (command === 'start' && (followUpBlocked || deliveryCommand)) return
    setSection('overview')
    void followUp.run(command)
  }

  if (configError) return <Card><CardContent className="space-y-3 p-6"><p role="alert">{configError}</p><Button onClick={() => void fetchAllData()}>重新读取该账号配置</Button></CardContent></Card>
  if (loading) return <div className="flex min-h-64 items-center justify-center gap-3 rounded-xl border bg-card text-sm text-muted-foreground"><span className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />正在读取账号配置…</div>

  return <div className="space-y-4">
    <WorkspaceSaveBar locked={locked || !!followUp.command}>
      {isDelivering ? <Button variant="destructive" disabled={!!deliveryCommand || isStopping} onClick={() => void handleStopDelivery()}><BiStop />{isStopping ? '正在停止投递…' : '停止投递'}</Button>
        : <Button variant="outline" disabled={saving || hasUnsaved || !editorsReady || isBusy || !taskStatus || !isLoggedIn || !!deliveryCommand} title={hasUnsaved ? '请先保存全部修改' : undefined} onClick={() => void handleStartDelivery()}><BiPlay />{deliveryCommand === 'start' ? '正在启动投递…' : '开始投递'}</Button>}
      {isFollowing ? <Button variant="destructive" disabled={!!followUp.command || followUp.status?.stopping} onClick={() => handleFollowUp('stop')}><BiStop />{followUp.status?.stopping || followUp.command === 'stop' ? '正在停止跟进…' : '停止跟进'}</Button>
        : <Button variant="outline" disabled={!!followUpBlocked || !!followUp.command || !!deliveryCommand} title={followUpBlocked || undefined} onClick={() => handleFollowUp('start')}><BiPlay />{followUp.command === 'start' ? '正在启动跟进…' : '立即跟进'}</Button>}
    </WorkspaceSaveBar>
    {followUp.commandError && <p role="alert" className="rounded-lg border border-red-300 p-3 text-sm text-red-600">{followUp.commandError}</p>}
    <Tabs value={section} onValueChange={setSection}>
      <TabsList className="workspace-tabs" aria-label="Boss 工作台分区">
        {([
          ['overview', '任务概览', LayoutDashboard], ['filters', '投递筛选', SlidersHorizontal],
          ['targeting', '精准审核', Sparkles], ['messages', '招呼语与简历', MessageSquare],
          ['follow-up', '定期跟进', MessageCircleMore],
          ['blacklist', '黑名单', ShieldBan], ['analytics', '投递分析', ChartNoAxesCombined],
          ['accounts', '账号与队列', UsersRound],
        ] as const).map(([value, label, Icon]) => <TabsTrigger key={value as string} value={value as string}><Icon className="mr-2 h-4 w-4" />{label as string}</TabsTrigger>)}
      </TabsList>
      <TabsContent value="overview" forceMount className="workspace-panel">
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_270px]">
          <DeliveryProgress status={taskStatus} connection={progressConnection} error={progressError} onRefresh={refreshStatus}>
            <BossFollowUpLog accountId={accountId} status={followUp.status} loadError={followUp.loadError} visible={section === 'overview'} />
          </DeliveryProgress>
          <aside className="space-y-5">
            <Card><CardHeader><CardTitle>当前投递策略</CardTitle><CardDescription>{accountName} · {hasUnsaved ? '含未保存的修改' : '已保存配置'}</CardDescription></CardHeader><CardContent className="space-y-4">
              <div><p className="mb-2 text-xs text-muted-foreground">搜索关键词</p><div className="flex flex-wrap gap-1.5">{keywordsDisplay.split(/[,，]/).map(word => word.trim()).filter(Boolean).map(word => <span key={word} className="rounded-md border bg-muted/40 px-2 py-1 text-xs">{word}</span>)}</div></div>
              <div className="border-t pt-3"><p className="mb-2 flex items-center gap-1.5 text-xs text-muted-foreground"><MapPin className="h-3.5 w-3.5" />城市执行顺序</p><p className="text-sm leading-6">{citySequence(config.cityCode).map(code => options.city.find(option => option.code === code || option.name === code)?.name || code).join(' → ') || '尚未选择'}</p></div>
              <div className="border-t pt-3"><p className="mb-1 text-xs text-muted-foreground">期望薪资</p><p className="text-sm font-medium">{config.salaryPolicy?.mode === 'custom' ? `${config.salaryPolicy.minMonthlyK ?? '不限'} – ${config.salaryPolicy.maxMonthlyK ?? '不限'} K / 月` : selectedSalary.map(code => options.salary.find(option => option.code === code || option.name === code)?.name || code).join('、') || '不限'}</p></div>
              <div className="border-t pt-3"><p className="mb-1 text-xs text-muted-foreground">HR 活跃范围</p><p className="text-sm">{config.filterDeadHr === 1 ? `最近 ${(config.hrActivityPolicy || defaultHrActivityPolicy).maxDays} 天` : '未开启活跃过滤'}</p></div>
              <Button variant="outline" className="w-full" onClick={() => setSection('filters')}>调整投递筛选<ArrowUpRight className="h-3.5 w-3.5" /></Button>
            </CardContent></Card>
            <Card><CardHeader><CardTitle>开始前，确认这三项</CardTitle></CardHeader><CardContent className="space-y-3 text-xs text-muted-foreground"><p className="flex gap-2"><Check className={`h-4 w-4 shrink-0 ${isLoggedIn ? 'text-teal-600' : ''}`} />{isLoggedIn ? '当前账号已登录' : '到“账号与队列”扫码登录'}</p><p className="flex gap-2"><Check className={`h-4 w-4 shrink-0 ${!hasUnsaved ? 'text-teal-600' : ''}`} />{hasUnsaved ? '先保存当前修改再启动' : '投递配置已保存'}</p><p className="flex gap-2"><FileText className="h-4 w-4 shrink-0" />在“精准审核”确认目标职责</p><Button variant="ghost" size="sm" className="w-full" onClick={() => setSection('accounts')}>管理账号与投递队列 →</Button></CardContent></Card>
          </aside>
        </div>
      </TabsContent>
      <TabsContent value="filters" forceMount className="workspace-panel">
        <div className="section-heading"><div><h2>投递筛选</h2><p>设置搜索范围与基础条件，同一账号的所有城市沿用这些条件。</p></div></div>
        <fieldset disabled={locked || saving} className="form-columns disabled:opacity-60">
          <div className="space-y-5">          {/* 搜索配置 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiSearch className="text-primary" />
                搜索配置
              </CardTitle>
              <CardDescription>设置职位搜索关键词和目标城市</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="space-y-2 sm:col-span-2">
                  <Label htmlFor="keywords">搜索关键词</Label>
                  <Input
                    id="keywords"
                    value={keywordsDisplay}
                    onChange={(e) => setKeywordsDisplay(e.target.value)}
                    placeholder="例如：Java开发工程师"
                  />
                  <p className="text-xs text-muted-foreground">职位搜索的关键词</p>
                </div>

              <div className="space-y-2">
                <Label htmlFor="jobType">职位类型</Label>
                <Select
                  id="jobType"
                  value={config.jobType || ''}
                  onChange={(e) => setConfig({ ...config, jobType: e.target.value })}
                >
                  {options.jobType.map((type) => (
                    <option key={type.id} value={type.code}>
                      {type.name}
                    </option>
                  ))}
                </Select>
                <p className="text-xs text-muted-foreground">选择职位类型</p>
              </div>

              <div className="space-y-2">
                <Label>公司行业</Label>
                <MultiSelect
                  options={options.industry}
                  selected={selectedIndustry}
                    onChange={setSelectedIndustry}
                    placeholder="选择公司行业"
                  />
                  <p className="text-xs text-muted-foreground">可多选</p>
                </div>
              
              {/* HR活跃过滤开关 */}
              <div className="space-y-3 sm:col-span-2">
                <Label htmlFor="filterDeadHr">HR活跃过滤</Label>
                <Select
                  id="filterDeadHr"
                  className="max-w-xs"
                  value={String(config.filterDeadHr ?? 0)}
                  onChange={(e) => setConfig({ ...config, filterDeadHr: Number(e.target.value) })}
                >
                  <option value="0">关闭</option>
                  <option value="1">开启</option>
                </Select>
                <BossHrActivitySettings enabled={config.filterDeadHr === 1} policy={config.hrActivityPolicy} onChange={hrActivityPolicy => setConfig(previous => ({ ...previous, hrActivityPolicy }))} />
              </div>
              </div>
            </CardContent>
        </Card>

<BossCityPlan rawCities={config.cityCode} policy={config.citySwitchPolicy} options={options.city} locked={locked || saving} onChange={(cityCode, citySwitchPolicy) => setConfig(previous => ({ ...previous, cityCode, citySwitchPolicy }))} /></div><div className="space-y-5">        {/* 薪资和经验 */}
        <Card className="animate-in fade-in slide-in-from-bottom-6 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiMoney className="text-primary" />
              薪资与经验要求
            </CardTitle>
            <CardDescription>设置薪资待遇和工作经验要求</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-5">
              <div className="space-y-2">
                <Label>薪资待遇</Label>
                <BossSalarySettings policy={config.salaryPolicy} selectedNames={selectedSalary.map(code => options.salary.find(option => option.code === code || option.name === code)?.name || code)} onChange={salaryPolicy => setConfig(previous => ({ ...previous, salaryPolicy }))} />
                {(config.salaryPolicy?.mode || 'selection') === 'selection' && <>
                <MultiSelect
                  options={options.salary}
                  selected={selectedSalary}
                  onChange={setSelectedSalary}
                  placeholder="选择薪资待遇"
                />
                <p className="text-xs text-muted-foreground">多选时匹配任一档位即可。选择“不限”时仍会检查已填写的岗位薪资上下限。</p>
                </>}
              </div>
              <div className="space-y-2">
                <Label>工作经验</Label>
                <MultiSelect
                  options={options.experience}
                  selected={selectedExperience}
                  onChange={setSelectedExperience}
                  placeholder="选择工作经验"
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 公司要求 */}
        <Card className="animate-in fade-in slide-in-from-bottom-7 duration-700">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BiBuilding className="text-primary" />
              公司要求
            </CardTitle>
            <CardDescription>设置目标公司的规模和融资阶段</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>学历要求</Label>
                <MultiSelect
                  options={options.degree}
                  selected={selectedDegree}
                  onChange={setSelectedDegree}
                  placeholder="选择学历要求"
                />
              </div>

              <div className="space-y-2">
                <Label>公司规模</Label>
                <MultiSelect
                  options={options.scale}
                  selected={selectedScale}
                  onChange={setSelectedScale}
                  placeholder="选择公司规模"
                />
              </div>

              <div className="space-y-2">
                <Label>融资阶段</Label>
                <MultiSelect
                  options={options.stage}
                  selected={selectedStage}
                  onChange={setSelectedStage}
                  placeholder="选择融资阶段"
                />
              </div>
            </div>
          </CardContent>
        </Card>

</div>
        </fieldset>
      </TabsContent>
      <TabsContent value="targeting" forceMount className="workspace-panel"><BossReviewPanel accountId={accountId} locked={locked || saving} busy={isBusy} loggedIn={isLoggedIn} onRefreshTask={refreshStatus} active={section === 'targeting'} /></TabsContent>
      <TabsContent value="messages" forceMount className="workspace-panel">
        <div className="section-heading"><div><h2>招呼语与简历</h2><p>个人资料、沟通内容与发送方式仅应用于当前账号。</p></div></div>
        <div className="form-columns">
          <fieldset disabled={locked || saving} className="space-y-5 disabled:opacity-60">
            <Card><CardHeader><CardTitle>首次沟通内容</CardTitle></CardHeader><CardContent>                <div className="space-y-3">
                  <Label htmlFor="sayHi">自定义打招呼语</Label>
                  <textarea
                    id="sayHi"
                    rows={4}
                    value={config.sayHi ?? ''}
                    onChange={event => setConfig(prev => ({ ...prev, sayHi: event.target.value }))}
                    placeholder="请输入你的自我介绍和求职意向"
                    className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm leading-7 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  />
                  <p className="text-sm text-muted-foreground">首次沟通使用此文本；启用 AI 招呼语后，优先使用生成内容。修改后统一保存，下次启动时生效。</p>
                </div>
</CardContent></Card>
          </fieldset>
          <div className="space-y-5"><BossProfileSettings accountId={accountId} locked={locked || saving} /><fieldset disabled={locked || saving} className="disabled:opacity-60"><Card><CardHeader><CardTitle>图片简历与发送方式</CardTitle></CardHeader><CardContent className="space-y-4">                <div className="grid gap-4 rounded-lg border bg-muted/20 p-4 sm:grid-cols-[minmax(0,1fr)_130px]">
                  <div className="space-y-3">
                    <div>
                      <Label className="text-base font-medium">图片简历 <span className="instant-badge ml-2">上传即时生效</span></Label>
                      <p className="mt-1 text-xs leading-6 text-muted-foreground">
                        支持 PNG、JPG、JPEG、GIF，单张不超过 10MB。重新上传会替换当前图片。
                      </p>
                    </div>
                    <input
                      ref={resumeImageInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/gif,.png,.jpg,.jpeg,.gif"
                      onChange={handleResumeImageChange}
                      className="hidden"
                    />
                    <Button
                      type="button"
                      variant="outline"
                      disabled={resumeImageUploading}
                      onClick={() => resumeImageInputRef.current?.click()}
                    >
                      <BiUpload className="mr-2" />
                      {resumeImageUploading ? '正在上传…' : resumeImage.exists ? '重新上传' : '选择并上传图片'}
                    </Button>
                    {resumeImage.exists && (
                      <p className="text-xs text-muted-foreground">
                        当前文件：{resumeImage.fileName || 'resume'}{resumeImage.size != null ? `（${formatFileSize(resumeImage.size)}）` : ''}
                      </p>
                    )}
                    {resumeImageMessage && (
                      <p className={`text-sm ${resumeImageMessage.success ? 'text-green-600' : 'text-red-600'}`}>
                        {resumeImageMessage.text}
                      </p>
                    )}
                  </div>
                  <div className="flex min-h-36 items-center justify-center overflow-hidden rounded-md border bg-background">
                    {resumePreviewUrl ? (
                      <button
                        type="button"
                        onClick={() => setShowResumePreview(true)}
                        className="group relative flex h-full w-full cursor-zoom-in items-center justify-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-inset"
                        aria-label="放大查看图片简历"
                        title="点击查看大图"
                      >
                        <Image
                          src={resumePreviewUrl}
                          alt="图片简历预览"
                          width={220}
                          height={224}
                          unoptimized
                          className="max-h-56 w-full object-contain"
                        />
                        <span className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-1 bg-black/60 py-1.5 text-xs text-white opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
                          <BiZoomIn className="text-base" /> 点击查看大图
                        </span>
                      </button>
                    ) : (
                      <div className="px-4 text-center text-xs leading-6 text-muted-foreground">尚未上传图片简历</div>
                    )}
                  </div>
                </div>
                <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="space-y-1">
                    <Label htmlFor="sendImgResume" className="text-base font-medium">
                      自动发送图片简历
                    </Label>
                    <p className="text-xs leading-6 text-muted-foreground">
                      开启后，系统会在打招呼语发送成功后发送上方图片；上传图片不会自动开启。
                    </p>
                    {!resumeImage.exists && (
                      <p className="text-xs text-amber-600">请先上传图片简历，再开启此选项。</p>
                    )}
                  </div>
                  <button
                    id="sendImgResume"
                    type="button"
                    role="switch"
                    disabled={!resumeImage.exists}
                    aria-checked={config.sendImgResume === 1}
                    aria-label="自动发送图片简历"
                    onClick={() => setConfig({ ...config, sendImgResume: config.sendImgResume === 1 ? 0 : 1 })}
                    className={`relative inline-flex h-7 w-12 shrink-0 items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 ${
                      config.sendImgResume === 1 ? 'bg-primary' : 'bg-muted-foreground/40'
                    } ${resumeImage.exists ? 'cursor-pointer' : 'cursor-not-allowed opacity-50'}`}
                  >
                    <span
                      aria-hidden="true"
                      className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${
                        config.sendImgResume === 1 ? 'translate-x-5' : 'translate-x-0.5'
                      }`}
                    />
                  </button>
                </div></CardContent></Card></fieldset></div>
        </div>
      </TabsContent>
      <TabsContent value="blacklist" forceMount className="workspace-panel">
        <Card className="animate-in fade-in slide-in-from-bottom-8 duration-700">
          <CardHeader className="gap-3 space-y-0 sm:flex-row sm:items-start sm:justify-between">
            <div className="space-y-2">
              <CardTitle className="flex items-center gap-2">
                <BiSearch className="text-primary" />
                黑名单管理 ({blacklist.length} 条)
              </CardTitle>
              <CardDescription>{locked ? '当前账号有投递或跟进任务，可导出黑名单。' : '导入 JSON 文件时合并到当前账号，自动跳过重复项。黑名单修改后立即保存。'}</CardDescription>
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              <input ref={blacklistFileInput} type="file" accept=".json,application/json" aria-label="选择黑名单 JSON 文件" onChange={handleImportBlacklist} disabled={blacklistEditingDisabled} hidden />
              <Button type="button" size="sm" variant="outline" disabled={blacklistEditingDisabled} onClick={() => blacklistFileInput.current?.click()}>
                <BiUpload />{blacklistPending === 'import' ? '正在导入…' : '导入黑名单'}
              </Button>
              <Button type="button" size="sm" variant="outline" disabled={blacklistPending !== null} onClick={handleExportBlacklist}>
                <BiDownload />{blacklistPending === 'export' ? '正在导出…' : '导出黑名单'}
              </Button>
            </div>
          </CardHeader>
          <CardContent className="space-y-3">
            {blacklistNotice && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-300">{blacklistNotice}</p>}
            {blacklistError && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{blacklistError}</p>}
            <fieldset disabled={blacklistEditingDisabled} className="min-w-0 space-y-6 disabled:opacity-60">
              <BossBlacklistCopy
                accountId={accountId}
                locked={blacklistEditingDisabled}
                pending={blacklistPending === 'copy'}
                onPendingChange={pending => {
                  setBlacklistPending(pending ? 'copy' : null)
                  if (pending) { setBlacklistError(''); setBlacklistNotice('') }
                }}
                onCopied={items => setBlacklist(normalizeBlacklist(items))}
              />
              <div className="flex flex-wrap gap-2">
                <Select
                  value={blacklistType}
                  onChange={(e) => setBlacklistType(e.target.value)}
                  className="w-32"
                >
                  <option value="company">公司</option>
                  <option value="job">岗位</option>
                  <option value="recruiter">HR</option>
                </Select>
                <Input
                  value={newBlacklistKeyword}
                  onChange={(e) => setNewBlacklistKeyword(e.target.value)}
                  placeholder={`输入${blacklistType === 'company' ? '公司名称' : blacklistType === 'job' ? '岗位关键词' : 'HR职位'}关键词`}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      handleAddBlacklist()
                    }
                  }}
                />
                <Button onClick={handleAddBlacklist} className="whitespace-nowrap">
                  <BiPlus />
                  添加
                </Button>
              </div>

              {/* 黑名单列表 - 按类型分组显示 */}
              <div className="grid items-start gap-5 md:grid-cols-3">
                {/* 公司黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiBuilding className="text-orange-500" />
                    <span>公司黑名单 ({blacklist.filter(item => item.type === 'company').length})</span>
                  </h3>
                  <div className="max-h-[28rem] space-y-2 overflow-y-auto">
                    {blacklist.filter(item => item.type === 'company').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无公司黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'company').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-orange-50 dark:bg-orange-950/20 rounded-lg border border-orange-200 dark:border-orange-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            aria-label={`删除黑名单 ${item.value}`}
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                {/* 岗位黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiBriefcase className="text-blue-500" />
                    <span>岗位黑名单 ({blacklist.filter(item => item.type === 'job').length})</span>
                  </h3>
                  <div className="max-h-[28rem] space-y-2 overflow-y-auto">
                    {blacklist.filter(item => item.type === 'job').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无岗位黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'job').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-blue-50 dark:bg-blue-950/20 rounded-lg border border-blue-200 dark:border-blue-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            aria-label={`删除黑名单 ${item.value}`}
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>

                {/* HR黑名单 */}
                <div>
                  <h3 className="text-sm font-semibold mb-3 flex items-center gap-2">
                    <BiSearch className="text-green-500" />
                    <span>HR黑名单 ({blacklist.filter(item => item.type === 'recruiter').length})</span>
                  </h3>
                  <div className="max-h-[28rem] space-y-2 overflow-y-auto">
                    {blacklist.filter(item => item.type === 'recruiter').length === 0 ? (
                      <div className="text-center py-4 text-muted-foreground bg-muted/30 rounded-lg">
                        <p className="text-xs">暂无HR黑名单</p>
                      </div>
                    ) : (
                      blacklist.filter(item => item.type === 'recruiter').map((item) => (
                        <div
                          key={item.id}
                          className="flex items-center justify-between p-3 bg-green-50 dark:bg-green-950/20 rounded-lg border border-green-200 dark:border-green-800"
                        >
                          <span className="text-sm">{item.value}</span>
                          <Button
                            variant="ghost"
                            aria-label={`删除黑名单 ${item.value}`}
                            size="sm"
                            onClick={() => handleDeleteBlacklist(item.id)}
                            className="text-red-500 hover:text-red-700 hover:bg-red-50"
                          >
                            <BiTrash />
                          </Button>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </fieldset>
          </CardContent>
        </Card>
      </TabsContent>
      <TabsContent value="analytics" className="workspace-panel">
        <AnalysisContent accountId={accountId} progressKey={bossHistoryProgressKey(taskStatus?.progress)} progressConnection={progressConnection} />
      </TabsContent>
      <TabsContent value="follow-up" forceMount className="workspace-panel">
        <div className="section-heading"><div><h2>定期跟进</h2><p>跟进已投递且未收到回复的会话，包含未读和已读未回。</p></div></div>
        {!isFollowing && followUpBlocked && followUpBlocked !== followUpValidation &&
          <p className="mb-4 text-sm text-muted-foreground">{followUpBlocked}</p>}
        <BossFollowUpSettings policy={followUpPolicy} messages={followUpMessages}
          disabled={locked || saving || !!followUp.command} timeZone={followUp.status?.timeZone}
          onPolicyChange={followUpPolicy => setConfig(previous => ({ ...previous, followUpPolicy }))}
          onMessagesChange={repeatChatMessages => setConfig(previous => ({ ...previous, repeatChatMessages }))} />
      </TabsContent>
      <TabsContent value="accounts" forceMount className="workspace-panel"><div className="section-heading"><div><h2>账号与队列</h2><p>独立登录、独立配置。按设定顺序执行，A 完成后自动切换到 B。</p></div><Button variant="outline" disabled={isBusy || saving || !!deliveryCommand} onClick={() => setShowLogoutDialog(true)}><BiLogOut />退出当前账号登录</Button></div>{management}</TabsContent>
    </Tabs>
      {/* 图片简历大图预览 */}
      {showResumePreview && resumePreviewUrl && createPortal(
        <div
          className="fixed inset-0 z-[100] overflow-auto bg-black/85 p-4 backdrop-blur-sm sm:p-8"
          role="dialog"
          aria-modal="true"
          aria-label="图片简历大图预览"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setShowResumePreview(false)
          }}
        >
          <button
            type="button"
            onClick={() => setShowResumePreview(false)}
            className="fixed right-4 top-4 z-[101] inline-flex h-12 w-12 items-center justify-center rounded-full border-2 border-white bg-white text-3xl text-gray-900 shadow-[0_4px_20px_rgba(0,0,0,0.65)] transition duration-200 hover:border-red-500 hover:bg-red-500 hover:text-white hover:scale-105 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-white/70 sm:right-6 sm:top-6"
            aria-label="关闭大图预览"
            title="关闭（Esc）"
          >
            <BiX />
          </button>
          <div className="flex min-h-full items-start justify-center py-10">
            <Image
              src={resumePreviewUrl}
              alt="图片简历大图"
              width={1400}
              height={2000}
              unoptimized
              priority
              className="h-auto w-[94vw] max-w-[1200px] rounded-md bg-white shadow-2xl"
              onMouseDown={(event) => event.stopPropagation()}
            />
          </div>
        </div>,
        document.body
      )}

      {/* 统计卡片已移除 */}
      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className="text-red-500" />
                  确认退出登录
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
                <div className="flex justify-end gap-2">
                  <Button
                    variant="ghost"
                    onClick={() => setShowLogoutDialog(false)}
                    className="rounded-full px-4"
                  >
                    取消
                  </Button>
                  <Button
                    onClick={async () => {
                      await triggerLogout()
                      setShowLogoutDialog(false)
                    }}
                    className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4"
                  >
                    确认退出
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                  {logoutResult.success ? '退出登录成功' : '退出登录失败'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowLogoutResultDialog(false)}
                    className={`rounded-full px-4 ${logoutResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 保存结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" role="dialog" aria-modal="true">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800 animate-in fade-in zoom-in-95">
            <Card className="border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-lg flex items-center gap-2">
                  <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                  {saveResult.title ?? (saveResult.success ? '保存成功' : '保存失败')}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0">
                <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
                <div className="flex justify-end gap-2">
                  <Button
                    onClick={() => setShowSaveDialog(false)}
                    className={`rounded-full px-4 ${saveResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`}
                  >
                    知道了
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
  </div>
}

// 多选下拉组件（简单版），按代码选择并显示名称
function MultiSelect({ options, selected, onChange, placeholder, onClose }: {
  options: BossOption[]; selected: string[]; onChange: (values: string[]) => void; placeholder?: string; onClose?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState({ top: 0, left: 0, width: 0 })
  const button = useRef<HTMLButtonElement>(null)
  const dropdown = useRef<HTMLDivElement>(null)
  const updatePosition = useCallback(() => {
    const rect = button.current?.getBoundingClientRect()
    if (!rect) return
    const height = Math.min(224, window.innerHeight - 24)
    const below = window.innerHeight - rect.bottom
    setPosition({ top: below >= height || rect.top < height ? rect.bottom + 6 : Math.max(8, rect.top - height - 6), left: Math.max(8, Math.min(rect.left, window.innerWidth - rect.width - 8)), width: rect.width })
  }, [])
  useEffect(() => {
    if (!open) return
    const outside = (event: MouseEvent) => {
      if (!button.current?.contains(event.target as Node) && !dropdown.current?.contains(event.target as Node)) { setOpen(false); onClose?.() }
    }
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); onClose?.(); button.current?.focus() } }
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', escape); window.removeEventListener('resize', updatePosition); window.removeEventListener('scroll', updatePosition, true) }
  }, [open, onClose, updatePosition])
  const names = options.filter(option => selected.includes(option.code)).map(option => option.name)
  return <div className="relative min-w-0">
    <button ref={button} type="button" aria-label={placeholder} aria-haspopup="listbox" aria-expanded={open} onClick={() => { if (!open) updatePosition(); else onClose?.(); setOpen(value => !value) }} className="flex h-10 w-full items-center justify-between gap-2 rounded-md border border-input bg-background px-3 text-left text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <span className="truncate">{names.join('、') || placeholder || '请选择'}</span><span className="shrink-0 text-[10px] text-muted-foreground">{open ? '▲' : '▼'}</span>
    </button>
    {open && createPortal(<div ref={dropdown} role="listbox" aria-label={placeholder} aria-multiselectable="true" className="dropdown-panel p-1.5" style={position}>
      {options.map(option => <button key={option.id} type="button" role="option" aria-selected={selected.includes(option.code)} onClick={() => onChange(selected.includes(option.code) ? selected.filter(code => code !== option.code) : [...selected, option.code])} className="flex w-full items-center gap-2.5 rounded-md px-2 py-2 text-left text-sm hover:bg-muted focus:bg-muted focus:outline-none"><span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${selected.includes(option.code) ? 'border-primary bg-primary text-primary-foreground' : 'border-input'}`}>{selected.includes(option.code) && <Check className="h-3 w-3" />}</span>{option.name}</button>)}
    </div>, document.body)}
  </div>
}
