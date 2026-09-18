<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'
import ClassList from '../components/ClassList.vue'
import DependencyGraphPane from '../components/DependencyGraphPane.vue'
import QaPanel from '../components/QaPanel.vue'
import SourcePane from '../components/SourcePane.vue'

const repository = useRepositoryStore()
const {
  url,
  phase,
  progress,
  message,
  errorMessage,
  language,
  framework,
  graph,
  neighborhood,
  selectedUnitId,
  filterText,
} = storeToRefs(repository)

const showFullGraph = ref(false)

const displayedMermaid = computed(() => {
  if (showFullGraph.value && graph.value) {
    return graph.value.mermaid
  }
  if (neighborhood.value) {
    return neighborhood.value.mermaid
  }
  return graph.value?.mermaid ?? ''
})

const selectedUnit = computed(() =>
  graph.value?.codeUnits.find((unit) => unit.id === selectedUnitId.value),
)

const busy = computed(
  () => phase.value === 'submitting' || phase.value === 'pending' || phase.value === 'running',
)

async function onSubmit() {
  showFullGraph.value = false
  await repository.submit()
}
</script>

<template>
  <el-card class="main-card">
    <template #header>
      <span>CodeCompass 依赖分析</span>
      <el-tag v-if="language" size="small" class="language-tag">{{ language }}</el-tag>
      <el-tag v-if="framework" size="small" type="success" class="language-tag">
        {{ framework }}
      </el-tag>
    </template>

    <!-- 输入区 -->
    <div class="input-row">
      <el-input
        v-model="url"
        placeholder="输入 GitHub 仓库地址，如 https://github.com/spring-projects/spring-petclinic"
        :disabled="busy"
        clearable
        @keyup.enter="onSubmit"
      />
      <el-button type="primary" :loading="busy" :disabled="!url.trim()" @click="onSubmit">
        分析
      </el-button>
    </div>

    <!-- 进度区 -->
    <div v-if="busy" class="progress-area">
      <el-progress :percentage="progress" :stroke-width="12" />
      <p class="progress-message">{{ message || '排队中…' }}</p>
    </div>

    <!-- 失败区 -->
    <el-alert
      v-if="phase === 'failed'"
      class="error-area"
      type="error"
      :title="errorMessage ?? '分析失败'"
      :closable="false"
      show-icon
    />

    <!-- 结果区 -->
    <div v-if="phase === 'done' && graph" class="result-layout">
      <div class="list-pane">
        <el-input v-model="filterText" placeholder="过滤类名或包名" clearable size="small" />
        <ClassList
          :units="graph.codeUnits"
          :selected-id="selectedUnitId"
          :filter-text="filterText"
          @select="(id: string) => void repository.selectUnit(id)"
        />
      </div>

      <div class="source-pane">
        <!-- T14：选中类源码（点行选中标识符） -->
        <SourcePane />
        <!-- F3：AI 问答（点类提问 + 选中标识符行锚点） -->
        <QaPanel />
      </div>

      <div class="graph-pane">
        <div class="graph-header">
          <span class="graph-title">
            {{ showFullGraph ? '全图' : selectedUnit ? `依赖图：${selectedUnit.name}` : '依赖图' }}
          </span>
          <el-button size="small" @click="showFullGraph = !showFullGraph">
            {{ showFullGraph ? '显示邻域' : '显示全图' }}
          </el-button>
        </div>
        <DependencyGraphPane :mermaid-text="displayedMermaid" />
        <p v-if="graph.isolatedCodeUnitIds.length > 0" class="isolated-hint">
          另有 {{ graph.isolatedCodeUnitIds.length }} 个类没有依赖关系，未画进图
        </p>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.main-card {
  max-width: 1100px;
  margin: 24px auto;
}

.language-tag {
  margin-left: 8px;
}

.input-row {
  display: flex;
  gap: 12px;
}

.progress-area {
  margin-top: 16px;
}

.progress-message {
  color: #909399;
  font-size: 13px;
  margin-top: 8px;
}

.error-area {
  margin-top: 16px;
}

.result-layout {
  display: flex;
  gap: 16px;
  margin-top: 16px;
  align-items: flex-start;
}

.list-pane {
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid #ebeef5;
  padding-right: 12px;
}

.source-pane {
  flex: 1 1 0;
  min-width: 0;
}

.graph-pane {
  flex: 1 1 0;
  min-width: 0;
}

.graph-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.graph-title {
  font-weight: 600;
}

.isolated-hint {
  color: #909399;
  font-size: 12px;
  margin-top: 8px;
}
</style>
