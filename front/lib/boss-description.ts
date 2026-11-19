const COMPANY_TITLES = ["公司简介", "公司介绍", "公司概况", "企业简介", "企业介绍", "关于我们", "about us"]
const JOB_TITLES = [
  "岗位(?:boss)?介绍", "职位介绍", "岗位职责", "职位职责", "工作职责", "工作内容",
  "任职要求", "任职资格", "任职条件", "岗位要求", "职位要求", "招聘要求",
  "加分项", "加分条件", "优先条件", "福利待遇", "responsibilities", "qualifications",
]
const SECTION_NUMBER = String.raw`(?:[一二三四五六七八九十⼀⼆⼋⼗]+[、.．]|[1-9１-９][、.．])`
const HEADING = new RegExp(String.raw`(${SECTION_NUMBER}[ \t\u3000]*)?([【「\[])?(${[...JOB_TITLES, ...COMPANY_TITLES].join("|")})[】」\]]?`, "gi")
const ITEM_MARKER = String.raw`(?:[（(][0-9０-９]{1,2}[）)]|[0-9０-９]{1,2}[.．、)）](?![0-9０-９])|[•●▪◦]|[-*](?=[ \t\u3000\p{Script=Han}]))`
const ITEM_START = new RegExp(String.raw`^(${ITEM_MARKER})[ \t\u3000]*`, "u")
const ITEM_BOUNDARY = new RegExp(String.raw`[\r\n]+|(?<=[。；;！？：:])(?=[ \t\u3000]*${ITEM_MARKER})`, "u")

type DescriptionItem = { text: string; number?: number }
type DescriptionBlock =
  | { kind: "paragraph"; text: string }
  | { kind: "list"; ordered: boolean; items: DescriptionItem[] }

export type BossDescriptionSection = { heading: string; blocks: DescriptionBlock[] }

type Boundary = { start: number; bodyStart: number; heading: string; company: boolean }

function paragraphBlocks(line: string): DescriptionBlock[] {
  if (line.length < 180) return [{ kind: "paragraph", text: line }]
  const blocks: DescriptionBlock[] = []
  let paragraph = ""
  for (const sentence of line.match(/[^。！？]+[。！？]*|[。！？]+/gu) ?? []) {
    paragraph += sentence
    if (paragraph.length >= 140) {
      blocks.push({ kind: "paragraph", text: paragraph.trim() })
      paragraph = ""
    }
  }
  if (paragraph.trim()) blocks.push({ kind: "paragraph", text: paragraph.trim() })
  return blocks
}

function formatBody(text: string): DescriptionBlock[] {
  const blocks: DescriptionBlock[] = []
  for (const segment of text.split(ITEM_BOUNDARY)) {
    const line = segment.trim()
    if (!line) continue
    const marker = ITEM_START.exec(line)
    const content = marker ? line.slice(marker[0].length).trim() : ""
    if (!marker || !content) {
      blocks.push(...paragraphBlocks(line))
      continue
    }
    const number = marker[1].normalize("NFKC").match(/\d+/)?.[0]
    const item = number === undefined ? { text: content } : { text: content, number: Number(number) }
    const ordered = number !== undefined
    const previous = blocks.at(-1)
    if (previous?.kind === "list" && previous.ordered === ordered) previous.items.push(item)
    else blocks.push({ kind: "list", ordered, items: [item] })
  }
  return blocks
}

function findHeadings(description: string): Boundary[] {
  const boundaries: Boundary[] = []
  for (const match of description.matchAll(HEADING)) {
    const start = match.index
    const end = start + match[0].length
    const after = description.slice(end)
    const previousLineEnd = Math.max(description.lastIndexOf("\n", start - 1), description.lastIndexOf("\r", start - 1))
    const startsLine = !description.slice(previousLineEnd + 1, start).trim()
    const separator = /^[ \t\u3000]*[:：][ \t\u3000]*/u.exec(after)
    if (!match[1] && !match[2] && !startsLine && !separator && !ITEM_START.test(after.trimStart())) continue
    boundaries.push({
      start,
      bodyStart: end + (separator?.[0].length ?? 0),
      heading: match[0].trim(),
      company: COMPANY_TITLES.includes(match[3].toLowerCase()),
    })
  }
  return boundaries
}

function companyIntroductionStart(description: string, companyName: string): number {
  if (!companyName.trim()) return -1
  const name = companyName.trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
  const match = new RegExp(String.raw`(^|[\r\n])([ \t\u3000]*)${name}\s*(?:成立于|创立于|始创于|于\d{4}年成立|是一家)`, "u").exec(description)
  return match ? match.index + match[1].length + match[2].length : -1
}

export function formatBossDescription(description?: string | null, introduction?: string | null, companyName?: string | null) {
  const source = description ?? ""
  const intro = introduction?.trim() ?? ""
  const boundaries = findHeadings(source)
  const trimmed = source.trimEnd()
  // 有些历史 JD 把公司介绍作为独立段落接在末尾，使用已有简介或明确的公司名开头来确认。
  const knownIntro = intro.length >= 16 && trimmed.endsWith(intro) ? trimmed.length - intro.length : -1
  for (const start of [companyIntroductionStart(source, companyName ?? ""), knownIntro].sort((a, b) => a - b)) {
    if (start < 0 || boundaries.some(boundary => boundary.start === start)) continue
    const previous = boundaries.findLast(boundary => boundary.start < start)
    if (!previous?.company) {
      boundaries.push({ start, bodyStart: start, heading: "", company: true })
      boundaries.sort((a, b) => a.start - b.start)
    }
  }
  if (boundaries[0]?.start !== 0) boundaries.unshift({ start: 0, bodyStart: 0, heading: "", company: false })

  const jobSections: BossDescriptionSection[] = []
  const companySections: BossDescriptionSection[] = []
  const jobText: string[] = []
  const companyText: string[] = []
  const embeddedIntros: string[] = []
  boundaries.forEach((boundary, index) => {
    const end = boundaries[index + 1]?.start ?? source.length
    const body = source.slice(boundary.bodyStart, end).trim()
    if (!body && !boundary.heading) return
    const section = { heading: boundary.heading, blocks: formatBody(body) }
    const original = source.slice(boundary.start, end)
    if (boundary.company) {
      embeddedIntros.push(body)
      if (!body) return
      companySections.push(section)
      companyText.push(original.trim())
    } else {
      jobSections.push(section)
      jobText.push(original)
    }
  })
  const comparable = (text: string) => text.replace(/\s+/gu, "")
  if (intro && !embeddedIntros.some(text => comparable(text) === comparable(intro))) {
    companySections.unshift({ heading: "", blocks: formatBody(intro) })
    companyText.unshift(intro)
  }
  return {
    jobSections, companySections,
    jobText: embeddedIntros.length ? jobText.join("").trim() : source,
    companyText: companyText.join("\n\n"),
  }
}
