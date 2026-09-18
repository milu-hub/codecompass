<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import RepositoryView from './views/RepositoryView.vue'
import AchievementsBadge from './components/AchievementsBadge.vue'
import { useHealthStore } from './stores/health'

// T0 的健康卡片已完成使命，缩成页脚一行连通性状态
const health = useHealthStore()
const { status } = storeToRefs(health)

onMounted(() => {
  void health.load()
})
</script>

<template>
  <header class="app-header cc-glass-bar">
    <div class="app-header-inner">
      <span class="app-title">CodeCompass</span>
      <!-- F5：成就入口（顶部） -->
      <AchievementsBadge />
    </div>
  </header>
  <RepositoryView />
  <footer class="app-footer">
    <small>后端连通性：{{ status || '…' }} · CodeCompass MVP</small>
  </footer>
</template>

<style scoped>
/* 第二步：顶部栏做成吸顶毛玻璃条 —— 滚动时内容从玻璃下穿过 */
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  /* 外壳是 flex 列：顶部栏与底部状态条固定，主区吃剩余高度 */
  flex-shrink: 0;
  border-bottom: 1px solid var(--cc-glass-line);
}

.app-header-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: var(--cc-layout-max-width);
  margin: 0 auto;
  padding: 12px 16px;
}

.app-title {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
}

.app-footer {
  /* 页面现在有两屏高：状态条做成吸底，滚动时不会跑到两屏之外（避免"断层"） */
  position: sticky;
  bottom: 0;
  z-index: 90;
  text-align: center;
  /* 页脚只是连通性一行字，别占掉工作台的高度 */
  flex-shrink: 0;
  margin-top: 12px;
  padding: 8px;
  border-top: 1px solid var(--cc-glass-line);
  background: var(--cc-glass-bg-bar);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
  color: #909399;
}
</style>
