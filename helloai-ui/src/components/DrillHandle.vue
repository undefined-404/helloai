<template>
  <!-- 派生血缘手柄（ADR-008）：下钻到派生主任务 / 上钻回来源子任务 / 返回来源。
       三种方向共用一个壳，尺寸与状态色按设计规范统一（下钻·返回 24/圆角6，上钻 28/圆角8），
       差异只靠箭头方向表达，不新增色相——否则同屏会出现「下钻一个紫、上钻另一个紫」。 -->
  <el-tooltip
    :content="title"
    placement="top"
    :disabled="!title"
  >
    <!-- 包一层 span 作 tooltip 触发器：原生 button 一旦 disabled 就不再派发
         mouseenter，直接包按钮会让「顶层，不可再上钻」这句说明永远弹不出来。 -->
    <span class="dh-wrap">
      <button
        type="button"
        class="drill-handle"
        :class="[`is-${size}`, { 'is-pill': !!label }]"
        :disabled="disabled"
        :aria-label="title || ariaFallback"
        @click="onClick"
      >
        <svg
          class="dh-icon"
          viewBox="0 0 10 10"
          aria-hidden="true"
        >
          <path
            :d="iconPath"
            vector-effect="non-scaling-stroke"
          />
        </svg>
        <span
          v-if="label"
          class="dh-label"
        >{{ label }}</span>
      </button>
    </span>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 血缘手柄。**不用 Element Plus 的 ArrowUp / ArrowDown**：那套是带杆箭头（↑ ↓），
 * 设计规范要的是无杆折线（chevron），两者在 24px 方块里一眼能分出来——
 * 带杆箭头会把方块填满，看起来像「排序」而不是「进入下一层」。
 *
 * 三种方向的语义边界（不要混用）：
 * - `down` 进下一层：本节点已二次拆分出独立主任务，点进那个主任务；
 * - `up`   回上一层：本任务/本子任务本身是派生出来的，点回来源；
 * - `back` 同级回退：当前页是深链直入的，点回它所属的那条来源记录（带文案用）。
 */
const props = withDefaults(defineProps<{
  direction?: 'down' | 'up' | 'back'
  /** 24 = 行内次级（下钻 / 返回）；28 = 行级主操作（上钻）。 */
  size?: 24 | 28
  /** 有文案即渲染成药丸（宽度自适应 + 左右内边距），无文案即方形徽标。 */
  label?: string
  /** 无下级 / 顶层任务：置灰占位而不是隐藏，保证同列纵向对齐不跳动。 */
  disabled?: boolean
  /** tooltip 文案，同时作为 aria-label。留空则不挂 tooltip。 */
  title?: string
}>(), {
  direction: 'down',
  size: 24,
  label: '',
  disabled: false,
  title: '',
})

const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()

/** chevron 两段折线 / 返回用带杆左箭头：笔画端点圆头，24px 方块里不显毛刺。 */
const iconPath = computed(() => {
  if (props.direction === 'up') return 'M2 6.4 L5 3.4 L8 6.4'
  if (props.direction === 'back') return 'M8.2 5 L2.2 5 M4.7 2.4 L2.1 5 L4.7 7.6'
  return 'M2 3.6 L5 6.6 L8 3.6'
})

/** 图标方向与动作方向一致，读屏时补一句可理解的动作名。 */
const ariaFallback = computed(() => {
  if (props.direction === 'up') return '上钻到来源'
  if (props.direction === 'back') return '返回来源'
  return '下钻到下一层'
})

function onClick(ev: MouseEvent) {
  if (props.disabled) return
  emit('click', ev)
}
</script>

<style scoped>
/* 触发器外壳：inline-flex 撑住按钮尺寸，禁用态仍能被 hover 到（见模板注释）。 */
.dh-wrap {
  display: inline-flex;
  flex: none;
}

.drill-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  flex: none;
  padding: 0;
  border: 1px solid var(--ha-handle-border);
  background: var(--ha-handle-bg);
  color: var(--ha-primary);
  cursor: pointer;
  transition:
    background-color var(--ha-duration-fast) var(--ha-ease-out),
    border-color var(--ha-duration-fast) var(--ha-ease-out),
    color var(--ha-duration-fast) var(--ha-ease-out);
  -webkit-tap-highlight-color: transparent;
}

.drill-handle.is-24 {
  width: 24px;
  height: 24px;
  border-radius: 6px;
}

.drill-handle.is-28 {
  width: 28px;
  height: 28px;
  border-radius: 8px;
}

/* 药丸：宽度交给内容，左内边距比右少 0——图标自带视觉留白，两侧等距反而偏左。 */
.drill-handle.is-pill {
  width: auto;
  padding: 0 9px;
}

.drill-handle.is-pill.is-28 {
  padding: 0 11px;
}

.drill-handle:hover:not(:disabled) {
  background: var(--ha-handle-bg-hover);
  border-color: var(--ha-handle-border-hover);
  color: var(--ha-primary-hover);
}

/* 按下即「已确认点到了」：反转为实心主色，与悬停区分开（规范里这一态只出现在按住瞬间）。 */
.drill-handle:active:not(:disabled) {
  background: var(--ha-primary);
  border-color: var(--ha-primary);
  color: #fff;
}

.drill-handle:focus-visible {
  outline: 2px solid var(--ha-primary);
  outline-offset: 2px;
}

.drill-handle:disabled {
  background: var(--el-fill-color-light);
  border-color: transparent;
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}

/* 图标 14/16px：viewBox 取 10×6 的折线画布，放大后折线视觉宽约 8.4/9.6px——
   这正是 24px 方块里「看得出是箭头、又不显笨重」的档位。
   vector-effect 让描边不随缩放变粗，24/28 两档笔画粗细保持一致。 */
.dh-icon {
  width: 14px;
  height: 14px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.is-28 .dh-icon {
  width: 16px;
  height: 16px;
}

.dh-label {
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}
</style>
