/**
 * 工作台布局：可拖拽的「上区 = 类列表 + 源码」「下区 = 图/路线/测验/笔记/成就」。
 *
 * 结构（仿 IDE 上下分区）：
 *
 *     ┌──────────┬─┬────────────────────────────┐
 *     │ 类列表   │▮│ 源码 + 问答                │  ← 上区，左右宽度可拖
 *     ├──────────┴─┴────────────────────────────┤
 *     │        ▬  上下高度可拖的分隔条           │
 *     ├─────────────────────────────────────────┤
 *     │ 依赖图 / 学习路线 / 测验 / 笔记 / 成就   │  ← 下区，独占整行
 *     └─────────────────────────────────────────┘
 *
 * 两条铁律，和之前三栏版一致：
 * 1. **CSS 变量只存比例**（--w-left/--w-middle/--h-top，和恒为 1），拖拽时只改它们，
 *    不重建 DOM、不动组件状态，因此也不会触发 Mermaid 重画；
 * 2. **像素上下限只在拖拽裁决里用**，不写进 DOM、不参与恢复，避免"存的比例合法、渲染却违规"。
 *
 * 两个方向共用同一个裁决函数 resolvePair：两段式拖拽是纯此消彼长（没有三栏那种级联），
 * 所以可以用一个闭式解，不需要迭代。
 */
import { computed, reactive, ref, type Ref } from 'vue'

/** 竖向分隔条宽度 = 横向分隔条高度（6px），必须与 CSS 里的 --cc-splitter 一致 */
export const SPLITTER_SIZE = 6

export const COLUMN_STORAGE_KEY = 'codecompass.layout.columns'
export const ROW_STORAGE_KEY = 'codecompass.layout.rows'

export interface ColumnWidths {
  left: number
  middle: number
}

export interface RowHeights {
  top: number
  bottom: number
}

/** 默认比例：左栏给到能完整显示类名（26% ≈ 340px），上区让编辑器占大头（68%） */
export const DEFAULT_COLUMNS: ColumnWidths = { left: 26, middle: 74 }
export const DEFAULT_ROWS: RowHeights = { top: 68, bottom: 32 }

export interface PairLimits {
  min: number
  max: number
}

/** 列方向：左栏 200-400；源码栏只要 ≥400（它是主工作面，上限交给容器） */
export const COLUMN_LIMITS: { first: PairLimits; second: PairLimits } = {
  first: { min: 200, max: 400 },
  second: { min: 400, max: Number.POSITIVE_INFINITY },
}

/** 行方向：上区（编辑器）≥300；下区 ≥160（下区没上限，编辑器的最小值已经保护了它） */
export const ROW_LIMITS: { first: PairLimits; second: PairLimits } = {
  first: { min: 300, max: Number.POSITIVE_INFINITY },
  second: { min: 160, max: Number.POSITIVE_INFINITY },
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

/** 容器太小、两段最小值加起来放不下时按比例缩（900px 附近必现），保证约束一定有解。 */
function scaleLimits(
  limits: { first: PairLimits; second: PairLimits },
  available: number,
): { first: PairLimits; second: PairLimits } {
  const minSum = limits.first.min + limits.second.min
  if (available >= minSum || available <= 0) {
    return limits
  }
  const scale = available / minSum
  const scaleOne = (limit: PairLimits): PairLimits => ({
    min: limit.min * scale,
    max: Number.isFinite(limit.max) ? limit.max * scale : limit.max,
  })
  return { first: scaleOne(limits.first), second: scaleOne(limits.second) }
}

/**
 * 两段式拖拽裁决：给定第一段的目标像素尺寸，返回满足双方上下限的两段尺寸（和恒为 available）。
 *
 * 可行区间是闭式的：第一段必须 ≤ available - 第二段最小（别把对方挤穿），
 * 也要 ≥ available - 第二段最大（别让对方超上限）。上下限之和已被 scaleLimits 保证放得下。
 */
export function resolvePair(options: {
  targetPx: number
  available: number
  limits: { first: PairLimits; second: PairLimits }
}): { first: number; second: number } {
  const { targetPx, available, limits } = options
  const scaled = scaleLimits(limits, available)
  const first = scaled.first
  const second = scaled.second
  const lower = Number.isFinite(second.max) ? Math.max(first.min, available - second.max) : first.min
  const upper = Math.min(first.max, available - second.min)
  const low = Math.min(lower, upper)
  const high = Math.max(lower, upper)
  const firstPx = clamp(targetPx, low, high)
  return { first: firstPx, second: Math.max(0, available - firstPx) }
}

function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

function readStoredPair(
  key: string,
  firstKey: string,
  secondKey: string,
  fallback: { first: number; second: number },
): { first: number; second: number } {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) {
      return { ...fallback }
    }
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') {
      return { ...fallback }
    }
    const record = parsed as Record<string, unknown>
    const firstValue = record[firstKey]
    const secondValue = record[secondKey]
    if (!isPositiveNumber(firstValue) || !isPositiveNumber(secondValue)) {
      return { ...fallback }
    }
    // 归一化到 100：老的三栏数据（left/middle/right）也能平滑迁移过来
    const total = firstValue + secondValue
    return {
      first: (firstValue / total) * 100,
      second: (secondValue / total) * 100,
    }
  } catch {
    // 隐私模式 / 脏数据
    return { ...fallback }
  }
}

