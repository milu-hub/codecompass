<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
// 只取类型（import type 编译后被擦除，不会把 mermaid 拉进首屏包）
import type { MermaidConfig } from 'mermaid'

const props = defineProps<{ mermaidText: string }>()

const container = ref<HTMLDivElement | null>(null)

// 渲染竞态令牌：快速切换类时，晚到的旧图不能盖住新图
let renderToken = 0
let initialized = false

/**
 * 超过这个边数就不交给 mermaid 画了，改成可读的降级说明。
 *
 * 取 1000 的理由：一是**可读性** —— 类级依赖图上千条边就是一团毛线，画出来也没法看；
 * 二是**渲染成本** —— dagre 布局在千级边上会明显卡顿。阈值之下的图照常渲染。
 */
const MAX_RENDERABLE_EDGES = 1000

/**
 * Mermaid 配置（第六步）。
 *
 * - theme 必须用 'base'：只有 base 会读 themeVariables，default 主题会把后端给的柔和角色色压掉；
 * - curve: 'basis' + nodeSpacing/rankSpacing：线条走平滑曲线、节点与层级之间留出呼吸空间，
 *   默认值在大图里会挤成一团；
 * - lineColor 取冷绿中性色，和全站中性色同族（也顺带是"配置真的生效了"的验收入口）；
 * - maxEdges：mermaid 默认只有 **500**，一超就抛 `Edge limit exceeded`（真机实测 flask 512 条边
 *   即触发，界面只剩"渲染失败"四个字）。这里放到远高于我们的产品阈值当**安全网** ——
 *   万一两边的计数口径有出入，也不会以硬失败的形式暴露给用户。
 */
const MERMAID_CONFIG: MermaidConfig = {
  startOnLoad: false,
  maxEdges: 5000,
  theme: 'base',
  themeVariables: {
    fontFamily:
      "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    fontSize: '13px',
    primaryColor: '#f1f5f3',
    primaryBorderColor: '#98a6a0',
    primaryTextColor: '#1f2a27',
    lineColor: '#98a6a0',
    /* 不设 nodeBorder 时 base 主题会给无角色节点套一层渐变现描边，写平更干净 */
    nodeBorder: '#98a6a0',
    tertiaryColor: '#f7fbf9',
  },
  flowchart: {
    curve: 'basis',
    nodeSpacing: 40,
    rankSpacing: 60,
    useMaxWidth: true,
  },
}

/** 全图边数 / 节点数：纯文本统计，数的是 mermaid 真正会读的那些行。 */
function countEdges(text: string): number {
  return text.split('\n').filter((line) => line.includes('-->')).length
}

function countNodes(text: string): number {
  return text.split('\n').filter((line) => /^\s*n\d+\[/.test(line)).length
}

/**
 * 超大图的降级说明。
 *
 * 说的是「图太大，已跳过渲染」而不是「渲染失败」—— 后者会让用户以为程序坏了，
 * 而这里其实是一个**有意的产品行为**：这种规模的图本来也读不了。
 */
function tooLargeHtml(edges: number, nodes: number): string {
  return '<div class="graph-too-large">'
    + '<p class="graph-too-large-title">图太大，已跳过渲染</p>'
    + '<p class="graph-too-large-desc">当前全图 ' + nodes + ' 个节点、' + edges
    + ' 条边，超过可读上限（' + MAX_RENDERABLE_EDGES + ' 条）。'
    + '点击左侧的类查看它的一跳邻域 —— 那里通常只有几条边，比全图清楚得多。</p>'
    + '</div>'
}

/** mermaid 自己的边数上限报错：同样归到「图太大」，不让它表现为「渲染失败」。 */
function isEdgeLimitError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  return /edge limit exceeded|maxedges/i.test(message)
}

/** 从节点分组的 id 里取出 mermaid 节点 id。
 *  实测 mermaid 12 会把渲染 id 当**前缀**拼上去：{@code cc-graph-2-flowchart-n0-0}，
 *  所以只取 flowchart- 之后、末尾 -数字 之前的那段（不同版本前缀/分隔符都不一样）。 */
function nodeKey(group: Element): string | null {
  const dataId = group.getAttribute('data-id')
  if (dataId) {
    return dataId
  }
  const match = /flowchart-(.+?)-\d+$/.exec(group.id)
  return match ? match[1] : null
}

/** 边的两端。
 *  Mermaid 12 的边 id 形如 {@code cc-graph-2-L_n0_n1_0}（渲染前缀 + 下划线），
 *  旧版本是 {@code L-n0-n1-0}；有的版本还会给边挂 LS-/LE- 类名。三种都认。
 *  只采纳形如 n0/n12 的片段，避免把前缀或计数器误当节点 id。 */
function edgeEndpoints(edge: Element): { from: string; to: string } | null {
  const classes = Array.from(edge.classList)
  const fromClass = classes.find((name) => name.startsWith('LS-'))?.slice(3)
  const toClass = classes.find((name) => name.startsWith('LE-'))?.slice(3)
  if (fromClass && toClass) {
    return { from: fromClass, to: toClass }
  }
  const tail = edge.id.replace(/^.*?L[_-]/, '')
  const ids = tail.split(/[_-]/).filter((part) => /^n\d+$/.test(part))
  return ids.length >= 2 ? { from: ids[0], to: ids[1] } : null
}

/**
 * 悬停高亮：把与当前节点相连的边点亮，其余边淡到 0.3。
 * 用事件委托（mouseover/mouseout + closest）而不是给每个节点挂监听：
 * 换图时 innerHTML 整个替换，委托只需要在容器上挂一次，也不会有遗留监听。
 */
