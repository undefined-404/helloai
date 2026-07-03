<template>
  <div class="character-wrapper" ref="containerRef">
    <!-- Robot Character SVG -->
    <svg
      :width="size"
      :height="size"
      viewBox="0 0 240 280"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      class="robot-char"
      :class="{ idle: !isFocused, listening: isFocused, typing: isTyping }"
    >
      <!-- Antenna glow -->
      <circle cx="120" cy="24" r="6" :fill="antennaColor" class="antenna-glow" />

      <!-- Antenna line -->
      <line x1="120" y1="30" x2="120" y2="48" stroke="#C4A484" stroke-width="2" />

      <!-- Ears -->
      <rect x="28" y="100" width="18" height="40" rx="9" fill="#DDF5E3" class="ear-left" />
      <rect x="194" y="100" width="18" height="40" rx="9" fill="#DDF5E3" class="ear-right" />

      <!-- Head -->
      <rect
        x="44" y="48" width="152" height="140" rx="32"
        :fill="headColor"
        :stroke="headBorderColor"
        stroke-width="2"
        class="robot-head"
      />

      <!-- Face screen area -->
      <rect
        x="60" y="72" width="120" height="80" rx="16"
        fill="#FFF7F0"
        class="face-screen"
      />

      <!-- Left Eye -->
      <g class="eye-group left-eye">
        <ellipse cx="96" cy="112" rx="18" ry="20" fill="white" class="eye-white" />
        <ellipse
          cx="96" cy="112" rx="8" ry="10"
          :fill="eyeColor"
          class="pupil"
          :style="{ transform: `translate(${leftPupilX}px, ${leftPupilY}px)` }"
        />
        <ellipse cx="92" cy="107" rx="3" ry="3" fill="white" class="eye-shine" />
      </g>

      <!-- Right Eye -->
      <g class="eye-group right-eye">
        <ellipse cx="144" cy="112" rx="18" ry="20" fill="white" class="eye-white" />
        <ellipse
          cx="144" cy="112" rx="8" ry="10"
          :fill="eyeColor"
          class="pupil"
          :style="{ transform: `translate(${rightPupilX}px, ${rightPupilY}px)` }"
        />
        <ellipse cx="140" cy="107" rx="3" ry="3" fill="white" class="eye-shine" />
      </g>

      <!-- Blink overlay -->
      <rect
        x="60" y="72" width="120" height="80" rx="16"
        :fill="headColor"
        class="blink-overlay"
      />

      <!-- Mouth -->
      <path
        :d="mouthPath"
        stroke="#6B4423"
        stroke-width="2.5"
        stroke-linecap="round"
        fill="none"
        class="robot-mouth"
      />

      <!-- Cheek blush -->
      <ellipse cx="78" cy="134" rx="10" ry="5" fill="#F3D2C1" class="cheek left-cheek" />
      <ellipse cx="162" cy="134" rx="10" ry="5" fill="#F3D2C1" class="cheek right-cheek" />

      <!-- Body neck connector -->
      <!-- <rect x="100" y="188" width="40" height="16" rx="6" fill="#DDF5E3" /> -->
    </svg>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = withDefaults(defineProps<{
  size?: number
  primaryColor?: string
  isFocused?: boolean
  isTyping?: boolean
  focusTarget?: 'none' | 'email' | 'password'
}>(), {
  size: 200,
  primaryColor: '#FF9E64',
  isFocused: false,
  isTyping: false,
  focusTarget: 'none'
})

const containerRef = ref<HTMLDivElement>()
const mouseX = ref(0.5)
const mouseY = ref(0.5)
const isBlinking = ref(false)
const blinkTimer = ref<ReturnType<typeof setInterval>>()

// ---- Mouse Tracking ----
function handleMouseMove(e: MouseEvent) {
  if (!containerRef.value) return
  const rect = containerRef.value.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  mouseX.value = Math.max(0, Math.min(1, (e.clientX - cx) / rect.width + 0.5))
  mouseY.value = Math.max(0, Math.min(1, (e.clientY - cy) / rect.height + 0.5))
}

// ---- Blink Cycle ----
function startBlinkCycle() {
  blinkTimer.value = setInterval(() => {
    isBlinking.value = true
    setTimeout(() => { isBlinking.value = false }, 150)
  }, 2500 + Math.random() * 2000)
}

