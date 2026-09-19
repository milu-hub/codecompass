<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { testLlmConnection } from '../api/llm'
import {
  PROVIDER_PRESETS,
  currentLlmConfig,
  presetOf,
  useLlmConfig,
  type LlmConfig,
} from '../composables/useLlmConfig'

/**
 * LLM 设置抽屉（顶部右上角「设置」入口打开）。
 *
 * 数据只有一条去向：**本浏览器的 localStorage**。服务端不持久化用户 key；
 * 问答请求时才把它放进 `X-LLM-Api-Key` 请求头（见 api/repos.ts）。
 * 因此抽屉里所有展示都走掩码，不回显完整 key。
 *
 * 交互状态齐全：测试连接有 loading + 成功/失败内联提示；未配置/已配置各有一档状态条。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const { configured, maskedKey, providerLabel, save, clear, reload } = useLlmConfig()

const draft = ref<LlmConfig>({ provider: 'deepseek', baseUrl: '', apiKey: '', model: '' })
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

function presetDraft(): LlmConfig {
  const preset = PROVIDER_PRESETS[0]
  return { provider: preset.id, baseUrl: preset.baseUrl, apiKey: '', model: preset.model }
}

/** 打开时把「当前生效值」灌进草稿；未配置则用首个预设打底，避免空白表单。 */
watch(visible, (open) => {
  if (!open) {
    return
  }
  reload()
  testResult.value = null
  const current = currentLlmConfig()
  draft.value = current ? { ...current } : presetDraft()
})

/** 选预设即自动填 baseUrl / model；「自定义」保持用户已填内容不动。 */
function onProviderChange(id: string) {
  const preset = presetOf(id)
  if (preset.id !== 'custom') {
    draft.value.baseUrl = preset.baseUrl
    draft.value.model = preset.model
  }
}

const canSubmit = computed(() => {
  const { baseUrl, apiKey, model } = draft.value
  return !!baseUrl.trim() && !!apiKey.trim() && !!model.trim()
})

const canTest = computed(() => canSubmit.value && !testing.value)

function onSave() {
  if (!canSubmit.value) {
    ElMessage.warning('请先填写完整的 Base URL / API Key / Model')
    return
  }
  save({
    provider: draft.value.provider,
    baseUrl: draft.value.baseUrl.trim(),
    apiKey: draft.value.apiKey.trim(),
    model: draft.value.model.trim(),
  })
  ElMessage.success('已保存到本浏览器')
}

function onClear() {
  clear()
  draft.value = presetDraft()
  testResult.value = null
  ElMessage.info('已清除本浏览器的 API Key 配置')
}

async function onTest() {
  if (!canSubmit.value) {
    ElMessage.warning('请先填写完整的 Base URL / API Key / Model')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testLlmConnection({
      baseUrl: draft.value.baseUrl.trim(),
      apiKey: draft.value.apiKey.trim(),
      model: draft.value.model.trim(),
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
  <el-drawer
    v-model="visible"
    title="LLM 设置"
    direction="rtl"
    size="420px"
    class="llm-drawer"
  >
    <!-- 状态条：绿色 = 用你自己的 key；黄色 = 用服务端默认额度 -->
    <div class="llm-status" :class="configured ? 'is-configured' : 'is-default'">
      <span class="llm-status-title">
        {{ configured ? '当前使用你自己的 API Key' : '当前使用服务端默认额度' }}
      </span>
      <span class="llm-status-detail">
        {{
          configured
            ? `${providerLabel} · 掩码 ${maskedKey}`
            : '填写下面的配置后，问答将改用你自己的 Key'
        }}
      </span>
    </div>

    <el-form label-position="top" class="llm-form" @submit.prevent>
      <el-form-item label="Provider">
        <el-select v-model="draft.provider" class="llm-full" @change="onProviderChange">
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
          v-model="draft.baseUrl"
          class="cc-glass-input"
          placeholder="https://api.deepseek.com/v1"
        />
      </el-form-item>

      <el-form-item label="API Key">
        <el-input
          v-model="draft.apiKey"
          class="cc-glass-input"
          type="password"
          show-password
          placeholder="sk-..."
        />
      </el-form-item>

      <el-form-item label="Model">
        <el-input v-model="draft.model" class="cc-glass-input" placeholder="deepseek-chat" />
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
      <el-button type="danger" text @click="onClear">清除</el-button>
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
  /* --cc-text-muted 在白底约 4.8:1，达 AA */
  color: var(--cc-text-muted);
  word-break: break-all;
}

/* ---------- 表单 ---------- */
.llm-form {
  margin-bottom: 4px;
}

.llm-form :deep(.el-form-item__label) {
  color: var(--cc-text-muted);
  font-size: 13px;
}

.llm-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.llm-test-result {
  margin-bottom: 16px;
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
