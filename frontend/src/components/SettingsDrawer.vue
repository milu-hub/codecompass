<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { testLlmConnection } from '../api/llm'
import {
  PROVIDER_PRESETS,
  defaultProfileName,
  maskApiKey,
  presetOf,
  profileLabel,
  useLlmConfig,
  type LlmProfile,
} from '../composables/useLlmConfig'

/**
 * LLM 设置抽屉（多配置版）。
 *
 * 数据只有一条去向：**本浏览器的 localStorage**。服务端不持久化用户 key；
 * 问答请求时才把「当前启用那套」放进 `X-LLM-Api-Key` 三个请求头（见 api/repos.ts）。
 * 因此列表里所有 key 都只以掩码展示，不回显完整值。
 *
 * 交互状态齐全：测试连接有 loading + 成功/失败内联提示；未配置 / 不完整 / 已配置三档状态条。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const {
  store,
  profiles,
  configured,
  selectedIncomplete,
  activeProviderLabel,
  maskedKey,
  reload,
  activate,
  create,
  save,
  remove,
} = useLlmConfig()

/** 正在编辑/新建的草稿；null = 不在编辑态（列表是主视图）。id 为空串表示「新建」。 */
const editing = ref<LlmProfile | null>(null)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

function blankDraft(providerId = PROVIDER_PRESETS[0].id): LlmProfile {
  const preset = presetOf(providerId)
  return {
    id: '',
    name: defaultProfileName(providerId, profiles.value),
    provider: providerId,
    baseUrl: preset.baseUrl,
    model: preset.model,
    apiKey: '',
  }
}

/** 打开时同步一次本地存储，并回到列表视图。 */
watch(visible, (open) => {
  if (open) {
    reload()
    editing.value = null
    testResult.value = null
  }
})

function startCreate(): void {
  editing.value = blankDraft()
  testResult.value = null
}

function startEdit(row: LlmProfile): void {
  editing.value = { ...row }
  testResult.value = null
}

function cancelEdit(): void {
  editing.value = null
  testResult.value = null
}

/** 选预设即自动填 baseUrl / model；「自定义」保持用户已填内容不动。 */
function onProviderChange(id: string): void {
  if (!editing.value) {
    return
  }
  const preset = presetOf(id)
  if (preset.id !== 'custom') {
    editing.value.baseUrl = preset.baseUrl
    editing.value.model = preset.model
  }
}

const canSubmit = computed(() => {
  const draft = editing.value
  if (!draft) {
    return false
  }
  return !!draft.baseUrl.trim() && !!draft.apiKey.trim() && !!draft.model.trim()
})

const canTest = computed(() => canSubmit.value && !testing.value)

function onSave(): void {
  const draft = editing.value
  if (!draft) {
    return
  }
  if (!canSubmit.value) {
    ElMessage.warning('请先填写完整的 Base URL / API Key / Model')
    return
  }
  const payload = {
    name: draft.name.trim() || defaultProfileName(draft.provider, profiles.value),
    provider: draft.provider,
    baseUrl: draft.baseUrl.trim(),
    apiKey: draft.apiKey.trim(),
    model: draft.model.trim(),
  }
  if (draft.id) {
    save({ ...payload, id: draft.id })
    ElMessage.success('已保存')
  } else {
    create(payload)
    ElMessage.success('已新建')
  }
  editing.value = null
  testResult.value = null
}

function onActivate(row: LlmProfile): void {
  activate(row.id)
  ElMessage.success(`已切换到「${profileLabel(row)}」`)
}

function onDelete(row: LlmProfile): void {
  if (!remove(row.id)) {
    // 正在使用的那套不允许删 —— 比"删完自动换一套"更可预测
    ElMessage.warning('正在使用的配置不能删除，请先切换到别的配置')
    return
  }
  if (editing.value?.id === row.id) {
    editing.value = null
  }
  ElMessage.success(`已删除「${profileLabel(row)}」`)
}

