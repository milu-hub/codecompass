<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import RepositoryView from './views/RepositoryView.vue'
import { useHealthStore } from './stores/health'

// T0 的健康卡片已完成使命，缩成页脚一行连通性状态
const health = useHealthStore()
const { status } = storeToRefs(health)

onMounted(() => {
  void health.load()
})
</script>

<template>
  <RepositoryView />
  <footer class="app-footer">
    <small>后端连通性：{{ status || '…' }} · CodeCompass MVP</small>
  </footer>
</template>

<style scoped>
.app-footer {
  text-align: center;
  margin-top: 24px;
  padding: 12px;
  color: #909399;
}
</style>