function wireHover(root: HTMLElement): void {
  const nodes = Array.from(root.querySelectorAll('g.node'))
  const edges = Array.from(root.querySelectorAll('.edgePaths path, path.flowchart-link'))

  const clear = () => {
    for (const edge of edges) {
      edge.classList.remove('cc-edge-active', 'cc-edge-dimmed')
    }
    for (const node of nodes) {
      node.classList.remove('cc-node-active')
    }
  }

  const highlight = (key: string) => {
    for (const edge of edges) {
      const ends = edgeEndpoints(edge)
      const connected = !!ends && (ends.from === key || ends.to === key)
      edge.classList.toggle('cc-edge-active', connected)
      edge.classList.toggle('cc-edge-dimmed', !connected)
    }
    for (const node of nodes) {
      node.classList.toggle('cc-node-active', nodeKey(node) === key)
    }
  }

  const onOver = (event: Event) => {
    const group = (event.target as Element | null)?.closest('g.node')
    const key = group ? nodeKey(group) : null
    if (key) {
      highlight(key)
    }
  }

  const onOut = (event: Event) => {
    const related = (event as MouseEvent).relatedTarget as Element | null
    // 指针还在同一个节点内部移动时不清除
    if (related?.closest('g.node') === (event.target as Element | null)?.closest('g.node')) {
      return
    }
    clear()
  }

  root.addEventListener('mouseover', onOver)
  root.addEventListener('mouseout', onOut)
}

async function render() {
  if (!container.value || !props.mermaidText) {
    return
  }
  const token = ++renderToken
  const edges = countEdges(props.mermaidText)
  const nodes = countNodes(props.mermaidText)

  // 超大图不交给 mermaid：它默认 maxEdges=500 会直接抛错，界面就只剩「渲染失败」四个字。
  // 主动跳过并给出可读的说明 —— 这种规模的图本来也读不了。
  if (edges > MAX_RENDERABLE_EDGES) {
    container.value.innerHTML = tooLargeHtml(edges, nodes)
    return
  }

  container.value.innerHTML = '<p class="graph-loading">图渲染中…</p>'
  try {
    // 动态 import：mermaid 体积大，只在真正要渲染图时才加载，首屏不受拖累
    const mermaidModule = await import('mermaid')
    const mermaid = mermaidModule.default
    if (!initialized) {
      mermaid.initialize(MERMAID_CONFIG)
      initialized = true
    }
    const { svg } = await mermaid.render('cc-graph-' + token, props.mermaidText)
    if (token !== renderToken) {
      return // 过期渲染丢弃
    }
    container.value.innerHTML = svg
    wireHover(container.value)
  } catch (error) {
    if (token === renderToken) {
      // 边数上限也归到「图太大」：这是有意的产品行为，不是故障
      container.value.innerHTML = isEdgeLimitError(error)
        ? tooLargeHtml(edges, nodes)
        : '<p class="graph-error">依赖图渲染失败</p>'
      console.error('mermaid 渲染失败：', error)
    }
  }
}

watch(
  () => props.mermaidText,
  () => {
    void render()
  },
)

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
  /* 不再写死 max-height：图区现在是两屏高的布局里的一整行，交给外层 Tab 内容区滚动 */
}

.graph-pane :deep(svg) {
  max-width: 100%;
  height: auto;
}

.graph-pane :deep(.graph-error) {
  color: #f56c6c;
}

/* 渲染中：大图会先加载 mermaid 再布局，给一行提示，避免看起来像空白/坏掉 */
.graph-pane :deep(.graph-loading) {
  color: var(--cc-text-muted);
  font-size: 13px;
}

/* 超大图的降级说明：说清"为什么不画"和"下一步该看什么"，不是错误红 */
.graph-pane :deep(.graph-too-large) {
  max-width: 520px;
  padding: 12px 14px;
  border: 1px solid rgba(230, 162, 60, 0.32);
  border-radius: var(--cc-radius-row);
  background: rgba(230, 162, 60, 0.1);
}

.graph-pane :deep(.graph-too-large-title) {
  margin: 0 0 4px;
  /* 加深过的琥珀：#96650f 在白底约 5.0:1，达 AA */
  color: #96650f;
  font-size: 13px;
  font-weight: 600;
}

.graph-pane :deep(.graph-too-large-desc) {
  margin: 0;
  /* --cc-text-muted 在白底约 4.8:1，达 AA */
  color: var(--cc-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

/* 悬停：相连的边点亮成主色加粗，其余淡到 0.3；被悬停的节点描边加粗。
   这里必须用 !important：Mermaid 会把样式以 **ID 选择器**注入到 SVG 内部
   （形如 #cc-graph-2 .flowchart-link{stroke:...}），而 ID 选择器的优先级
   比我们任何类选择器组合都高 —— 实测不加 !important 时 stroke / stroke-width 完全不生效
   （opacity 因为 Mermaid 没设才生效）。!important 同时也压得住内联 style，两种机制都稳。 */
.graph-pane :deep(.edgePaths path),
.graph-pane :deep(path.flowchart-link) {
  transition:
    opacity 0.12s ease,
    stroke 0.12s ease,
    stroke-width 0.12s ease;
}

.graph-pane :deep(.cc-edge-dimmed) {
  opacity: 0.3;
}

.graph-pane :deep(.cc-edge-active) {
  stroke: var(--cc-accent) !important;
  stroke-width: 2px !important;
}

.graph-pane :deep(g.node) {
  transition: opacity 0.12s ease;
}

.graph-pane :deep(g.node.cc-node-active rect),
.graph-pane :deep(g.node.cc-node-active polygon),
.graph-pane :deep(g.node.cc-node-active circle) {
  stroke-width: 2px !important;
}
</style>
