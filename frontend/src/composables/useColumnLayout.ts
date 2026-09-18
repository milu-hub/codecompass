/**
 * 三栏可拖拽布局（仿 IDE）。
 *
 * 分工写死，避免"两套真相"：
 * - **CSS 变量只存比例**（--w-left/--w-middle/--w-right，和恒为 1），拖拽时写的是它们；
 * - **像素上下限只在拖拽裁决里用**（见 COLUMN_LIMITS），不写进 DOM，也不参与上次恢复，
 *   这样窗口缩放时不会出现"存的比例合法、渲染却违反最小值"的中间态。
 *
 * 三个最容易错的地方，都在这里一次性处理掉：
 * 1. 分隔条把宽度分成三份后还剩两条 6px 分隔条要占位，比例必须按「容器宽 - 2×分隔条」算，
 *    否则三栏加起来比容器宽，右边那栏会被挤出视口；
 * 2. 某一栏触到最小值后，继续拖要能"吃掉"第三栏（规格里的「允许另一栏继续扩大」），
 *    这是级联而不是标准双栏此消彼长，见 resolveDrag 的注释分支；
 * 3. 容器窄到三条最小值加起来都放不下时（900px 附近必然发生），最小值要按比例缩放，
 *    否则约束无解、布局会横向溢出。
 */
import { computed, reactive, ref, type Ref } from 'vue'

export type ColumnKey = 'left' | 'middle' | 'right'

/** 比例（百分比，和为 100）—— 落 localStorage 的格式 */
export interface ColumnWidths {
  left: number
  middle: number
  right: number
}

/** 像素宽三栏（和恒为 available）—— 只在拖拽裁决里流转 */
interface PixelWidths {
  left: number
  middle: number
  right: number
}

/** 分隔条宽度，必须与 CSS 里的 --cc-splitter 一致 */
export const SPLITTER_WIDTH = 6

export const STORAGE_KEY = 'codecompass.layout.columns'

export const DEFAULT_COLUMN_WIDTHS: ColumnWidths = { left: 22, middle: 44, right: 34 }

export const COLUMN_LIMITS: Record<ColumnKey, { min: number; max: number }> = {
  left: { min: 200, max: 400 },
  middle: { min: 400, max: 900 },
  right: { min: 320, max: 700 },
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

/** 容器太窄时把上下限按比例缩小，保证三条最小值一定放得下。 */
function effectiveLimits(available: number): Record<ColumnKey, { min: number; max: number }> {
  const minSum =
    COLUMN_LIMITS.left.min + COLUMN_LIMITS.middle.min + COLUMN_LIMITS.right.min
  if (available >= minSum || available <= 0) {
    return COLUMN_LIMITS
  }
  const scale = available / minSum
  return {
    left: { min: COLUMN_LIMITS.left.min * scale, max: COLUMN_LIMITS.left.max * scale },
    middle: { min: COLUMN_LIMITS.middle.min * scale, max: COLUMN_LIMITS.middle.max * scale },
    right: { min: COLUMN_LIMITS.right.min * scale, max: COLUMN_LIMITS.right.max * scale },
  }
}

export function readStoredColumnWidths(): ColumnWidths {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return { ...DEFAULT_COLUMN_WIDTHS }
    }
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') {
      return { ...DEFAULT_COLUMN_WIDTHS }
    }
    const record = parsed as Record<string, unknown>
    const left = record.left
    const middle = record.middle
    const right = record.right
    if (
      typeof left !== 'number' ||
      typeof middle !== 'number' ||
      typeof right !== 'number' ||
      ![left, middle, right].every((value) => Number.isFinite(value) && value > 0)
    ) {
      return { ...DEFAULT_COLUMN_WIDTHS }
    }
    const total = left + middle + right
    if (Math.abs(total - 100) > 5) {
      // 明显不是本应用写入的数据，宁可用默认值
      return { ...DEFAULT_COLUMN_WIDTHS }
    }
    return { left, middle, right }
  } catch {
    // 隐私模式 / 脏数据
    return { ...DEFAULT_COLUMN_WIDTHS }
  }
}

