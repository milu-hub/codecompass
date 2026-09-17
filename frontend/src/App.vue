<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useHealthStore } from './stores/health'

const healthStore = useHealthStore()
const { status, service, version, time, loading, error } = storeToRefs(healthStore)

onMounted(() => {
  void healthStore.load()
})
</script>

<template>
  <el-card class="health-card">
    <template #header>CodeCompass 后端连通性</template>

    <el-descriptions :column="1" border>
      <el-descriptions-item label="状态">
        <el-tag :type="status === 'UP' ? 'success' : 'danger'">{{ status }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="服务">{{ service || '-' }}</el-descriptions-item>
      <el-descriptions-item label="版本">{{ version || '-' }}</el-descriptions-item>
      <el-descriptions-item label="服务端时间">{{ time || '-' }}</el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-if="error"
      class="health-error"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />

    <el-button class="health-refresh" :loading="loading" @click="healthStore.load()">
      重新检测
    </el-button>
  </el-card>
</template>

<style scoped>
.health-card {
  max-width: 640px;
  margin: 48px auto;
}

.health-error,
.health-refresh {
  margin-top: 16px;
}
</style>
