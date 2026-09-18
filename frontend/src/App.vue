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
  <header class="app-header">
    <span class="app-title">CodeCompass</span>
    <!-- F5：成就入口（顶部） -->
    <AchievementsBadge />
  </header>
  <RepositoryView />
  <footer class="app-footer">
    <small>后端连通性：{{ status || '…' }} · CodeCompass MVP</small>
  </footer>
</template>

<style scoped>
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: 1100px;
  margin: 0 auto;
  padding: 12px 16px 0;
}

.app-title {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
}

.app-footer {
  text-align: center;
  margin-top: 24px;
  padding: 12px;
  color: #909399;
}
</style>
