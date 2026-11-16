import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 拖拽阈值（px）：小于该位移视为轻点，超过才进入拖动。 */
export const DRAG_THRESHOLD = 4
/** 拖回停靠坑位的命中容差（px）：坑位外扩一圈再判定，免得必须落在像素内。 */
export const DOCK_PAD = 10

export interface PointerDragOptions {
  /** 当前是否允许拖动（如面板展开时不允许整拖）；返回 false 时位移被忽略。 */
  canDrag?: () => boolean
  /** 按下：调用方记录拖动起点（锚点坐标由调用方自行保存）。 */
  onPress?: (e: PointerEvent) => void
  /** 越过阈值后的每次移动；dx/dy 为相对按下点的位移。 */
  onDrag: (dx: number, dy: number) => void
  /** 松手：dragged=false 表示轻点（此时 e 即点击位置）。 */
  onRelease?: (e: PointerEvent, dragged: boolean) => void
}

/**
 * 悬浮件（额度胶囊 / 站内消息铃铛）共用的指针拖拽壳：
 * 统一阈值判定、window 级 pointermove/pointerup 监听、拖动后吞掉补发的 click。
 * 坐标换算与形态切换留在各自组件（两者锚点语义不同，不硬凑）。
 */
export function usePointerDrag(options: PointerDragOptions) {
  const dragging = ref(false)
  let pressed = false
  let suppressClick = false
  let startX = 0
  let startY = 0

  function onPointerDown(e: PointerEvent) {
    if (e.button !== 0) return
    pressed = true
    dragging.value = false
    suppressClick = false
    startX = e.clientX
    startY = e.clientY
    options.onPress?.(e)
  }

  function onPointerMove(e: PointerEvent) {
    if (!pressed) return
    if (options.canDrag && !options.canDrag()) return
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    if (!dragging.value) {
      if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) return
      dragging.value = true
    }
    options.onDrag(dx, dy)
  }

  function onPointerUp(e: PointerEvent) {
    if (!pressed) return
    pressed = false
    const dragged = dragging.value
    dragging.value = false
    if (dragged) suppressClick = true
    options.onRelease?.(e, dragged)
  }

  /** 拖动结束后浏览器可能补发一次 click：按钮的 @click 里先调它吞掉这一次。 */
  function consumeClick(): boolean {
    if (!suppressClick) return false
    suppressClick = false
    return true
  }

  onMounted(() => {
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
  })

  return { dragging, onPointerDown, consumeClick }
}

/** 松手点是否落在停靠坑位（外扩 DOCK_PAD）内。 */
export function isOverDock(selector: string, x: number, y: number): boolean {
  const dock = document.querySelector<HTMLElement>(selector)
  if (!dock) return false
  const r = dock.getBoundingClientRect()
  return x >= r.left - DOCK_PAD && x <= r.right + DOCK_PAD &&
    y >= r.top - DOCK_PAD && y <= r.bottom + DOCK_PAD
}
