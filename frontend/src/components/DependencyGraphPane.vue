<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ mermaidText: string }>()

const container = ref<HTMLDivElement | null>(null)

// 渲染竞态令牌：快速切换类时，晚到的旧图不能盖住新图
let renderToken = 0
let initialized = false

async function render() {
  if (!container.value || !props.mermaidText) {
    return
  }
  const token = ++renderToken
  try {
    // 动态 import：mermaid 体积大，只在真正要渲染图时才加载，首屏不受拖累
    const mermaidModule = await import('mermaid')
    const mermaid = mermaidModule.default
    if (!initialized) {
      mermaid.initialize({ startOnLoad: false, theme: 'default' })
      initialized = true
    }
    const { svg } = await mermaid.render('cc-graph-' + token, props.mermaidText)
    if (token !== renderToken) {
      return // 过期渲染丢弃
    }
    container.value.innerHTML = svg
  } catch (error) {
    if (token === renderToken) {
      container.value.innerHTML = '<p class="graph-error">依赖图渲染失败</p>'
      console.error('mermaid 渲染失败：', error)
    }
  }
}

watch(() => props.mermaidText, () => {
  void render()
})

onMounted(() => {
  void render()
})

onBeforeUnmount(() => {
  // 组件卸载后，一切在途渲染全部作废
  renderToken++
})
</script>

<template>
  <div ref="container" class="graph-pane"></div>
</template>

<style scoped>
.graph-pane {
  overflow: auto;
  max-height: 640px;
}

.graph-pane :deep(svg) {
  max-width: 100%;
  height: auto;
}

.graph-error {
  color: #f56c6c;
}
</style>
