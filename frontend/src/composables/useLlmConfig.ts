/**
 * 用户自填 LLM 配置的**响应式封装**。
 *
 * 纯逻辑（存储 / 预设 / 掩码 / 请求头）在 `utils/llmConfig.ts`：API 层直接引用它，
 * 不必为一个 localStorage 读取而依赖 Vue。这里只做一件事 —— 把「当前生效配置」
 * 包成**模块级共享 ref**，让顶部状态提示与设置抽屉读到同一份，保存/清除后彼此立刻一致。
 *
 * 同时 re-export 纯逻辑，组件从一个入口导入即可。
 */
import { computed, ref } from 'vue'
import {
  clearLlmConfig,
  maskApiKey,
  presetOf,
  readLlmConfig,
  writeLlmConfig,
  type LlmConfig,
} from '../utils/llmConfig'

export * from '../utils/llmConfig'

/** 模块级共享状态：读一次，之后由 save / clear 驱动。 */
const config = ref<LlmConfig | null>(readLlmConfig())

const configured = computed(() => !!config.value?.apiKey?.trim())

const maskedKey = computed(() => (config.value ? maskApiKey(config.value.apiKey) : ''))

const providerLabel = computed(() => {
  const provider = config.value?.provider
  return provider ? presetOf(provider).label : ''
})

export function useLlmConfig() {
  function save(next: LlmConfig): void {
    writeLlmConfig(next)
    config.value = readLlmConfig()
  }

  function clear(): void {
    clearLlmConfig()
    config.value = null
  }

  function reload(): void {
    config.value = readLlmConfig()
  }

  return { config, configured, maskedKey, providerLabel, save, clear, reload }
}
