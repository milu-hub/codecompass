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
        class="cc-glass-input"
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

    <!-- 结果区：仿 IDE 上下分区 —— 第一行「类列表 + 源码」占满全宽，第二行「依赖图等」整行铺开 -->
    <div v-if="phase === 'done' && graph" class="result-layout">
      <div class="workbench-top">
        <div class="list-pane cc-glass-card">
          <el-input v-model="filterText" placeholder="过滤类名或包名" clearable size="small" />
          <ClassList
            :units="graph.codeUnits"
            :selected-id="selectedUnitId"
            :filter-text="filterText"
            :progress="repository.unitProgress"
            @select="(id: string) => void repository.selectUnit(id)"
          />
        </div>

        <div class="source-pane cc-glass-card">
          <!-- T14：选中类源码（点行选中标识符） -->
          <SourcePane />
          <!-- F3：AI 问答（点类提问 + 选中标识符行锚点） -->
          <QaPanel />
        </div>
      </div>

      <!-- T24：右栏统一 Tab —— 图 / 学习路线 / 测验 / 笔记 / 成就（下沉为整行） -->
      <div class="right-pane cc-glass-card">
        <el-tabs v-model="rightTab" class="cc-glass-tabs">
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
    <p class="share-hint">
      任何人（无需 Cookie）都可打开这条短链，页面为只读，包含依赖图、学习路线、问答记录与你自己的笔记。
    </p>
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
/* 第二步：外层卡片退化成「透明容器」—— 让三栏玻璃卡片直接压在页面光晕上。
   否则毛玻璃背后垫着一层白底，玻璃等于白做。 */
.main-card {
  max-width: var(--cc-layout-max-width);
  margin: 24px auto;
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
}

/* 内衬 16px，与顶部栏内容对齐 */
.main-card :deep(.el-card__header) {
  padding: 0 16px 12px;
  border-bottom: none;
}

.main-card :deep(.el-card__body) {
  padding: 0 16px;
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

/* 工作台：仿 IDE 上下两区，撑满窗口剩余高度，各自内部滚动（不让整页滚动）
   第一行 = 类列表（资源管理器）+ 源码（编辑器），占满全宽；
   第二行 = 依赖图/学习路线/测验/笔记/成就，整行铺开（图在窄栏里根本画不开）。

   高度分配：先把可用高度算出来，再切一块给下图区，剩下的全归编辑器。
   min-height 用「上图最少 360 + 间隙 16 + 下图最少 220」兜底：窗口不够高时宁可整页滚一点，
   也不把编辑器压成几行（实测 1280×800 下不兜底的话编辑器只剩 6 行，比改之前还差）。 */
.result-layout {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 16px;
  height: calc(100dvh - 222px);
  min-height: 596px;
}

.workbench-top {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 360px;
  align-items: stretch;
}

/* 第一行：类列表栏宽到能显示完整类名（包名让位，优先保住类名） */
.list-pane {
  display: flex;
  flex-direction: column;
  width: 348px;
  flex-shrink: 0;
  min-height: 0;
  padding: 12px;
}

/* 源码栏吃掉第一行剩下的全部宽度 */
.source-pane {
  display: flex;
  flex-direction: column;
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  padding: 12px;
}

/* 第二行：整行铺开，高度按视口比例给（下限 220 / 上限 320） */
.right-pane {
  display: flex;
  flex-direction: column;
  height: clamp(220px, 26%, 320px);
  flex-shrink: 0;
  min-width: 0;
  padding: 12px;
}

/* 右侧 Tab 内容区自己滚，避免图/路线撑破玻璃卡片 */
.right-pane :deep(.el-tabs) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.right-pane :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
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
