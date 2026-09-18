<script setup lang="ts">
import { ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'
import { fetchSource } from '../api/repos'
import type { SourceView } from '../api/repos'

/**
 * T14 源码面板：展示选中类的源码行（行号为真实文件行号），点击行即选中标识符：
 *
 * - 落在某个方法范围内 → 吸附到整个方法（提问上下文 = 方法全文）
 * - 落在字段声明行 → 吸附到该行（属性名提问）
 * - 其他行 → 选中该行
 *
 * 选中范围进 store.selection，作为 QaPanel 提问的行锚点。
 */
const repository = useRepositoryStore()
const { selectedUnitId, taskId, graph, selection } = storeToRefs(repository)

const source = ref<SourceView | null>(null)
const sourceError = ref('')
let requestToken = 0

watch(
  [selectedUnitId, taskId],
  async () => {
    source.value = null
    sourceError.value = ''
    if (!taskId.value || !selectedUnitId.value) {
      return
    }
    const token = ++requestToken
    try {
      const response = await fetchSource(taskId.value, selectedUnitId.value)
      if (token === requestToken) {
        source.value = response
      }
    } catch (error) {
      if (token === requestToken) {
        sourceError.value = error instanceof Error ? error.message : String(error)
      }
    }
  },
  { immediate: true },
)

const selectedUnit = () =>
  graph.value?.codeUnits.find((unit) => unit.id === selectedUnitId.value) ?? null

function isSelectedLine(lineNumber: number): boolean {
  if (!selection.value) {
    return false
  }
  return lineNumber >= selection.value.startLine && lineNumber <= selection.value.endLine
}

/** 点击源码行：优先吸附到所在方法，其次字段声明行，最后就是该行。 */
function onLineClick(lineNumber: number, lineText: string) {
  const unit = selectedUnit()
  if (!unit) {
    return
  }
  const method = unit.methods.find(
    (m) => lineNumber >= m.startLine && lineNumber <= m.endLine,
  )
  if (method) {
    repository.setSelection({
      startLine: method.startLine,
      endLine: method.endLine,
      label: `方法 ${method.name}（${method.startLine}-${method.endLine} 行）`,
    })
    return
  }
  const field = unit.fields.find((f) =>
    new RegExp(`\\b${f.name}\\b`).test(lineText),
  )
  if (field) {
    repository.setSelection({
      startLine: lineNumber,
      endLine: lineNumber,
      label: `属性 ${field.name}（第 ${lineNumber} 行）`,
    })
    return
  }
  repository.setSelection({
    startLine: lineNumber,
    endLine: lineNumber,
    label: `第 ${lineNumber} 行`,
  })
}
</script>

<template>
  <div class="source-pane">
    <div class="source-header">
      <span class="source-title">源码</span>
      <span v-if="source" class="source-file">{{ source.file }}</span>
      <span v-if="selection" class="selection-hint">{{ selection.label }}</span>
      <el-button
        v-if="selection"
        size="small"
        text
        type="primary"
        @click="repository.clearSelection()"
      >
        取消选中
      </el-button>
    </div>

    <el-alert v-if="sourceError" type="error" :title="sourceError" :closable="false" show-icon />

    <div v-if="source" class="code-viewer">
      <div
        v-for="(line, index) in source.lines"
        :key="source.startLine + index"
        class="code-line"
        :class="{ selected: isSelectedLine(source.startLine + index) }"
        :title="`点击选中第 ${source.startLine + index} 行`"
        @click="onLineClick(source.startLine + index, line)"
      >
        <span class="line-number">{{ source.startLine + index }}</span>
        <span class="line-text">{{ line || ' ' }}</span>
      </div>
    </div>
    <p v-else-if="!sourceError" class="source-loading">加载源码中…</p>
  </div>
</template>

<style scoped>
.source-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.source-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.source-title {
  font-weight: 600;
}

.source-file {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selection-hint {
  font-size: 12px;
  color: #409eff;
}

.code-viewer {
  flex: 1;
  min-height: 0;
  max-height: 420px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
  font-family: 'Consolas', 'Menlo', 'Courier New', monospace;
  font-size: 13px;
}

.code-line {
  display: flex;
  cursor: pointer;
}

.code-line:hover {
  background: #f0f7ff;
}

.code-line.selected {
  background: #d9ecff;
}

.line-number {
  flex-shrink: 0;
  width: 48px;
  padding-right: 8px;
  text-align: right;
  color: #c0c4cc;
  user-select: none;
  border-right: 1px solid #ebeef5;
  margin-right: 8px;
}

.line-text {
  white-space: pre;
}

.source-loading {
  color: #909399;
  font-size: 13px;
}
</style>
