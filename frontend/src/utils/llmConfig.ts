/**
 * 用户自填 LLM 配置的**纯逻辑**（无 Vue 依赖，API 层可直接引用）。
 *
 * 响应式包装见 `composables/useLlmConfig.ts`；这里只放存储、预设、掩码、请求头构造，
 * 让 `api/` 层不必为了读一个 localStorage 而依赖 Vue。
 *
 * **存储位置：只存浏览器 localStorage（key = `cc_llm_config`），服务端不持久化。**
 * 三个硬约束：
 * 1. key 只在浏览器本地，绝不写进数据库；
 * 2. 界面上只显示掩码（`sk-***abc`）；
 * 3. 请求时才读出来放进请求头，不放 URL 参数、不记日志。
 */

/** localStorage 键名（与前端其余持久化键的 `cc_` 前缀保持一致）。 */
export const LLM_CONFIG_STORAGE_KEY = 'cc_llm_config'

/** 一份用户自填配置。 */
export interface LlmConfig {
  provider: string
  baseUrl: string
  apiKey: string
  model: string
}

/** Provider 预设：选中后自动填充 baseUrl / model（「自定义」留空由用户自填）。 */
export interface ProviderPreset {
  id: string
  label: string
  baseUrl: string
  model: string
}

export const PROVIDER_PRESETS: readonly ProviderPreset[] = [
  {
    id: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
  },
  {
    id: 'openai',
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini',
  },
  {
    id: 'qwen',
    label: '通义',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen-plus',
  },
  {
    id: 'kimi',
    label: 'Kimi',
    baseUrl: 'https://api.moonshot.cn/v1',
    model: 'moonshot-v1-8k',
  },
  {
    id: 'custom',
    label: '自定义',
    baseUrl: '',
    model: '',
  },
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

function normalize(value: unknown): LlmConfig | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const raw = value as Partial<Record<keyof LlmConfig, unknown>>
  const config: LlmConfig = {
    provider: typeof raw.provider === 'string' ? raw.provider : 'custom',
    baseUrl: typeof raw.baseUrl === 'string' ? raw.baseUrl : '',
    apiKey: typeof raw.apiKey === 'string' ? raw.apiKey : '',
    model: typeof raw.model === 'string' ? raw.model : '',
  }
  return config.apiKey.trim() ? config : null
}

/** 读 localStorage；没有 / 坏 JSON / 无 key 一律返回 null（视为未配置）。 */
export function readLlmConfig(): LlmConfig | null {
  try {
    const raw = window.localStorage.getItem(LLM_CONFIG_STORAGE_KEY)
    return raw ? normalize(JSON.parse(raw)) : null
  } catch {
    // 坏数据当作未配置，不抛错打断页面
    return null
  }
}

/** 写 localStorage。apiKey 为空视为「清除」而不是写入一份空配置。 */
export function writeLlmConfig(config: LlmConfig): void {
  if (!config.apiKey.trim()) {
    clearLlmConfig()
    return
  }
  try {
    window.localStorage.setItem(LLM_CONFIG_STORAGE_KEY, JSON.stringify(config))
  } catch {
    // 隐私模式下 localStorage 可能不可写；失败不打断主流程
  }
}

/** 删配置。 */
export function clearLlmConfig(): void {
  try {
    window.localStorage.removeItem(LLM_CONFIG_STORAGE_KEY)
  } catch {
    // 同上
  }
}

/** 一次性读取当前生效配置（不依赖响应式状态）。 */
export function currentLlmConfig(): LlmConfig | null {
  return readLlmConfig()
}

/**
 * 构造问答请求要带的三个 LLM 请求头。
 *
 * <p>未配置时返回**空对象**：一个头都不发，后端据此回落服务端默认额度。
 * 用请求头而不是 URL 参数，避免 key 被代理访问日志记录。
 */
export function llmRequestHeaders(): Record<string, string> {
  const config = readLlmConfig()
  if (!config) {
    return {}
  }
  return {
    'X-LLM-Api-Key': config.apiKey.trim(),
    'X-LLM-Base-Url': config.baseUrl.trim(),
    'X-LLM-Model': config.model.trim(),
  }
}
