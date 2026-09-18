<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F5 成就面板（T24：从顶部徽章扩展为右栏 Tab 面板）。
 * 展示全部定义的解锁状态；解锁判定在服务端按配置规则做。
 */
const repository = useRepositoryStore()
const { achievements } = storeToRefs(repository)
</script>

<template>
  <div class="achievement-panel cc-glass-strong">
    <p v-if="achievements.length === 0" class="empty-hint">尚无成就定义。</p>
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
</template>

<style scoped>
.achievement-panel {
  max-height: 560px;
  overflow-y: auto;
  /* 第二步：成就面板是一层更厚的毛玻璃（.cc-glass-strong），这里补内衬与分隔线 */
  padding: 8px 12px;
}

.empty-hint {
  color: var(--cc-text-faint);
  text-align: center;
  padding: 12px;
}

.achievement-row {
  padding: 8px 4px;
  border-bottom: 1px solid var(--cc-line);
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
