<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElNotification } from 'element-plus'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F5 成就入口（顶部徽章）：展示全部成就与解锁状态。
 *
 * 红点语义 = 「有已解锁但还没看过的成就」：
 * - 点开弹层（含点击徽章本身）就记为已读，红点随即消失；
 * - 已读标记持久化在本地，刷新后不会把老成就又当新解锁。
 */
const repository = useRepositoryStore()
const { achievements, phase, unseenAchievementCount } = storeToRefs(repository)

const popoverVisible = ref(false)

watch(popoverVisible, (visible) => {
  if (visible) {
    repository.markAchievementsSeen()
  }
})

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

/**
 * 首屏只做「静默同步」：把服务端已有的成就灌进 store 当作基线。
 * 之前这里直接走 notifyNewAchievements，而 store 初始是空列表，
 * 于是每次刷新页面「所有已解锁成就」都被判成本次新解锁，一进页面就弹一堆提示。
 */
onMounted(() => {
  void repository.refreshAchievements().catch(() => {
    // 首屏同步失败不打扰主流程
  })
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
  <el-popover
    v-model:visible="popoverVisible"
    placement="bottom-end"
    trigger="click"
    width="280"
  >
    <template #reference>
      <el-badge
        :value="unseenAchievementCount"
        :hidden="unseenAchievementCount === 0"
        class="achievement-badge"
      >
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
