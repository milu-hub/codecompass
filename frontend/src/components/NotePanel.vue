<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElNotification } from 'element-plus'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F5 笔记面板：编辑当前选中类的笔记（每用户每仓库每类一条，后端 upsert）。
 */
const repository = useRepositoryStore()
const { selectedUnitId, graph, notes } = storeToRefs(repository)

const content = ref('')
const saving = ref(false)

const currentNote = computed(() =>
  notes.value.find((note) => note.codeUnitId === selectedUnitId.value) ?? null,
)

const repoUrl = computed(() => graph.value?.repositoryUrl ?? '')

watch(
  [selectedUnitId, notes],
  () => {
    content.value = currentNote.value?.content ?? ''
  },
  { immediate: true },
)

async function onSave() {
  if (!selectedUnitId.value || !repoUrl.value) {
    return
  }
  saving.value = true
  try {
    await repository.saveNote(repoUrl.value, selectedUnitId.value, content.value)
    ElNotification.success({ title: '笔记已保存', message: 'FIRST_NOTE 可能已解锁' })
    void repository.refreshAchievements().then((newly) => {
      for (const achievement of newly) {
        ElNotification.success({
          title: `成就解锁：${achievement.name}`,
          message: achievement.description,
        })
      }
    })
  } finally {
    saving.value = false
  }
}

async function onDelete() {
  if (!currentNote.value || !repoUrl.value) {
    return
  }
  await repository.removeNote(currentNote.value.id, repoUrl.value)
  content.value = ''
}
</script>

<template>
  <div class="note-panel">
    <div class="note-header">
      <span class="note-title">笔记</span>
      <span v-if="currentNote" class="note-meta">
        {{ currentNote.updatedAt.slice(0, 10) }} 更新
      </span>
    </div>
    <el-input
      v-model="content"
      type="textarea"
      :rows="4"
      placeholder="为当前选中的类写点笔记…"
    />
    <div class="note-actions">
      <el-button size="small" type="primary" :loading="saving" @click="onSave">保存</el-button>
      <el-button v-if="currentNote" size="small" type="danger" text @click="onDelete">
        删除
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.note-panel {
  margin-top: 12px;
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}

.note-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.note-title {
  font-weight: 600;
}

.note-meta {
  font-size: 12px;
  color: #909399;
}

.note-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