export function storeColumnWidths(widths: ColumnWidths): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(widths))
  } catch {
    // 写不进去不影响本次会话内拖拽效果
  }
}

const EPSILON = 1e-6

const COLUMN_KEYS: ColumnKey[] = ['left', 'middle', 'right']

/**
 * 硬约束：任何一栏不得低于自己的最小宽度。
 *
 * 最小值之和 ≤ available 由 effectiveLimits 保证，所以一定有解。
 * 让位顺序由 caller 给出（离被拖分隔条最远的栏排最前）：这就是规格里
 * 「一栏到最小值后，另一栏还能继续扩大」的级联——差额全部由最远那栏承担。
 * 一开始写成「按富余比例分摊」，实测右栏明明该长到上限 700，却被中栏按比例分走一截。
 */
function enforceMinimums(
  widths: PixelWidths,
  mins: PixelWidths,
  donors: ColumnKey[],
): PixelWidths {
  const out = { ...widths }
  const receivers = COLUMN_KEYS.filter((key) => mins[key] - out[key] > EPSILON)
  if (receivers.length === 0) {
    return out
  }
  let deficit = receivers.reduce((sum, key) => sum + (mins[key] - out[key]), 0)
  const initialDeficit = deficit
  for (const key of donors) {
    if (deficit <= EPSILON) {
      break
    }
    if (receivers.includes(key)) {
      continue
    }
    const slack = out[key] - mins[key]
    if (slack <= EPSILON) {
      continue
    }
    const take = Math.min(deficit, slack)
    out[key] -= take
    deficit -= take
  }
  // 让出来的量按缺口比例补到不足的栏上（通常只有一个 receiver，即全额补上）
  const covered = initialDeficit - deficit
  if (covered > EPSILON) {
    for (const key of receivers) {
      const need = mins[key] - out[key]
      out[key] += covered * (need / initialDeficit)
    }
  }
  return out
}

/**
 * 软约束：超出上限的部分转给相邻栏（若它有上限余量），转不出去就保留。
 *
 * 为什么上限必须是软的：三条上限之和（400+900+700=2000）通常大于可用宽度，
 * 上限本身可能无解。这时唯一正确的取舍是**保住最小值**——曾经把上限当硬约束，
 * 结果拖到最左时中栏被压成 100px（低于 400 最小值），实测 1160 个用例里违反了 264 个。
 */
function relaxMaximums(widths: PixelWidths, maxes: PixelWidths, boundary: 0 | 1): PixelWidths {
  const out = { ...widths }
  const dragged: ColumnKey = boundary === 0 ? 'left' : 'right'
  const order: ColumnKey[] =
    boundary === 0 ? ['middle', 'left', 'right'] : ['middle', 'right', 'left']
  for (const key of order) {
    if (key === dragged) {
      // 被拖的栏在第一步已经严格夹在自己的上下限内
      continue
    }
    let excess = out[key] - maxes[key]
    if (excess <= EPSILON) {
      continue
    }
    const targets: ColumnKey[] =
      key === 'middle' ? (boundary === 0 ? ['right'] : ['left']) : ['middle']
    for (const target of targets) {
      if (target === dragged) {
        continue
      }
      const room = maxes[target] - out[target]
      if (room <= EPSILON) {
        continue
      }
      const move = Math.min(excess, room)
      out[key] -= move
      out[target] += move
      excess -= move
      if (excess <= EPSILON) {
        break
      }
    }
  }
  return out
}

/**
 * 拖拽裁决：给定被拖拽的分隔条、指针换算出的目标边界位置，返回合法的三栏像素宽。
 * 纯函数，便于直接跑断言。
 *
 * @param boundary 0 = 左/中分隔条，1 = 中/右分隔条
 * @param targetPx 目标边界位置（从主区左边缘算起的像素）
 */
