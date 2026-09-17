<script setup lang="ts">
import { computed } from 'vue'
import type { UnitView } from '../api/repos'

const props = defineProps<{
  units: UnitView[]
  selectedId: string | null
  filterText: string
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
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
}

.class-row:hover {
  background: #f5f7fa;
}

.class-row.selected {
  background: #ecf5ff;
}

.class-name {
  font-weight: 600;
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
