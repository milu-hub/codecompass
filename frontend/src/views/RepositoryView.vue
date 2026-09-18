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
import { useResizableLayout } from '../composables/useResizableLayout'

/** 工作台布局：左右分栏 + 上下分区，两个方向都可拖，比例落 localStorage */
const layoutRef = ref<HTMLElement | null>(null)
const { layoutStyle, draggingAxis, startDrag, resetLayout } = useResizableLayout(layoutRef)

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

    <!-- 结果区：上区「类列表 + 源码」占一行（左右可拖），下区「图/Tab」独占整行（上下可拖） -->
    <div
      v-if="phase === 'done' && graph"
      ref="layoutRef"
      class="result-layout"
      :style="layoutStyle"
    >
      <div class="workbench-top" data-pane="top">
        <div class="list-pane cc-glass-card" data-pane="left">
          <el-input v-model="filterText" placeholder="过滤类名或包名" clearable size="small" />
          <ClassList
            :units="graph.codeUnits"
            :selected-id="selectedUnitId"
            :filter-text="filterText"
            :progress="repository.unitProgress"
            @select="(id: string) => void repository.selectUnit(id)"
          />
        </div>

        <div
          class="splitter splitter--col"
          :class="{ 'is-dragging': draggingAxis === 'columns' }"
          role="separator"
          aria-orientation="vertical"
          aria-label="拖拽调整类列表宽度"
          @pointerdown="startDrag('columns', $event)"
        ></div>

        <div class="source-pane cc-glass-card" data-pane="middle">
          <!-- T14：选中类源码（点行选中标识符） -->
          <SourcePane />
          <!-- F3：AI 问答（点类提问 + 选中标识符行锚点） -->
          <QaPanel />
        </div>
      </div>

      <div
        class="splitter splitter--row"
        :class="{ 'is-dragging': draggingAxis === 'rows' }"
        role="separator"
        aria-orientation="horizontal"
        aria-label="拖拽调整上下分区高度"
        @pointerdown="startDrag('rows', $event)"
      ></div>

      <!-- T24：下区统一 Tab —— 图 / 学习路线 / 测验 / 笔记 / 成就 -->
      <div class="right-pane cc-glass-card" data-pane="bottom">
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

  <!-- 重置布局：右下角小图标（窄屏不启用拖拽，也就没得重置） -->
  <button
    class="layout-reset"
    type="button"
    title="重置布局"
    aria-label="重置布局"
    @click="resetLayout"
  >
    <svg viewBox="0 0 16 16" width="15" height="15" fill="none" aria-hidden="true">
      <path
        d="M13.2 8a5.2 5.2 0 1 1-1.62-3.77"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
      />
      <path
        d="M13.5 2.1v3.3h-3.3"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </button>

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
   否则毛玻璃背后垫着一层白底，玻璃等于白做。

   高度上它要吃掉「顶部栏」与「底部状态条」之间的全部剩余高度（#app 是 100dvh 的 flex 列），
   这样三栏主区的高度就不用写任何 magic number，也不需要 vh 减法。 */
.main-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  /* 必须显式 width: 100%：横向 auto 外边距会吞掉自由空间，从而让 flex 交叉轴的 stretch 失效，
     宽度就会退化成内容 fit-content（窄屏实测卡片只有 588px，没撑满 805px）。
     有了 100% 之后超宽时由 max-width 收口，auto 再把剩余空间分到两侧完成居中。 */
  width: 100%;
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
  flex-shrink: 0;
}

.main-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
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

/* 工作台：上下两区，两个方向都靠 CSS 变量拖拽（不重建 DOM、不动组件状态）
   - 上区高度 = (容器高 - 分隔条) × --h-top，下区吃掉剩余
   - 上区内左右宽度 = (上区宽 - 分隔条) × --w-left / --w-middle
   比例之和恒为 1，所以不会出现裂缝或被顶出边界。 */
.result-layout {
  --cc-splitter: 6px;
  display: flex;
  flex-direction: column;
  flex: 1;
  margin-top: 16px;
  /* 小窗口下宁可整页滚，也不把两区压扁 */
  min-height: 480px;
}

.workbench-top {
  display: grid;
  grid-template-columns:
    calc((100% - var(--cc-splitter)) * var(--w-left, 0.26))
    var(--cc-splitter)
    calc((100% - var(--cc-splitter)) * var(--w-middle, 0.74));
  /* 显式 minmax(0, 1fr)：杜绝行高被内容 max-content 撑开 */
  grid-template-rows: minmax(0, 1fr);
  flex: 0 0 calc((100% - var(--cc-splitter)) * var(--h-top, 0.68));
  min-height: 0;
}

/* 两区各自成一张玻璃卡片，高度撑满自己那块，内部各自滚动 */
.list-pane,
.source-pane,
.right-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 12px;
  overflow: hidden;
}

/* 下区独占整行，吃掉上区与分隔条之外的剩余高度 */
.right-pane {
  flex: 1 1 0;
}

/* 分隔条：透明，悬停/拖拽时亮一条主色线 */
.splitter {
  position: relative;
  background: transparent;
  /* 触屏拖动时不要顺手滚动页面 */
  touch-action: none;
}

.splitter::after {
  content: '';
  position: absolute;
  border-radius: 1px;
  background: var(--cc-accent);
  opacity: 0;
  transition: opacity 0.12s ease;
}

/* 左右分栏用竖向分隔条 */
.splitter--col {
  cursor: col-resize;
}

.splitter--col::after {
  top: 0;
  bottom: 0;
  left: 50%;
  width: 2px;
  transform: translateX(-50%);
}

/* 上下分区用横向分隔条 */
.splitter--row {
  flex: 0 0 var(--cc-splitter);
  cursor: row-resize;
}

.splitter--row::after {
  left: 0;
  right: 0;
  top: 50%;
  height: 2px;
  transform: translateY(-50%);
}

.splitter:hover::after,
.splitter.is-dragging::after {
  opacity: 1;
}

/* 重置布局：右下角小图标 */
.layout-reset {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 30;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--cc-glass-line-strong);
  border-radius: 50%;
  background: var(--cc-glass-bg-panel);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  color: var(--cc-text-muted);
  cursor: pointer;
  transition:
    color 0.16s ease,
    border-color 0.16s ease;
}

.layout-reset:hover {
  color: var(--cc-accent);
  border-color: var(--cc-accent);
}

/* 窄屏（<900px）：不启用拖拽，三块纵向堆叠，每块各占一屏 */
@media (max-width: 899px) {
  .result-layout {
    flex: 0 0 auto;
    min-height: 0;
  }

  .workbench-top {
    display: flex;
    flex-direction: column;
    /* 堆叠后高度由各块自己决定，别被外壳的 100dvh 挤压 */
    flex: 0 0 auto;
    gap: 16px;
  }

  .splitter {
    display: none;
  }

  .list-pane,
  .source-pane,
  .right-pane {
    /* 必须 flex: none：桌面规则里下区是 flex: 1 1 0，flex-basis: 0 会盖掉这里的 height，
       实测下区只剩 min-height 420（别家 730） */
    flex: 0 0 auto;
    height: calc(100dvh - 170px);
    min-height: 420px;
  }

  .layout-reset {
    display: none;
  }
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
