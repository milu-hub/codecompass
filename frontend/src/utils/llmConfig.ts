/**
 * 用户自填 LLM 配置的**纯逻辑**（无 Vue 依赖，API 层可直接引用）。
 *
 * <p>支持**多套配置**并一键切换。localStorage 结构：
 * <pre>
 * {
 *   "activeId": "deepseek-1",
 *   "profiles": [ { "id", "name", "provider", "baseUrl", "apiKey", "model" } ]
 * }
 * </pre>
 *
 * **存储位置：只存浏览器 localStorage（key = `cc_llm_config`），服务端不持久化。**
 * 四个硬约束：
 * 1. key 只在浏览器本地，绝不写进数据库；
 * 2. 界面上只显示掩码（`sk-***abc`）；
 * 3. 请求时才读出来放进请求头，不放 URL 参数、不记日志；
 * 4. 兼容旧的「单套扁平配置」——读到就用它迁移成一条 profile，不让老用户丢配置。
 */

/** localStorage 键名（与前端其余持久化键的 `cc_` 前缀保持一致）。 */
export const LLM_CONFIG_STORAGE_KEY = 'cc_llm_config'

/** 一套 LLM 配置。 */
export interface LlmProfile {
  id: string
  name: string
  provider: string
  baseUrl: string
  apiKey: string
  model: string
}

/** 全部配置 + 当前启用哪一套。 */
export interface LlmConfigStore {
  activeId: string | null
  profiles: LlmProfile[]
}

/** Provider 预设：选中后自动填充 baseUrl / model（「自定义」留空由用户自填）。 */
export interface ProviderPreset {
  id: string
  label: string
  baseUrl: string
  model: string
}

