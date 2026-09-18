<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useRepositoryStore } from '../stores/repository'
import ClassList from '../components/ClassList.vue'
import DependencyGraphPane from '../components/DependencyGraphPane.vue'
import QaPanel from '../components/QaPanel.vue'
import SourcePane from '../components/SourcePane.vue'
import LearningPathPanel from '../components/LearningPathPanel.vue'
import QuizPanel from '../components/QuizPanel.vue'
import NotePanel from '../components/NotePanel.vue'
import AchievementsPanel from '../components/AchievementsPanel.vue'

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
// T24：右栏统一 Tab（图 / 路线 / 测验 / 笔记 / 成就）；默认不铺全图，点类才看邻域
const rightTab = ref<'graph' | 'path' | 'quiz' | 'note' | 'achievement'>('graph')

// F6：分享短链弹窗
const shareDialogVisible = ref(false)
const sharing = ref(false)
const shareUrl = computed(() =>
  repository.shareLink ? `${window.location.origin}${repository.shareLink.url}` : '',
)

async function onGenerateShare() {
  sharing.value = true
  try {
    await repository.generateShareLink()
    shareDialogVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error))
  } finally {
    sharing.value = false
  }
}

async function copyShareLink() {
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    ElMessage.success('短链已复制')
  } catch {
    // 剪贴板不可用（非安全上下文）时退回选中提示
    ElMessage.warning('复制失败，请手动复制')
  }
}

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
      <!-- F6：生成分享页（分析完成后） -->
      <el-button
        v-if="phase === 'done' && graph"
        class="share-button"
        size="small"
        :loading="sharing"
        @click="onGenerateShare"
      >
        生成分享页
      </el-button>
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
          :progress="repository.unitProgress"
          @select="(id: string) => void repository.selectUnit(id)"
        />
      </div>

      <div class="source-pane">
        <!-- T14：选中类源码（点行选中标识符） -->
        <SourcePane />
        <!-- F3：AI 问答（点类提问 + 选中标识符行锚点） -->
        <QaPanel />
      </div>

      <!-- T24：右栏统一 Tab —— 图 / 学习路线 / 测验 / 笔记 / 成就 -->
      <div class="right-pane">
        <el-tabs v-model="rightTab">
          <el-tab-pane label="依赖图" name="graph">
            <div class="graph-header">
              <span class="graph-title">
                {{ showFullGraph ? '全图' : selectedUnit ? `依赖图：${selectedUnit.name}` : '依赖图' }}
              </span>
              <el-button size="small" @click="showFullGraph = !showFullGraph">
                {{ showFullGraph ? '显示邻域' : '显示全图' }}
              </el-button>
            </div>
            <p v-if="!showFullGraph && !selectedUnit" class="graph-hint">
              点击左侧的类，查看它与其它类的依赖关系
            </p>
            <DependencyGraphPane v-else :mermaid-text="displayedMermaid" />
            <p v-if="graph.isolatedCodeUnitIds.length > 0" class="isolated-hint">
              另有 {{ graph.isolatedCodeUnitIds.length }} 个类没有依赖关系，未画进图
            </p>
          </el-tab-pane>
          <el-tab-pane label="学习路线" name="path">
            <LearningPathPanel />
          </el-tab-pane>
          <el-tab-pane label="测验" name="quiz">
            <QuizPanel />
          </el-tab-pane>
          <el-tab-pane label="笔记" name="note">
            <NotePanel />
          </el-tab-pane>
          <el-tab-pane label="成就" name="achievement">
            <AchievementsPanel />
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </el-card>

  <!-- F6：分享短链弹窗（只读分享页由后端渲染，见 /share/{id}） -->
  <el-dialog v-model="shareDialogVisible" title="分享领读页" width="520">
    <p class="share-hint">任何人（无需 Cookie）都可打开这条短链，页面为只读。</p>
    <div class="share-link-row">
      <el-input v-model="shareUrl" readonly />
      <el-button type="primary" @click="copyShareLink">复制</el-button>
    </div>
    <template #footer>
      <el-button @click="shareDialogVisible = false">关闭</el-button>
      <el-link :href="shareUrl" target="_blank" type="primary">打开分享页</el-link>
    </template>
  </el-dialog>
</template>

<style scoped>
.main-card {
  max-width: 1100px;
  margin: 24px auto;
}

.language-tag {
  margin-left: 8px;
}

.share-button {
  float: right;
}

.share-hint {
  color: #909399;
  font-size: 13px;
  margin: 0 0 8px;
}

.share-link-row {
  display: flex;
  gap: 8px;
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

.right-pane {
  flex: 1 1 0;
  min-width: 0;
}

.graph-hint {
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
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