// ---- Pupil Positions ----
const pupilRange = 6

const leftPupilX = computed(() => {
  if (props.focusTarget === 'email') return -2
  if (props.focusTarget === 'password') return 1
  return (mouseX.value - 0.5) * 2 * pupilRange
})

const leftPupilY = computed(() => {
  if (props.focusTarget !== 'none') return 2
  return (mouseY.value - 0.5) * 2 * pupilRange
})

const rightPupilX = computed(() => {
  if (props.focusTarget === 'email') return -2
  if (props.focusTarget === 'password') return 1
  return (mouseX.value - 0.5) * 2 * pupilRange
})

const rightPupilY = computed(() => {
  if (props.focusTarget !== 'none') return 2
  return (mouseY.value - 0.5) * 2 * pupilRange
})

// ---- Mouth Animation ----
const mouthPath = computed(() => {
  if (props.isTyping) {
    return 'M 100 150 Q 120 165 140 150'
  }
  if (props.isFocused) {
    return 'M 104 152 Q 120 162 136 152'
  }
  return 'M 106 154 Q 120 160 134 154'
})

// ---- Colors ----
const headColor = computed(() => '#FFF7F0')
const headBorderColor = computed(() => '#F3D2C1')
const eyeColor = computed(() => props.primaryColor)
const antennaColor = computed(() => props.primaryColor)

onMounted(() => {
  window.addEventListener('mousemove', handleMouseMove)
  startBlinkCycle()
})

onUnmounted(() => {
  window.removeEventListener('mousemove', handleMouseMove)
  clearInterval(blinkTimer.value)
})
</script>

<style scoped>
.character-wrapper {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  position: relative;
  z-index: 20;
}

.robot-char {
  transition: transform 0.7s cubic-bezier(0.34, 1.56, 0.64, 1);
  filter: drop-shadow(0 8px 32px rgba(255, 158, 100, 0.12));
}

.robot-char.idle {
  animation: float 4s ease-in-out infinite;
}

.robot-char.listening {
  animation: lean-forward 0.6s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
}

.robot-char.typing {
  animation: bounce-subtle 0.3s ease-in-out;
}

/* ---- Antenna Glow ---- */
.antenna-glow {
  animation: pulse-glow 2s ease-in-out infinite;
}

@keyframes pulse-glow {
  0%, 100% { opacity: 0.6; r: 5; }
  50% { opacity: 1; r: 7; }
}

/* ---- Eye Shine ---- */
.eye-shine {
  transition: opacity 0.3s;
}

/* ---- Blink Overlay ---- */
.blink-overlay {
  opacity: 0;
  transition: opacity 0.1s;
  pointer-events: none;
}

.robot-char .blink-overlay {
  animation: none;
}

/* ---- Float Animation ---- */
@keyframes float {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}

/* ---- Lean Forward ---- */
@keyframes lean-forward {
  0% { transform: translateY(0) scale(1); }
  50% { transform: translateY(4px) scale(0.98); }
  100% { transform: translateY(2px) scale(0.99); }
}

/* ---- Subtle Bounce ---- */
@keyframes bounce-subtle {
  0% { transform: translateY(0); }
  30% { transform: translateY(-3px); }
  60% { transform: translateY(1px); }
  100% { transform: translateY(0); }
}

/* ---- Ear Wiggle ---- */
.robot-char.listening .ear-left {
  animation: ear-wiggle 1s ease-in-out infinite;
}
.robot-char.listening .ear-right {
  animation: ear-wiggle 1s ease-in-out infinite 0.15s;
}

@keyframes ear-wiggle {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-3deg); }
  75% { transform: rotate(3deg); }
}

/* ---- Eye Group ---- */
.eye-group {
  transition: transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}

/* ---- Pupil ---- */
.pupil {
  transition: transform 0.15s ease-out;
}

/* ---- Cheek Blush ---- */
.cheek {
  transition: transform 0.5s;
}
.robot-char.listening .cheek {
  transform: scale(1.03);
}

/* ---- Face Screen ---- */
.face-screen {
  transition: filter 0.4s;
}
.robot-char.listening .face-screen {
  filter: brightness(0.98);
}

/* ---- Robot Head ---- */
.robot-head {
  transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.robot-char.listening .robot-head {
  stroke-width: 2.5;
}
</style>