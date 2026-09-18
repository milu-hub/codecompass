<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'
import { askQuestion } from '../api/repos'
import type { AskResponse } from '../api/repos'

/**
 * F3 AI 问答窗口。
 *
 * - 提问时把当前选中的类作为锚点传给后端（§S7「点击某个类提问」）；
 *   没选中类也能问 —— 后端退化为关键词检索。
 * - T14：源码面板里选中的标识符范围（store.selection）作为行锚点一起提交，
 *   后端把它做成聚焦片段置顶 —— 问题精确指向选中的方法/属性/行。
 * - 引用是后端校验过的（行号来自检索层），点击引用把类列表定位到对应类。
 */
const repository = useRepositoryStore()
const { selectedUnitId, taskId, selection } = storeToRefs(repository)

const question = ref('')
const asking = ref(false)
const answer = ref<AskResponse | null>(null)
const errorMessage = ref('')

const selectedUnit = computed(
  () => repository.graph?.codeUnits.find((unit) => unit.id === selectedUnitId.value) ?? null,
)

// 切换任务时清空问答区，避免上个仓库的答案张冠李戴
watch(
  () => repository.taskId,
  () => {
    question.value = ''
    answer.value = null
    errorMessage.value = ''
  },
)

async function onSubmit() {
  const text = question.value.trim()
  if (!text || asking.value || !taskId.value) {
    return
  }
  asking.value = true
  errorMessage.value = ''
  try {
    answer.value = await askQuestion(
      taskId.value,
      text,
      selectedUnitId.value,
      selection.value?.startLine ?? null,
      selection.value?.endLine ?? null,
    )
  } catch (error) {
    answer.value = null
    // http.ts 抛出的信息形如「POST /api/... 失败：<后端 error>」，只展示后端原文
    const raw = error instanceof Error ? error.message : String(error)
    errorMessage.value = raw.split('失败：')[1] ?? raw
  } finally {
    asking.value = false
  }
}

/** 引用 → 类列表定位：选中该文件对应的类（邻域图随之切换）。 */
function jumpToReference(file: string) {
  const unit = repository.graph?.codeUnits.find((u) => u.filePath === file)
  if (unit) {
    void repository.selectUnit(unit.id)
  }
}

function shortName(file: string): string {
  return file.split('/').pop() ?? file
}
</script>

<template>
  <div class="qa-panel">
    <div class="qa-header">
      <span class="qa-title">AI 问答</span>
      <span v-if="selection" class="qa-anchor">选中：{{ selection.label }}</span>
      <span v-else-if="selectedUnit" class="qa-anchor">锚点类：{{ selectedUnit.name }}</span>
      <span v-else class="qa-anchor muted">未选中类：按关键词检索</span>
    </div>

    <div class="qa-input-row">
      <el-input
        v-model="question"
        placeholder="针对代码提问，如：processFindForm 方法做什么"
        :disabled="asking"
        clearable
        @keyup.enter="onSubmit"
      />
      <el-button type="primary" :loading="asking" :disabled="!question.trim()" @click="onSubmit">
        提问
      </el-button>
    </div>

    <el-alert
      v-if="errorMessage"
      class="qa-error"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    />

    <div v-if="answer" class="qa-answer">
      <p class="answer-text">{{ answer.answer }}</p>
      <div v-if="answer.references.length > 0" class="reference-list">
        <span class="reference-title">引用（点击定位到对应类）：</span>
        <el-tag
          v-for="(reference, index) in answer.references"
          :key="index"
          class="reference-item"
          size="small"
          type="info"
          effect="plain"
          :title="reference.file"
          @click="jumpToReference(reference.file)"
        >
          {{ shortName(reference.file) }} {{ reference.startLine }}-{{ reference.endLine }}
        </el-tag>
      </div>
      <p class="answer-meta">model：{{ answer.model }}</p>
    </div>
  </div>
</template>

<style scoped>
.qa-panel {
  margin-top: 12px;
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}

.qa-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.qa-title {
  font-weight: 600;
}

.qa-anchor {
  font-size: 12px;
  color: #409eff;
}

.qa-anchor.muted {
  color: #909399;
}

.qa-input-row {
  display: flex;
  gap: 8px;
}

.qa-error {
  margin-top: 8px;
}

.qa-answer {
  margin-top: 12px;
}

.answer-text {
  white-space: pre-wrap;
  line-height: 1.7;
  margin: 0;
}

.reference-list {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.reference-title {
  font-size: 12px;
  color: #909399;
}

.reference-item {
  cursor: pointer;
}

.answer-meta {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