async function onTest(): Promise<void> {
  const draft = editing.value
  if (!draft || !canSubmit.value) {
    ElMessage.warning('请先填写完整的 Base URL / API Key / Model')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testLlmConnection({
      baseUrl: draft.baseUrl.trim(),
      apiKey: draft.apiKey.trim(),
      model: draft.model.trim(),
    })
  } catch (error) {
    testResult.value = {
      ok: false,
      message: error instanceof Error ? error.message : String(error),
    }
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="LLM 设置" direction="rtl" size="420px" class="llm-drawer">
    <!-- 状态条：绿 = 用你自己的 key；黄 = 未配置或不完整（回落服务端默认额度） -->
    <div class="llm-status" :class="configured ? 'is-configured' : 'is-default'">
      <span class="llm-status-title">
        {{ configured ? '当前使用你自己的 API Key' : '当前使用服务端默认额度' }}
      </span>
      <span class="llm-status-detail">
        <template v-if="configured">{{ activeProviderLabel }} · 掩码 {{ maskedKey }}</template>
        <template v-else-if="selectedIncomplete">选中的配置缺少 API Key 或 Base URL，暂不生效</template>
        <template v-else>还没有可用的配置，问答走服务端的 Key</template>
      </span>
    </div>

    <!-- 配置列表（不在编辑态时是主视图） -->
    <div class="llm-list-head">
      <span class="llm-list-title">我的配置（{{ profiles.length }}）</span>
      <el-button size="small" type="primary" @click="startCreate">新建配置</el-button>
    </div>

    <p v-if="profiles.length === 0" class="llm-empty">
      还没有配置。点「新建配置」填一套你自己的 Key。
    </p>

    <ul v-else class="llm-list">
      <li
        v-for="row in profiles"
        :key="row.id"
        class="llm-row"
        :class="{ 'is-active': row.id === store.activeId }"
      >
        <div class="llm-row-main">
          <span class="llm-row-name">
            <span v-if="row.id === store.activeId" class="llm-active-dot" aria-label="当前启用">●</span>
            {{ profileLabel(row) }}
          </span>
          <span class="llm-row-meta">
            {{ presetOf(row.provider).label }} · {{ row.model || '未填模型' }} ·
            {{ maskApiKey(row.apiKey) || '无 Key' }}
          </span>
        </div>
        <div class="llm-row-actions">
          <el-button
            v-if="row.id !== store.activeId"
            size="small"
            text
            @click="onActivate(row)"
          >
            启用
          </el-button>
          <span v-else class="llm-active-tag">使用中</span>
          <el-button size="small" text @click="startEdit(row)">编辑</el-button>
          <el-button size="small" text type="danger" @click="onDelete(row)">删除</el-button>
        </div>
      </li>
    </ul>

    <!-- 编辑/新建表单 -->
    <div v-if="editing" class="llm-editor">
      <p class="llm-editor-title">{{ editing.id ? '编辑配置' : '新建配置' }}</p>

      <el-form label-position="top" class="llm-form" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="editing.name" class="cc-glass-input" placeholder="例如 公司网关 / 本地 Ollama" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="editing.provider" class="llm-full" @change="onProviderChange">
            <el-option
              v-for="preset in PROVIDER_PRESETS"
              :key="preset.id"
              :label="preset.label"
              :value="preset.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input
            v-model="editing.baseUrl"
            class="cc-glass-input"
            placeholder="https://api.deepseek.com/v1"
          />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="editing.apiKey"
            class="cc-glass-input"
            type="password"
            show-password
            placeholder="sk-..."
          />
        </el-form-item>
        <el-form-item label="Model">
          <el-input v-model="editing.model" class="cc-glass-input" placeholder="deepseek-chat" />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="testResult"
        class="llm-test-result"
        :type="testResult.ok ? 'success' : 'error'"
        :title="testResult.message"
        :closable="false"
        show-icon
      />

      <div class="llm-actions">
        <el-button :loading="testing" :disabled="!canTest" @click="onTest">测试连接</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
        <el-button text @click="cancelEdit">取消</el-button>
      </div>
    </div>

    <p class="llm-note">你的 API Key 只保存在本浏览器，不会上传到服务器。</p>
  </el-drawer>
</template>

<style scoped>
.llm-full {
  width: 100%;
}

/* ---------- 状态条 ---------- */
.llm-status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 18px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--cc-radius-row);
}

.llm-status.is-configured {
  background: rgba(12, 166, 120, 0.08);
  border-color: rgba(12, 166, 120, 0.28);
}

/* 加深过的绿：主色 #0ca678 作小字只有 3.0:1，不达 AA，文字改用 #087f5b（约 5.1:1） */
.llm-status.is-configured .llm-status-title {
  color: #087f5b;
}

.llm-status.is-default {
  background: rgba(230, 162, 60, 0.1);
  border-color: rgba(230, 162, 60, 0.32);
}

/* 同理加深的琥珀：#96650f 在白底约 5.0:1 */
.llm-status.is-default .llm-status-title {
  color: #96650f;
}

.llm-status-title {
  font-size: 13px;
  font-weight: 600;
}

.llm-status-detail {
  font-size: 12px;
  color: var(--cc-text-muted);
  word-break: break-all;
}

/* ---------- 列表 ---------- */
.llm-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.llm-list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--cc-ink);
}

.llm-empty {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--cc-text-muted);
}

.llm-list {
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
  max-height: 240px;
  overflow-y: auto;
}

.llm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--cc-glass-line-strong);
  border-radius: var(--cc-radius-row);
  background: rgba(255, 255, 255, 0.6);
}

.llm-row + .llm-row {
  margin-top: 8px;
}

/* 当前启用：主色描边 + 极淡主色底，和"未启用"一眼可分；不靠颜色单独承载语义（另有「使用中」文字） */
.llm-row.is-active {
  border-color: rgba(12, 166, 120, 0.45);
  background: rgba(12, 166, 120, 0.06);
}

.llm-row-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.llm-row-name {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--cc-ink);
  overflow-wrap: anywhere;
}

.llm-active-dot {
  color: #087f5b;
  font-size: 10px;
  line-height: 1;
}

.llm-row-meta {
  font-size: 12px;
  color: var(--cc-text-muted);
  overflow-wrap: anywhere;
}

.llm-row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.llm-active-tag {
  font-size: 12px;
  color: #087f5b;
  padding: 0 6px;
}

/* ---------- 编辑区 ---------- */
.llm-editor {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--cc-glass-line-strong);
}

.llm-editor-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--cc-ink);
}

.llm-form {
  margin-bottom: 4px;
}

.llm-form :deep(.el-form-item__label) {
  color: var(--cc-text-muted);
  font-size: 13px;
}

.llm-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.llm-test-result {
  margin-bottom: 14px;
}

/* ---------- 动作区 ---------- */
.llm-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.llm-note {
  margin: 20px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--cc-text-muted);
}
</style>
