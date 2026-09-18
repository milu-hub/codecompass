<script setup lang="ts">
import { computed } from 'vue'
import type { UnitView } from '../api/repos'

const props = defineProps<{
  units: UnitView[]
  selectedId: string | null
  filterText: string
  /** F5：codeUnitId → status（unread/reading/done）的进度标记 */
  progress?: Record<string, string>
}>()

const emit = defineEmits<{ select: [id: string] }>()

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

const roleTagType: Record<string, TagType> = {
  entry: 'warning',
  controller: 'primary',
  service: 'success',
  repository: 'danger',
  component: 'info',
}

/** F5：进度标记颜色。 */
function progressDot(status: string | undefined): string {
  if (status === 'done') {
    return '#67c23a'
  }
  if (status === 'reading') {
    return '#e6a23c'
  }
  return '#dcdfe6'
}

const filtered = computed(() => {
  const keyword = props.filterText.trim().toLowerCase()
  if (!keyword) {
    return props.units
  }
  return props.units.filter(
    (unit) =>
      unit.name.toLowerCase().includes(keyword) ||
      unit.packageName.toLowerCase().includes(keyword),
  )
})
</script>

<template>
  <div class="class-list">
    <div
      v-for="unit in filtered"
      :key="unit.id"
      class="class-row"
      :class="{ selected: unit.id === selectedId }"
      @click="emit('select', unit.id)"
    >
      <span
        class="progress-dot"
        :style="{ background: progressDot(progress?.[unit.id]) }"
        :title="progress?.[unit.id] ? `进度：${progress[unit.id]}` : '未读'"
      ></span>
      <span class="class-name">{{ unit.name }}</span>
      <el-tag v-if="unit.role" :type="roleTagType[unit.role] ?? 'info'" size="small" effect="plain">
        {{ unit.role }}
      </el-tag>
      <el-tag v-else size="small" type="info" effect="plain">{{ unit.kind }}</el-tag>
      <span class="class-package">{{ unit.packageName }}</span>
    </div>
    <p v-if="filtered.length === 0" class="empty-hint">没有匹配的类</p>
  </div>
</template>

<style scoped>
.class-list {
  max-height: 640px;
  overflow-y: auto;
}

.class-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  /* 第二步之后：行默认透明，让玻璃卡片的底透上来；圆角按「交互行 8px」规则 */
  border-radius: var(--cc-radius-row);
  cursor: pointer;
  background: transparent;
  transition: background-color 0.16s ease;
}

.class-row:hover {
  background: var(--cc-accent-tint);
}

.class-row.selected {
  background: var(--cc-accent-soft);
}

/* 选中态：左侧 3px 主色竖条（上下各内缩 8px，避免顶到圆角） */
.class-row.selected::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 2px;
  background: var(--cc-accent);
}

.class-name {
  font-weight: 600;
}

.progress-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #dcdfe6;
}

.class-package {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 45%;
}

.empty-hint {
  color: #909399;
  padding: 12px;
  text-align: center;
}
</style>