function storePair(key: string, firstKey: string, secondKey: string, first: number, second: number): void {
  try {
    localStorage.setItem(key, JSON.stringify({ [firstKey]: first, [secondKey]: second }))
  } catch {
    // 写不进去不影响本次会话
  }
}

export function readStoredColumns(): ColumnWidths {
  const pair = readStoredPair(COLUMN_STORAGE_KEY, 'left', 'middle', {
    first: DEFAULT_COLUMNS.left,
    second: DEFAULT_COLUMNS.middle,
  })
  return { left: pair.first, middle: pair.second }
}

export function readStoredRows(): RowHeights {
  const pair = readStoredPair(ROW_STORAGE_KEY, 'top', 'bottom', {
    first: DEFAULT_ROWS.top,
    second: DEFAULT_ROWS.bottom,
  })
  return { top: pair.first, bottom: pair.second }
}

type Axis = 'columns' | 'rows'

/**
 * 布局组合式：状态 + 落库 + 原生指针拖拽（不引入任何第三方库）。
 * 两个方向共用一份拖拽实现，差异只有 6 处（坐标轴、可用尺寸、读取目标、上限组、CSS 变量、存储键）。
 */
export function useResizableLayout(containerRef: Ref<HTMLElement | null>) {
  const columns = reactive<ColumnWidths>(readStoredColumns())
  const rows = reactive<RowHeights>(readStoredRows())

  const layoutStyle = computed(() => ({
    '--w-left': String(columns.left / 100),
    '--w-middle': String(columns.middle / 100),
    '--h-top': String(rows.top / 100),
  }))

  /** 正在拖的方向（用于分隔条高亮） */
  const draggingAxis = ref<Axis | null>(null)

  function paneBox(pane: string, dim: 'width' | 'height'): number {
    const container = containerRef.value
    if (!container) {
      return 0
    }
    const element = container.querySelector<HTMLElement>(`[data-pane="${pane}"]`)
    if (!element) {
      return 0
    }
    const rect = element.getBoundingClientRect()
    return dim === 'width' ? rect.width : rect.height
  }

  function availableFor(axis: Axis): number {
    const container = containerRef.value
    if (!container) {
      return 0
    }
    const rect = container.getBoundingClientRect()
    return (axis === 'columns' ? rect.width : rect.height) - SPLITTER_SIZE
  }

  function applyPair(axis: Axis, firstPx: number, secondPx: number): void {
    const container = containerRef.value
    if (!container) {
      return
    }
    const total = firstPx + secondPx
    if (total <= 0) {
      return
    }
    // 直接写 CSS 变量：不经过响应式状态，拖拽期间组件零重渲染，也就碰不到 Mermaid
    if (axis === 'columns') {
      container.style.setProperty('--w-left', String(firstPx / total))
      container.style.setProperty('--w-middle', String(secondPx / total))
    } else {
      container.style.setProperty('--h-top', String(firstPx / total))
    }
  }

  function commitPair(axis: Axis, firstPx: number, secondPx: number): void {
    const total = firstPx + secondPx
    if (total <= 0) {
      return
    }
    // 状态存精确比例（松手时 DOM 不会退回取整值、不会跳），localStorage 存整数百分比
    const firstPercent = (firstPx / total) * 100
    const secondPercent = 100 - firstPercent
    if (axis === 'columns') {
      columns.left = firstPercent
      columns.middle = secondPercent
      storePair(COLUMN_STORAGE_KEY, 'left', 'middle', Math.round(firstPercent), Math.round(secondPercent))
    } else {
      rows.top = firstPercent
      rows.bottom = secondPercent
      storePair(ROW_STORAGE_KEY, 'top', 'bottom', Math.round(firstPercent), Math.round(secondPercent))
    }
  }

  function startDrag(axis: Axis, event: PointerEvent): void {
    const container = containerRef.value
    const splitter = event.currentTarget as HTMLElement | null
    if (!container || !splitter || event.button !== 0) {
      return
    }
    const available = availableFor(axis)
    if (available <= 0) {
      return
    }
    const limits = axis === 'columns' ? COLUMN_LIMITS : ROW_LIMITS
    const startFirst = axis === 'columns' ? paneBox('left', 'width') : paneBox('top', 'height')
    const startCoord = axis === 'columns' ? event.clientX : event.clientY

    draggingAxis.value = axis
    // 捕获可能因浏览器差异失败（合成指针事件下实测会立刻 lostpointercapture），
    // 失败也不影响拖拽：真正的事件通道是下面挂在 window 上的监听
    try {
      splitter.setPointerCapture(event.pointerId)
    } catch {
      // 忽略
    }
    splitter.classList.add('is-dragging')
    document.body.classList.add(axis === 'columns' ? 'cc-resizing-col' : 'cc-resizing-row')

    let frame = 0
    let pendingCoord = startCoord
    let lastResolved: { first: number; second: number } | null = null
    let finished = false

    const render = () => {
      frame = 0
      const target = startFirst + (pendingCoord - startCoord)
      const resolved = resolvePair({ targetPx: target, available, limits })
      lastResolved = resolved
      applyPair(axis, resolved.first, resolved.second)
    }

    const onMove = (moveEvent: PointerEvent) => {
      // 兜底：按键已松开却没收到 pointerup（松在窗口外 / 捕获被释放）时用 buttons 收尾
      if (moveEvent.buttons === 0) {
        finish()
        return
      }
      pendingCoord = axis === 'columns' ? moveEvent.clientX : moveEvent.clientY
      // requestAnimationFrame 节流到 ~16ms，指针事件比帧率密
      if (!frame) {
        frame = requestAnimationFrame(render)
      }
    }

    /**
     * 结束拖拽。
     *
     * 监听挂 window 而不是分隔条本身：只挂元素上时，一旦捕获丢失就既收不到 pointermove
     * 也收不到 pointerup —— 实测表现是"拖不动，而且 body 的 cursor/禁选类永远摘不掉"。
     * 另外**不能**把 lostpointercapture 当结束：合成指针下它会在第一个 move 之前触发，
     * 若在那里收尾等于自己把监听摘掉、拖拽立刻失效。
     */
    const finish = () => {
      if (finished) {
        return
      }
      finished = true
      if (frame) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', finish)
      window.removeEventListener('blur', finish)
      splitter.classList.remove('is-dragging')
      document.body.classList.remove('cc-resizing-col', 'cc-resizing-row')
      draggingAxis.value = null
      if (lastResolved) {
        commitPair(axis, lastResolved.first, lastResolved.second)
      }
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', finish)
    window.addEventListener('pointercancel', finish)
    window.addEventListener('blur', finish)
  }

  /** 恢复默认比例（右下角小图标触发），两个方向一起复位。 */
  function resetLayout(): void {
    columns.left = DEFAULT_COLUMNS.left
    columns.middle = DEFAULT_COLUMNS.middle
    rows.top = DEFAULT_ROWS.top
    rows.bottom = DEFAULT_ROWS.bottom
    storePair(COLUMN_STORAGE_KEY, 'left', 'middle', DEFAULT_COLUMNS.left, DEFAULT_COLUMNS.middle)
    storePair(ROW_STORAGE_KEY, 'top', 'bottom', DEFAULT_ROWS.top, DEFAULT_ROWS.bottom)
  }

  return { columns, rows, layoutStyle, draggingAxis, startDrag, resetLayout }
}