export const PROVIDER_PRESETS: readonly ProviderPreset[] = [
  { id: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { id: 'qwen', label: '通义', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { id: 'kimi', label: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { id: 'custom', label: '自定义', baseUrl: '', model: '' },
]

/** 按 id 取预设；未知 id 返回「自定义」而不是 undefined。 */
export function presetOf(id: string): ProviderPreset {
  return PROVIDER_PRESETS.find((preset) => preset.id === id)
    ?? PROVIDER_PRESETS[PROVIDER_PRESETS.length - 1]
}

/**
 * 掩码：`sk-1234567890abc` → `sk-***abc`（首 3 + `***` + 尾 3）。
 * 太短（≤ 6 字）一律只给 `***`，宁可少显示也不泄露。
 */
export function maskApiKey(key: string): string {
  const trimmed = (key ?? '').trim()
  if (!trimmed) {
    return ''
  }
  if (trimmed.length <= 6) {
    return '***'
  }
  return `${trimmed.slice(0, 3)}***${trimmed.slice(-3)}`
}

/** 一套配置能否真正用于出站：key 与 baseUrl 都非空。 */
export function isUsable(profile: LlmProfile | null | undefined): boolean {
  return !!profile && !!profile.apiKey.trim() && !!profile.baseUrl.trim()
}

/** 展示名：用户起的名字优先，没名字就用 provider 的显示名。 */
export function profileLabel(profile: LlmProfile): string {
  return profile.name.trim() || presetOf(profile.provider).label
}

/** 生成 profile id：优先 crypto.randomUUID，退化为时间戳 + 随机串。 */
export function newProfileId(): string {
  const webCrypto = globalThis.crypto
  if (webCrypto && typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID()
  }
  return `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/** 新配置的默认名：`DeepSeek`、重名时 `DeepSeek 2`。 */
export function defaultProfileName(provider: string, existing: readonly LlmProfile[]): string {
  const base = presetOf(provider).label
  const taken = new Set(existing.map((profile) => profile.name))
  if (!taken.has(base)) {
    return base
  }
  let index = 2
  while (taken.has(`${base} ${index}`)) {
    index++
  }
  return `${base} ${index}`
}

export function emptyStore(): LlmConfigStore {
  return { activeId: null, profiles: [] }
}

function str(raw: Record<string, unknown>, key: string): string {
  return typeof raw[key] === 'string' ? (raw[key] as string) : ''
}

/** 归一化单条；整体空白的条目返回 null（旧格式里的空配置不该留成幽灵行）。 */
function normalizeProfile(value: unknown): LlmProfile | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const raw = value as Record<string, unknown>
  const provider = str(raw, 'provider') || 'custom'
  const profile: LlmProfile = {
    id: str(raw, 'id') || newProfileId(),
    name: str(raw, 'name') || presetOf(provider).label,
    provider,
    baseUrl: str(raw, 'baseUrl'),
    apiKey: str(raw, 'apiKey'),
    model: str(raw, 'model'),
  }
  const meaningful = profile.apiKey.trim() || profile.baseUrl.trim() || profile.model.trim()
  return meaningful ? profile : null
}

/** 解析任意历史形态 → 当前结构。 */
function migrate(value: unknown): LlmConfigStore {
  if (!value || typeof value !== 'object') {
    return emptyStore()
  }
  const raw = value as Record<string, unknown>

  if (Array.isArray(raw.profiles)) {
    const profiles = raw.profiles
      .map(normalizeProfile)
      .filter((profile): profile is LlmProfile => profile !== null)
    const activeId = str(raw, 'activeId')
    return {
      // activeId 指向不存在的条目时归零，避免"当前配置"悬空
      activeId: profiles.some((profile) => profile.id === activeId) ? activeId : null,
      profiles,
    }
  }

  // 旧格式：单套扁平 {provider, baseUrl, apiKey, model} → 迁移成一条并置为当前
  const legacy = normalizeProfile(raw)
  return legacy ? { activeId: legacy.id, profiles: [legacy] } : emptyStore()
}

/** 读 localStorage；没有 / 坏 JSON 返回空 store（绝不抛错打断页面）。 */
export function readStore(): LlmConfigStore {
  try {
    const raw = window.localStorage.getItem(LLM_CONFIG_STORAGE_KEY)
    return raw ? migrate(JSON.parse(raw)) : emptyStore()
  } catch {
    return emptyStore()
  }
}

/** 写 localStorage；写失败（隐私模式）静默忽略，不打断主流程。 */
export function writeStore(store: LlmConfigStore): void {
  try {
    window.localStorage.setItem(LLM_CONFIG_STORAGE_KEY, JSON.stringify(store))
  } catch {
    // 同上
  }
}

/** 当前启用的那套；未配置 / 该套不完整（缺 key 或 baseUrl）时返回 null。 */
export function activeProfile(): LlmProfile | null {
  const store = readStore()
  const profile = store.profiles.find((item) => item.id === store.activeId) ?? null
  return isUsable(profile) ? profile : null
}

/**
 * 构造问答请求要带的三个 LLM 请求头（读**当前启用**的那套）。
 *
 * <p>没有可用配置时返回**空对象**：一个头都不发，后端据此回落服务端环境变量额度。
 * 用请求头而不是 URL 参数，避免 key 被代理访问日志记录。
 */
export function llmRequestHeaders(): Record<string, string> {
  const profile = activeProfile()
  if (!profile) {
    return {}
  }
  return {
    'X-LLM-Api-Key': profile.apiKey.trim(),
    'X-LLM-Base-Url': profile.baseUrl.trim(),
    'X-LLM-Model': profile.model.trim(),
  }
}

// ---------- CRUD（纯函数：入参 store 出参新 store，便于组合与被响应式层复用） ----------

/** 新增一套；原先一套都没有时，新建的自动成为当前启用。 */
export function addProfile(
  store: LlmConfigStore,
  draft: Omit<LlmProfile, 'id'>,
): { store: LlmConfigStore; profile: LlmProfile } {
  const profile: LlmProfile = { id: newProfileId(), ...draft }
  return {
    store: { activeId: store.activeId ?? profile.id, profiles: [...store.profiles, profile] },
    profile,
  }
}

/** 覆盖更新同 id 的那套。 */
export function updateProfile(store: LlmConfigStore, profile: LlmProfile): LlmConfigStore {
  return {
    ...store,
    profiles: store.profiles.map((item) => (item.id === profile.id ? profile : item)),
  }
}

/**
 * 删除一套。**正在使用的那套不允许删**（ok=false）——
 * 比"删完自动切到第一套"更可预测，不会让生效配置在你没注意时被换掉。
 */
export function removeProfile(store: LlmConfigStore, id: string): { store: LlmConfigStore; ok: boolean } {
  if (store.activeId === id) {
    return { store, ok: false }
  }
  return { store: { ...store, profiles: store.profiles.filter((item) => item.id !== id) }, ok: true }
}

/** 切换当前启用；id 不存在时原样返回。 */
export function setActive(store: LlmConfigStore, id: string): LlmConfigStore {
  return store.profiles.some((item) => item.id === id) ? { ...store, activeId: id } : store
}
