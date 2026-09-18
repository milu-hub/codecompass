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
      <span class="class-package" :title="unit.packageName">{{ unit.packageName }}</span>
    </div>
    <p v-if="filtered.length === 0" class="empty-hint">没有匹配的类</p>
  </div>
</template>

<style scoped>
/* 列表吃掉卡片剩余高度，自己滚（原来写死 640px，白瞎了工作台的高度） */
.class-list {
  flex: 1;
  min-height: 0;
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

/* 角色/类型标签不参与收缩，长名字优先让位给省略号 */
.class-row .el-tag {
  flex-shrink: 0;
}

/* 长类名：类名**不参与收缩**（flex 的收缩是按「权重 × 基准宽」成比例分摊的，
   只调权重永远会给类名漏下百分之几，1px 就够触发省略号了）。宽度由 max-width 兜住：
   扣掉进度点、角色标签和间距，只有真的长过整栏才出省略号，同时保证行内不会顶出横向溢出 */
.class-name {
  font-weight: 600;
  flex-shrink: 0;
  max-width: calc(100% - 104px);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  /* 关键：收缩权重给到极大，让包名先把整行让完，类名才不会被挤成省略号。
     flex 的收缩量是按「权重 × 基准宽度」分摊的，只把包名设成 12 时类名仍会被分到百分之几，
     于是一样触发 text-overflow: ellipsis。类名自己保留可收缩能力，超长时兜底省略号。
     这里也不设 max-width：包名只吃类名与标签剩下的那点空间，天然就是次要信息的位置。 */
  flex-shrink: 100;
}

.empty-hint {
  color: #909399;
  padding: 12px;
  text-align: center;
}
</style>
