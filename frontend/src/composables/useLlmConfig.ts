/**
 * 用户自填 LLM 配置的**响应式封装**（多配置版）。
 *
 * 纯逻辑（存储 / 预设 / 掩码 / CRUD / 请求头）在 `utils/llmConfig.ts`：API 层直接引用它，
 * 不必为一个 localStorage 读取而依赖 Vue。这里只做两件事：
 * 1. 把整个 store 包成**模块级共享 ref**，让顶部切换器与设置抽屉读到同一份；
 * 2. 把 CRUD 落库（内存 ref 与 localStorage 一起更新），保证两边不脱节。
 *
 * 同时 re-export 纯逻辑，组件从一个入口导入即可。
 */
import { computed, ref } from 'vue'
import {
  addProfile,
  isUsable,
  maskApiKey,
  presetOf,
  profileLabel,
  readStore,
  removeProfile,
  setActive,
  updateProfile,
  writeStore,
  type LlmConfigStore,
  type LlmProfile,
} from '../utils/llmConfig'

export * from '../utils/llmConfig'

/** 模块级共享状态：读一次，之后由各操作驱动。 */
const store = ref<LlmConfigStore>(readStore())

/** 内存与 localStorage 一起更新，避免"界面变了但请求头还是旧的"。 */
function commit(next: LlmConfigStore): void {
  store.value = next
  writeStore(next)
}

const profiles = computed(() => store.value.profiles)

/** 当前**选中**的那套（可能还没填完）。 */
const selected = computed(
  () => store.value.profiles.find((item) => item.id === store.value.activeId) ?? null,
)

/** 当前**真正生效**的那套：选中且 key/baseUrl 齐全。 */
const activeProfile = computed(() => (isUsable(selected.value) ? selected.value : null))

const configured = computed(() => activeProfile.value !== null)

/** 选中了但没填完 —— 界面上要提示"这套还不完整，仍会用服务端默认"。 */
const selectedIncomplete = computed(() => !!selected.value && !isUsable(selected.value))

/** 顶部切换器显示的文案。 */
const activeLabel = computed(() => (selected.value ? profileLabel(selected.value) : '未配置'))

const activeProviderLabel = computed(
  () => (activeProfile.value ? presetOf(activeProfile.value.provider).label : ''),
)

const maskedKey = computed(() =>
  activeProfile.value ? maskApiKey(activeProfile.value.apiKey) : '',
)

export function useLlmConfig() {
  function reload(): void {
    store.value = readStore()
  }

  function activate(id: string): void {
    commit(setActive(store.value, id))
  }

  /** 新建；一套都没有时这一套自动成为当前启用。返回新建的 profile（供调用方继续编辑）。 */
  function create(draft: Omit<LlmProfile, 'id'>): LlmProfile {
    const result = addProfile(store.value, draft)
    commit(result.store)
    return result.profile
  }

  function save(profile: LlmProfile): void {
    commit(updateProfile(store.value, profile))
  }

  /** 删除；正在使用的那套返回 false 且不删。 */
  function remove(id: string): boolean {
    const result = removeProfile(store.value, id)
    if (result.ok) {
      commit(result.store)
    }
    return result.ok
  }

  return {
    store,
    profiles,
    selected,
    activeProfile,
    configured,
    selectedIncomplete,
    activeLabel,
    activeProviderLabel,
    maskedKey,
    reload,
    activate,
    create,
    save,
    remove,
  }
}