export function resolveDrag(options: {
  boundary: 0 | 1
  targetPx: number
  available: number
  current: PixelWidths
}): PixelWidths {
  const { boundary, targetPx, available, current } = options
  const limits = effectiveLimits(available)
  const mins: PixelWidths = {
    left: limits.left.min,
    middle: limits.middle.min,
    right: limits.right.min,
  }
  const maxes: PixelWidths = {
    left: limits.left.max,
    middle: limits.middle.max,
    right: limits.right.max,
  }

  let desired: PixelWidths
  if (boundary === 0) {
    // 拖左/中分隔条：指针直接定左栏宽度，右边界（left+middle）先保持不动
    const spanEnd = current.left + current.middle
    const left = clamp(targetPx, mins.left, maxes.left)
    desired = { left, middle: spanEnd - left, right: available - spanEnd }
  } else {
    // 拖中/右分隔条：指针定右边界；右栏自身先夹在上下限内
    // （注意：这里只夹右栏，中栏不足的部分交给 enforceMinimums 向左级联去借，
    //   这就是规格里「一栏到最小值后，另一栏还能继续扩大」的实现方式）
    const edge = clamp(targetPx, available - maxes.right, available - mins.right)
    desired = { left: current.left, middle: edge - current.left, right: available - edge }
  }

  // 让位顺序：离被拖分隔条最远的栏排最前（拖左/中分隔条时右栏先让，拖中/右时分左栏先让）
  const donors: ColumnKey[] = boundary === 0 ? ['right', 'middle', 'left'] : ['left', 'middle', 'right']
  const floored = enforceMinimums(desired, mins, donors)
  const relaxed = relaxMaximums(floored, maxes, boundary)

  const left = clamp(relaxed.left, 0, available)
  const middle = clamp(relaxed.middle, 0, available - left)
  const right = Math.max(0, available - left - middle)
  return { left, middle, right }
}

function toPercentages(resolved: PixelWidths): ColumnWidths {
  const total = resolved.left + resolved.middle + resolved.right
  if (total <= 0) {
    return { ...DEFAULT_COLUMN_WIDTHS }
  }
  const left = Math.round((resolved.left / total) * 100)
  const middle = Math.round((resolved.middle / total) * 100)
  // 右栏补齐到 100：否则四舍五入后三栏加起来可能是 99 或 101，
  // 而 CSS 里三条轨道都按「和 = 1」算，和不为 1 就会出现一条缝或顶出边界
  const right = 100 - left - middle
  return { left, middle, right }
}

/**
 * 三栏布局组合式：状态 + 落库 + 原生指针拖拽（不引入任何第三方库）。
 *
 * @param containerRef 三栏网格容器（.result-layout）
 */
