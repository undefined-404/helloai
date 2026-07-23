<template>
  <canvas ref="canvasRef" class="starfield-canvas" aria-hidden="true" />
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'

// 深蓝星空背景（Canvas 2D）：圆形轨道运动 + 拖尾 + 闪烁，深蓝基调点缀 HelloAI 紫青
const props = withDefaults(defineProps<{
  // 星星密度（每 10000px² 的星星数），按面板尺寸自动换算总量
  density?: number
  // 背景基色（深蓝黑）
  bgColor?: string
}>(), {
  density: 1.4,
  bgColor: 'hsl(217, 64%, 6%)'
})

const canvasRef = ref<HTMLCanvasElement>()

let ctx: CanvasRenderingContext2D | null = null
let rafId = 0
let width = 0
let height = 0
let dpr = 1
let stars: Star[] = []
let resizeObserver: ResizeObserver | null = null

// 品牌点缀：约 15% 星星使用紫/青，其余为蓝白
const PALETTE = ['#CFE3FF', '#CFE3FF', '#CFE3FF', '#A78BFA', '#67E8F9']

interface Star {
  orbitRadius: number
  radius: number
  orbitX: number
  orbitY: number
  timePassed: number
  speed: number
  alpha: number
  color: string
  // 每颗星星的离屏纹理
  sprite: HTMLCanvasElement
}

// 用离屏 canvas 预渲染一颗星星的径向渐变纹理
function createStarSprite(radius: number, color: string): HTMLCanvasElement {
  const c = document.createElement('canvas')
  const size = Math.max(2, Math.ceil(radius * 6))
  c.width = size
  c.height = size
  const cctx = c.getContext('2d')!
  const half = size / 2
  const gradient = cctx.createRadialGradient(half, half, 0, half, half, half)
  gradient.addColorStop(0, color)
  gradient.addColorStop(0.35, color)
  gradient.addColorStop(1, 'transparent')
  cctx.fillStyle = gradient
  cctx.beginPath()
  cctx.arc(half, half, half, 0, Math.PI * 2)
  cctx.fill()
  return c
}

function buildStars() {
  const area = width * height
  const count = Math.round((area / 10000) * props.density)
  const maxStars = Math.min(Math.max(count, 120), 1500)

  stars = []
  for (let i = 0; i < maxStars; i++) {
    // 半径分布向小偏移（平方衰减），让小点星星占多数
    const radius = Math.pow(Math.random(), 2.2) * 1.1 + 0.3
    const color = PALETTE[Math.floor(Math.random() * PALETTE.length)]
    stars.push({
      orbitRadius: Math.random() * Math.max(width, height) * 0.5,
      radius,
      orbitX: width / 2,
      orbitY: height / 2,
      timePassed: Math.random() * Math.PI * 2,
      speed: (Math.random() * 0.6 + 0.2) * 0.0004,
      alpha: Math.random() * 0.6 + 0.4,
      color,
      sprite: createStarSprite(radius, color)
    })
  }
}

function drawStatic() {
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.fillStyle = props.bgColor
  ctx.fillRect(0, 0, width, height)
  ctx.globalCompositeOperation = 'lighter'
  for (const s of stars) {
    const x = Math.sin(s.timePassed) * s.orbitRadius + s.orbitX
    const y = Math.cos(s.timePassed) * s.orbitRadius + s.orbitY
    const sw = s.sprite.width
    ctx.globalAlpha = s.alpha
    ctx.drawImage(s.sprite, x - sw / 2, y - sw / 2)
  }
  ctx.globalAlpha = 1
  ctx.globalCompositeOperation = 'source-over'
}

function frame() {
  if (!ctx) return

  // 半透明背景叠加形成拖尾
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.globalAlpha = 0.6
  ctx.globalCompositeOperation = 'source-over'
  ctx.fillStyle = props.bgColor
  ctx.fillRect(0, 0, width, height)

  ctx.globalCompositeOperation = 'lighter'
  for (const s of stars) {
    s.timePassed += s.speed
    // 闪烁：更高频率、更大幅度，小星星忽明忽暗更明显
    if (Math.random() > 0.5) {
      s.alpha += (Math.random() - 0.5) * 0.25
      s.alpha = Math.min(1, Math.max(0.15, s.alpha))
    }
    const x = Math.sin(s.timePassed) * s.orbitRadius + s.orbitX
    const y = Math.cos(s.timePassed) * s.orbitRadius + s.orbitY
    const sw = s.sprite.width
    ctx.globalAlpha = s.alpha
    ctx.drawImage(s.sprite, x - sw / 2, y - sw / 2)
  }

  ctx.globalAlpha = 1
  ctx.globalCompositeOperation = 'source-over'
  rafId = requestAnimationFrame(frame)
}

function setup() {
  const canvas = canvasRef.value
  if (!canvas) return
  const parent = canvas.parentElement
  if (!parent) return

  dpr = Math.min(window.devicePixelRatio || 1, 2)
  width = parent.clientWidth
  height = parent.clientHeight
  if (width === 0 || height === 0) return

  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  canvas.style.width = width + 'px'
  canvas.style.height = height + 'px'

  ctx = canvas.getContext('2d')
  if (!ctx) return

  buildStars()

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  cancelAnimationFrame(rafId)
  if (reduceMotion) {
    drawStatic()
  } else {
    rafId = requestAnimationFrame(frame)
  }
}

onMounted(() => {
  setup()
  const parent = canvasRef.value?.parentElement
  if (parent && 'ResizeObserver' in window) {
    let debounce = 0
    resizeObserver = new ResizeObserver(() => {
      clearTimeout(debounce)
      debounce = window.setTimeout(setup, 150)
    })
    resizeObserver.observe(parent)
  }
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  resizeObserver?.disconnect()
  resizeObserver = null
  ctx = null
  stars = []
})
</script>

<style scoped>
.starfield-canvas {
  position: absolute;
  inset: 0;
  z-index: 0;
  display: block;
  pointer-events: none;
}
</style>
