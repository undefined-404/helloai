<template>
  <div
    ref="containerRef"
    class="avatar-reveal"
    :class="{ 'is-revealed': isRevealed }"
    :style="{ width: size + 'px', height: size + 'px' }"
    tabindex="0"
    role="img"
    aria-label="HelloAI 虚拟人物，鼠标移动时显示机械骨架"
    @mouseenter="onReveal"
    @mouseleave="onHide"
    @mousemove="onMouseMove"
    @focus="onReveal"
    @blur="onHide"
    @touchstart.passive="onToggle"
  >
    <!-- 人物底图 -->
    <img
      :src="avatarBase"
      alt=""
      class="avatar-layer avatar-base"
      draggable="false"
    />

    <!-- 骨架揭示层 - 使用 CSS mask 跟随鼠标 -->
    <img
      :src="avatarSkeleton"
      alt=""
      class="avatar-layer avatar-skeleton"
      :style="skeletonMaskStyle"
      draggable="false"
    />

    <!-- 光标指示器 -->
    <div
      v-if="isRevealed"
      class="cursor-indicator"
      :style="cursorStyle"
      aria-hidden="true"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import avatarBase from '@/assets/avatar/helloai-avatar-base.png'
import avatarSkeleton from '@/assets/avatar/helloai-avatar-skeleton.png'

const props = withDefaults(defineProps<{
  size?: number
  primaryColor?: string
  isFocused?: boolean
  isTyping?: boolean
  focusTarget?: 'none' | 'email' | 'password'
}>(), {
  size: 200,
  primaryColor: '#7C3AED',
  isFocused: false,
  isTyping: false,
  focusTarget: 'none'
})

const containerRef = ref<HTMLDivElement>()
const isRevealed = ref(false)
const touchRevealed = ref(false)

// 鼠标位置（相对容器中心，-0.5 到 0.5）
const mouseX = ref(0)
const mouseY = ref(0)

// 遮罩半径（像素）
const maskRadius = 80

// 骨架层遮罩样式 - 使用 radial-gradient 作为 mask
const skeletonMaskStyle = computed(() => {
  if (!isRevealed.value) {
    return {
      maskImage: 'none',
      WebkitMaskImage: 'none',
      opacity: 0
    }
  }

  // 将相对坐标转换为像素坐标
  const x = (mouseX.value + 0.5) * props.size
  const y = (mouseY.value + 0.5) * props.size

  // 创建径向渐变遮罩，中心跟随鼠标
  const mask = `radial-gradient(circle ${maskRadius}px at ${x}px ${y}px, black 0%, black 60%, transparent 100%)`

  return {
    maskImage: mask,
    WebkitMaskImage: mask,
    opacity: 1
  }
})

// 光标指示器样式
const cursorStyle = computed(() => {
  const x = (mouseX.value + 0.5) * props.size
  const y = (mouseY.value + 0.5) * props.size

  return {
    left: `${x}px`,
    top: `${y}px`,
    width: `${maskRadius * 2}px`,
    height: `${maskRadius * 2}px`
  }
})

function onMouseMove(e: MouseEvent) {
  if (!containerRef.value) return

  const rect = containerRef.value.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 2

  // 计算相对位置（-0.5 到 0.5）
  mouseX.value = (e.clientX - centerX) / rect.width
  mouseY.value = (e.clientY - centerY) / rect.height
}

function onReveal() {
  isRevealed.value = true
}

function onHide() {
  if (!touchRevealed.value) {
    isRevealed.value = false
  }
}

function onToggle(e: TouchEvent) {
  touchRevealed.value = !touchRevealed.value
  isRevealed.value = touchRevealed.value

  if (touchRevealed.value && containerRef.value) {
    // 触摸时默认显示中心区域
    mouseX.value = 0
    mouseY.value = 0
  }
}
</script>

<style scoped>
.avatar-reveal {
  position: relative;
  overflow: hidden;
  border-radius: 50%;
  cursor: crosshair;
  outline: none;
  z-index: 20;
  flex-shrink: 0;
  transition: box-shadow 0.4s ease;
}

.avatar-reveal:focus-visible {
  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.6),
              0 0 0 6px v-bind(primaryColor);
}

.avatar-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center top;
  user-select: none;
  pointer-events: none;
}

.avatar-base {
  z-index: 1;
  transition: transform 0.3s ease;
}

.avatar-skeleton {
  z-index: 2;
  transition: opacity 0.2s ease;
  /* mask 由 :style 动态绑定 */
}

/* 光标指示器 - 显示遮罩区域轮廓 */
.cursor-indicator {
  position: absolute;
  z-index: 3;
  border: 1px solid rgba(6, 182, 212, 0.4);
  border-radius: 50%;
  transform: translate(-50%, -50%);
  pointer-events: none;
  box-shadow:
    0 0 20px rgba(6, 182, 212, 0.2),
    inset 0 0 20px rgba(124, 58, 237, 0.1);
  animation: cursor-pulse 2s ease-in-out infinite;
}

@keyframes cursor-pulse {
  0%, 100% {
    box-shadow:
      0 0 20px rgba(6, 182, 212, 0.2),
      inset 0 0 20px rgba(124, 58, 237, 0.1);
  }
  50% {
    box-shadow:
      0 0 30px rgba(6, 182, 212, 0.35),
      inset 0 0 30px rgba(124, 58, 237, 0.2);
  }
}

/* 减少动画偏好 */
@media (prefers-reduced-motion: reduce) {
  .avatar-skeleton,
  .avatar-base,
  .cursor-indicator {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
  }

  .cursor-indicator {
    animation: none;
  }
}
</style>
