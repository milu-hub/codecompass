<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { ElNotification } from 'element-plus'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F5 成就入口（顶部徽章）：展示全部成就与解锁状态；
 * 分析完成（phase → done）时刷新，新解锁弹提示。
 */
const repository = useRepositoryStore()
const { achievements, phase } = storeToRefs(repository)

const unlockedCount = () => achievements.value.filter((a) => a.unlockedAt).length

async function notifyNewAchievements() {
  try {
    const newly = await repository.refreshAchievements()
    for (const achievement of newly) {
      ElNotification.success({
        title: `成就解锁：${achievement.name}`,
        message: achievement.description,
      })
    }
  } catch {
    // 成就加载失败不打扰主流程
  }
}

onMounted(() => {
  void notifyNewAchievements()
})

watch(
  () => phase.value,
  (value) => {
    if (value === 'done') {
      void notifyNewAchievements()
    }
  },
)
</script>

<template>
  <el-popover placement="bottom-end" trigger="click" width="280">
    <template #reference>
      <el-badge :value="unlockedCount()" :hidden="unlockedCount() === 0" class="achievement-badge">
        <el-button size="small" text>🏆 成就</el-button>
      </el-badge>
    </template>
    <div class="achievement-list">
      <div
        v-for="achievement in achievements"
        :key="achievement.code"
        class="achievement-row"
        :class="{ unlocked: achievement.unlockedAt }"
      >
        <span class="achievement-name">{{ achievement.name }}</span>
        <span v-if="achievement.unlockedAt" class="unlocked-mark">已解锁</span>
        <span v-else class="locked-mark">未解锁</span>
        <p class="achievement-desc">{{ achievement.description }}</p>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.achievement-badge {
  display: inline-flex;
  align-items: center;
}

.achievement-list {
  max-height: 360px;
  overflow-y: auto;
}

.achievement-row {
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
}

.achievement-row.unlocked .achievement-name {
  color: #67c23a;
}

.achievement-name {
  font-weight: 600;
}

.unlocked-mark {
  font-size: 12px;
  color: #67c23a;
  margin-left: 8px;
}

.locked-mark {
  font-size: 12px;
  color: #c0c4cc;
  margin-left: 8px;
}

.achievement-desc {
  margin: 2px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