export function useColumnLayout(containerRef: Ref<HTMLElement | null>) {
  const columns = reactive<ColumnWidths>(readStoredColumnWidths())

  const containerStyle = computed(() => ({
    '--w-left': String(columns.left / 100),
    '--w-middle': String(columns.middle / 100),
    '--w-right': String(columns.right / 100),
  }))

  /** 正在拖的分隔条序号（用于 hover/拖拽态的样式） */
  const draggingBoundary = ref<0 | 1 | null>(null)

  function readPixelWidths(): PixelWidths {
    const container = containerRef.value
    if (!container) {
      return { left: 0, middle: 0, right: 0 }
    }
    const width = (pane: string) =>
      container.querySelector<HTMLElement>(`[data-pane="${pane}"]`)?.getBoundingClientRect()
        .width ?? 0
    return { left: width('left'), middle: width('middle'), right: width('right') }
  }

  function applyPixels(resolved: PixelWidths): void {
    const container = containerRef.value
    if (!container) {
      return
    }
    const total = resolved.left + resolved.middle + resolved.right
    if (total <= 0) {
      return
    }
    // 拖拽期间直接写 CSS 变量：不经过响应式状态，组件完全不重渲染，
    // 也就碰不到 Mermaid（图只在 mermaid-text 变化时重画）
    container.style.setProperty('--w-left', String(resolved.left / total))
    container.style.setProperty('--w-middle', String(resolved.middle / total))
    container.style.setProperty('--w-right', String(resolved.right / total))
  }

  function startDrag(boundary: 0 | 1, event: PointerEvent): void {
    const container = containerRef.value
    if (!container || event.button !== 0) {
      return
    }
    const splitter = event.currentTarget as HTMLElement | null
    if (!splitter) {
      return
    }
    const available = container.getBoundingClientRect().width - SPLITTER_WIDTH * 2
    if (available <= 0) {
      return
    }
    const startWidths = readPixelWidths()
    const startX = event.clientX
    const startBoundary =
      boundary === 0 ? startWidths.left : startWidths.left + startWidths.middle

    draggingBoundary.value = boundary
    // 捕获可能因浏览器差异失败（比如合成的指针事件），失败也不能让拖拽不可用
    try {
      splitter.setPointerCapture(event.pointerId)
    } catch {
      // 忽略：下面挂在 window 上的监听不依赖捕获
    }
    splitter.classList.add('is-dragging')
    document.body.classList.add('cc-resizing')

    let frame = 0
    let pendingX = startX
    let lastResolved: PixelWidths | null = null
    let finished = false

    const render = () => {
      frame = 0
      const targetPx = startBoundary + (pendingX - startX)
      const resolved = resolveDrag({
        boundary,
        targetPx,
        available,
        current: startWidths,
      })
      lastResolved = resolved
      applyPixels(resolved)
    }

    const onMove = (moveEvent: PointerEvent) => {
      // 兜底：按键已经松开却没收到 pointerup（松在窗口外、或指针捕获被浏览器释放）
      // 这种情况下事件照样会到 window，所以用 buttons 判定收尾，避免拖拽卡住
      if (moveEvent.buttons === 0) {
        finish()
        return
      }
      pendingX = moveEvent.clientX
      // requestAnimationFrame 节流到 ~16ms：指针事件比帧率密，直接改会白跑很多次
      if (!frame) {
        frame = requestAnimationFrame(render)
      }
    }

    /**
     * 结束拖拽。
     *
     * 监听挂在 window 而不是分隔条本身：只挂元素上时，一旦指针捕获丢失
     * 就既收不到 pointermove 也收不到 pointerup —— 实测表现是"拖不动，而且 body 的
     * cc-resizing 永远摘不掉"。
     *
     * 另外**不能**把 lostpointercapture 当成结束：实测 headless/合成指针下，setPointerCapture
     * 会在第一个 move 之前就被释放，若在那里收尾，等于自己把 window 监听摘掉、拖拽立刻失效。
     * 捕获只当加速器，丢了就继续用 window 事件走完。
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
      document.body.classList.remove('cc-resizing')
      draggingBoundary.value = null
      if (lastResolved) {
        // 松手才落库：拖拽过程写 localStorage 既无意义也拖慢帧率。
        // 注意分两套：状态里放**精确比例**（这样松手时 DOM 不会退回取整值、不会跳一下），
        // localStorage 里放规格要求的整数百分比（下次打开按比例还原，允许 ≤0.5% 的取整偏差）。
        const total = lastResolved.left + lastResolved.middle + lastResolved.right
        if (total > 0) {
          columns.left = (lastResolved.left / total) * 100
          columns.middle = (lastResolved.middle / total) * 100
          columns.right = (lastResolved.right / total) * 100
        }
        storeColumnWidths(toPercentages(lastResolved))
      }
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', finish)
    window.addEventListener('pointercancel', finish)
    window.addEventListener('blur', finish)
  }

  /** 恢复默认比例（右下角小图标触发）。 */
  function resetColumns(): void {
    columns.left = DEFAULT_COLUMN_WIDTHS.left
    columns.middle = DEFAULT_COLUMN_WIDTHS.middle
    columns.right = DEFAULT_COLUMN_WIDTHS.right
    storeColumnWidths({ ...DEFAULT_COLUMN_WIDTHS })
  }

  return { columns, containerStyle, draggingBoundary, startDrag, resetColumns }
}
