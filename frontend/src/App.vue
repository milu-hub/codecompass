<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import RepositoryView from './views/RepositoryView.vue'
import AchievementsBadge from './components/AchievementsBadge.vue'
import SettingsDrawer from './components/SettingsDrawer.vue'
import { profileLabel, useLlmConfig } from './composables/useLlmConfig'
import { useHealthStore } from './stores/health'

// T0 的健康卡片已完成使命，缩成页脚一行连通性状态
const health = useHealthStore()
const { status } = storeToRefs(health)

/** LLM 设置抽屉开合（入口在右上角，与成就徽章并列） */
const settingsVisible = ref(false)

/** 顶栏配置切换器：显示当前配置名，点一下直接切，不用进抽屉。 */
const { store, profiles, configured, activeLabel, activate } = useLlmConfig()

/** el-dropdown 用 command 字符串区分动作；这个哨兵值表示"去抽屉里管理"。 */
const OPEN_SETTINGS = '__open_settings'

function onSwitchProfile(command: string): void {
  if (command === OPEN_SETTINGS) {
    settingsVisible.value = true
    return
  }
  activate(command)
}

onMounted(() => {
  void health.load()
})
</script>

<template>
  <header class="app-header cc-glass-bar">
    <div class="app-header-inner">
      <span class="app-title">CodeCompass</span>
      <div class="app-header-actions">
        <!-- F5：成就入口（顶部） -->
        <AchievementsBadge />
        <!-- 当前 LLM 配置：下拉直接切换，不用进抽屉 -->
        <el-dropdown trigger="click" @command="onSwitchProfile">
          <span class="profile-switcher" :class="{ 'is-configured': configured }">
            <span class="profile-dot" />
            <span class="profile-label">{{ activeLabel }}</span>
            <span class="profile-caret">▾</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="profiles.length === 0" disabled>
                还没有配置（用服务端默认额度）
              </el-dropdown-item>
              <el-dropdown-item
                v-for="profile in profiles"
                :key="profile.id"
                :command="profile.id"
              >
                {{ (profile.id === store.activeId ? '✓ ' : '') + profileLabel(profile) }}
              </el-dropdown-item>
              <el-dropdown-item divided :command="OPEN_SETTINGS">管理配置…</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- 用户自填 LLM key 的设置入口（与成就徽章并列） -->
        <el-button size="small" text @click="settingsVisible = true">⚙️ 设置</el-button>
      </div>
    </div>
  </header>
  <SettingsDrawer v-model="settingsVisible" />
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

/* 右侧动作组：成就徽章 + 配置切换器 + 设置入口并排 */
.app-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 顶栏配置切换器：胶囊样式，与设置按钮同高；圆点表示"已有可用配置" */
.profile-switcher {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 170px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--cc-glass-line-strong);
  border-radius: var(--cc-radius-input);
  background: rgba(255, 255, 255, 0.6);
  font-size: 12px;
  color: var(--cc-text-muted);
  cursor: pointer;
}

.profile-switcher:hover {
  border-color: var(--cc-text-faint);
}

.profile-switcher.is-configured {
  color: var(--cc-ink);
}

.profile-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cc-text-faint);
}

.profile-switcher.is-configured .profile-dot {
  background: var(--cc-accent);
}

.profile-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-caret {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--cc-text-faint);
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
