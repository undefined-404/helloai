<template>
  <div class="sq-card">
    <div
      v-for="q in questions"
      :key="q.id"
      class="sq-question"
    >
      <div class="sq-question-text">
        {{ q.text }}
        <el-tag
          v-if="q.multiple"
          size="small"
          type="info"
          class="sq-multi-tag"
        >
          可多选
        </el-tag>
        <el-button
          v-if="!readonly && hasRecommended(q)"
          class="sq-rec-btn"
          size="small"
          type="warning"
          plain
          :disabled="disabled"
          @click="pickRecommended(q)"
        >
          推荐
        </el-button>
      </div>
      <div class="sq-options">
        <div
          v-for="opt in q.options"
          :key="opt.value"
          class="sq-option"
          :class="{ selected: isSelected(q, opt), disabled: disabled && !readonly, readonly }"
          @click="toggle(q, opt)"
        >
          <span class="sq-option-label">{{ opt.label }}</span>
          <el-tag
            v-if="opt.recommended"
            size="small"
            type="warning"
            effect="plain"
          >
            推荐
          </el-tag>
        </div>
      </div>
      <el-input
        v-if="!readonly && q.allowCustom !== false"
        v-model="customText[q.id]"
        size="small"
        :disabled="disabled"
        :placeholder="q.customPlaceholder || '其他情况可补充说明（选填）'"
        class="sq-custom"
      />
      <div
        v-else-if="readonly && roCustomOf(q.id)"
        class="sq-custom-ro"
      >
        补充：{{ roCustomOf(q.id) }}
      </div>
    </div>
    <div
      v-if="!readonly"
      class="sq-actions"
    >
      <el-button
        type="primary"
        size="small"
        :disabled="disabled || !canSubmit"
        :loading="loading"
        @click="submit"
      >
        提交选择
      </el-button>
      <span class="sq-tip">也可以直接在下方输入框自由回复</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ClarifyOption, ClarifyQuestion, ClarifySelection } from '@/types'

// V33 结构化选项式追问卡片：选项点选 + 自定义补充 → 提交时同时产出
// 可读文本（走 content，LLM 上下文可读）与选择快照（走 payload，前端回显）
// readonly=历史追问只读回显：selections 为当时的选择快照，不可点选、无提交按钮
const props = defineProps<{
  questions: ClarifyQuestion[]
  disabled?: boolean
  loading?: boolean
  readonly?: boolean
  selections?: ClarifySelection[] | null
}>()

const emit = defineEmits<{
  (e: 'submit', payload: { text: string; selections: ClarifySelection[] }): void
}>()

// questionId → 选中 value 列表（单选存 0~1 个元素）
const picked = reactive<Record<string, string[]>>({})
const customText = reactive<Record<string, string>>({})

// 只读模式的选择快照索引：questionId → selection
const roSelections = computed(() => {
  const map: Record<string, ClarifySelection> = {}
  for (const s of props.selections ?? []) map[s.questionId] = s
  return map
})

function isSelected(q: ClarifyQuestion, opt: ClarifyOption) {
  const values = props.readonly ? (roSelections.value[q.id]?.values ?? []) : (picked[q.id] ?? [])
  return values.includes(opt.value)
}

function roCustomOf(qid: string) {
  return (roSelections.value[qid]?.customText ?? '').trim()
}

function hasRecommended(q: ClarifyQuestion) {
  return q.options.some(o => o.recommended)
}

// 一键选中该题所有推荐项（单选取第一个推荐项）
function pickRecommended(q: ClarifyQuestion) {
  if (props.disabled || props.readonly) return
  const rec = q.options.filter(o => o.recommended).map(o => o.value)
  if (!rec.length) return
  picked[q.id] = q.multiple ? rec : [rec[0]]
}

function toggle(q: ClarifyQuestion, opt: ClarifyOption) {
  if (props.disabled || props.readonly) return
  const cur = picked[q.id] ?? []
  if (q.multiple) {
    picked[q.id] = cur.includes(opt.value) ? cur.filter(v => v !== opt.value) : [...cur, opt.value]
  } else {
    picked[q.id] = cur.includes(opt.value) ? [] : [opt.value]
  }
}

// 每题至少选一项或填了自定义补充才可提交
const canSubmit = computed(() =>
  props.questions.every(q => (picked[q.id] ?? []).length > 0 || (customText[q.id] ?? '').trim().length > 0))

function submit() {
  if (!canSubmit.value) return
  const selections: ClarifySelection[] = []
  const lines: string[] = []
  for (const q of props.questions) {
    const values = picked[q.id] ?? []
    const labels = values.map(v => q.options.find(o => o.value === v)?.label ?? v)
    const custom = (customText[q.id] ?? '').trim()
    selections.push({
      questionId: q.id,
      questionText: q.text,
      values,
      labels,
      custom: custom.length > 0,
      customText: custom || null
    })
    const parts = [...labels]
    if (custom) parts.push(`补充：${custom}`)
    lines.push(`${q.text}：${parts.join('、')}`)
  }
  emit('submit', { text: lines.join('\n'), selections })
}
</script>

<style scoped>
.sq-card {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface-elevated);
  padding: 12px 14px;
}

.sq-question { margin-bottom: 12px; }
.sq-question:last-of-type { margin-bottom: 8px; }

.sq-question-text {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.sq-multi-tag { flex-shrink: 0; }
.sq-rec-btn { flex-shrink: 0; margin-left: 2px; }

/* 选项纵向单列：每个选项独占一行（不论文本长短），避免横向挤行影响可读性 */
.sq-options { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }

.sq-option {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 12px;
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  transition: border-color 0.15s, background 0.15s;
}

.sq-option:hover:not(.disabled):not(.readonly) { border-color: var(--ha-primary, #409eff); }
.sq-option.selected {
  background: var(--ha-primary-muted);
  border-color: var(--ha-primary, #409eff);
}
.sq-option.disabled { cursor: not-allowed; opacity: 0.6; }
.sq-option.readonly { cursor: default; }

.sq-custom { margin-top: 2px; }
.sq-custom-ro { font-size: 12px; color: var(--ha-muted); }

.sq-actions { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.sq-tip { font-size: 12px; color: var(--ha-muted); }
</style>
