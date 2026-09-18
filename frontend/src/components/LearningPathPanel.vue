<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F2 学习路线面板：按 order 展示步骤，点步跳转到对应类；显示预计时间；展开/折叠。
 */
const repository = useRepositoryStore()
const { learningPath, graph } = storeToRefs(repository)

const generating = ref(false)
const collapsed = ref(false)

const visibleSteps = computed(() => {
  const path = learningPath.value
  if (!path) {
    return []
  }
  return collapsed.value ? path.steps.slice(0, 5) : path.steps
})

const totalMinutes = computed(() =>
  (learningPath.value?.steps ?? []).reduce((sum, step) => sum + step.estimatedMinutes, 0),
)

function unitName(codeUnitId: string): string {
  return graph.value?.codeUnits.find((unit) => unit.id === codeUnitId)?.name ?? codeUnitId
}

function unitPackage(codeUnitId: string): string {
  return graph.value?.codeUnits.find((unit) => unit.id === codeUnitId)?.packageName ?? ''
}

async function onGenerate() {
  generating.value = true
  try {
    await repository.generateLearningPath()
  } finally {
    generating.value = false
  }
}
</script>

<template>
  <div class="learning-path">
    <div v-if="!learningPath" class="path-empty">
      <p>尚未生成学习路线。</p>
      <el-button type="primary" :loading="generating" @click="onGenerate">生成学习路线</el-button>
    </div>

    <div v-else class="path-body">
      <div class="path-header">
        <span class="path-meta">
          共 {{ learningPath.steps.length }} 步 · 预计 {{ totalMinutes }} 分钟
        </span>
        <el-button size="small" text type="primary" @click="collapsed = !collapsed">
          {{ collapsed ? '展开全部' : '只看前 5 步' }}
        </el-button>
      </div>

      <ol class="step-list">
        <li
          v-for="step in visibleSteps"
          :key="step.codeUnitId"
          class="step-row"
          @click="repository.selectUnit(step.codeUnitId)"
        >
          <span class="step-order">{{ step.order }}</span>
          <span class="step-name">{{ unitName(step.codeUnitId) }}</span>
          <span class="step-minutes">{{ step.estimatedMinutes }} 分钟</span>
          <p class="step-reason">{{ step.reason }}</p>
        </li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.learning-path {
  padding: 4px 0;
}

.path-empty {
  padding: 16px 8px;
  color: #909399;
  text-align: center;
}

.path-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.path-meta {
  font-size: 13px;
  color: #606266;
}

.step-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 560px;
  overflow-y: auto;
}

.step-row {
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
}

.step-row:hover {
  background: #f5f7fa;
}

.step-order {
  display: inline-block;
  width: 24px;
  height: 24px;
  line-height: 24px;
  text-align: center;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 12px;
  margin-right: 8px;
}

.step-name {
  font-weight: 600;
  margin-right: 8px;
}

.step-minutes {
  font-size: 12px;
  color: #909399;
}

.step-reason {
  margin: 4px 0 0 32px;
  font-size: 12px;
  color: #909399;
}
</style>
