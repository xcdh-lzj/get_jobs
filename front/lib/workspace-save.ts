export interface WorkspaceEditor {
  id: string
  label: string
  dirty: boolean
  ready: boolean
  validationError?: string
  prepare: () => { save: () => Promise<void> }
  reset: () => void
}

export interface WorkspaceSaveResult {
  saved: string[]
  failed: { label: string; message: string }[]
}

// Validate every draft and capture every payload before making the first write.
// A failed section stays dirty; successful sections are not submitted on retry.
export async function saveWorkspace(editors: WorkspaceEditor[]): Promise<WorkspaceSaveResult> {
  const unavailable = editors.find(editor => !editor.ready)
  if (unavailable) throw new Error(`${unavailable.label}尚未读取完成，请重试读取后保存。`)
  const changed = editors.filter(editor => editor.dirty)
  const invalid = changed.find(editor => editor.validationError)
  if (invalid) throw new Error(`${invalid.label}：${invalid.validationError}`)
  const prepared = changed.map(editor => ({ label: editor.label, ...editor.prepare() }))
  const result: WorkspaceSaveResult = { saved: [], failed: [] }
  for (const section of prepared) {
    try {
      await section.save()
      result.saved.push(section.label)
    } catch (error) {
      result.failed.push({ label: section.label, message: error instanceof Error ? error.message : '保存失败' })
    }
  }
  return result
}

export async function writeWorkspaceJson(url: string, body: unknown, method = 'PUT') {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(url, {
      method, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: controller.signal,
    })
    const data = await response.json().catch(() => null)
    if (!response.ok || data?.success === false) throw new Error(data?.message || data?.detail || '保存失败，请检查服务连接或账号锁定状态')
    return data
  } finally { clearTimeout(timeout) }
}
