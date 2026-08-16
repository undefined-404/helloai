<template>
  <!-- 核验分析：把评审 Agent 返回的 JSON 结构化成非开发者能看懂的卡片 -->
  <div
    v-if="verdict"
    class="verdict"
  >
    <div class="verdict-summary">
      <el-tag
        :type="verdict.pass ? 'success' : 'danger'"
        size="small"
      >
        {{ verdict.pass ? '核验通过' : '核验未通过' }}
      </el-tag>
      <el-tag
        v-if="scoreText"
        type="warning"
        size="small"
        effect="plain"
      >
        评分 {{ scoreText }}
      </el-tag>
    </div>

    <div
      v-if="commentText"
      class="verdict-field"
    >
      <div class="verdict-label">
        总体点评
      </div>
      <div class="verdict-value">
        {{ commentText }}
      </div>
    </div>

    <div
      v-if="issuesText"
      class="verdict-field"
    >
      <div class="verdict-label">
        发现的问题
      </div>
      <div class="verdict-value verdict-issues">
        {{ issuesText }}
      </div>
    </div>

    <div
      v-if="analysisText"
      class="verdict-field"
    >
      <div class="verdict-label">
        详细分析
      </div>
      <MarkdownView :content="analysisText" />
    </div>
  </div>

  <!-- 解析失败（非评审 JSON）时降级为普通 Markdown 渲染 -->
  <MarkdownView
    v-else
    :content="content"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownView from './MarkdownView.vue'

const props = defineProps<{ content: string | null | undefined }>()

interface Verdict {
  pass?: boolean
  score?: number | string | null
  issues?: unknown
  comment?: unknown
  analysis?: unknown
}

// 去掉可能包裹的 ```json ``` 代码围栏后再解析
function stripFences(s: string): string {
  const t = s.trim()
  const m = t.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/)
  return m ? m[1].trim() : t
}

const verdict = computed<Verdict | null>(() => {
  const raw = stripFences(props.content || '')
  if (!raw.startsWith('{')) return null
  try {
    const obj = JSON.parse(raw)
    if (typeof obj !== 'object' || obj === null) return null
    // 至少命中一个评审字段，避免把普通 JSON 误判为评审结论
    if (!('pass' in obj || 'score' in obj || 'comment' in obj || 'analysis' in obj)) return null
    return obj as Verdict
  } catch {
    return null
  }
})

// 数组/对象统一转成可读文本
function toText(v: unknown): string {
  if (v == null) return ''
  if (Array.isArray(v)) return v.map((x) => String(x)).join('\n')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

const scoreText = computed(() => (verdict.value?.score != null && verdict.value?.score !== '' ? String(verdict.value.score) : ''))
const commentText = computed(() => toText(verdict.value?.comment))
const issuesText = computed(() => toText(verdict.value?.issues))
const analysisText = computed(() => toText(verdict.value?.analysis))
</script>

<style scoped>
.verdict { display: flex; flex-direction: column; gap: 12px; }
.verdict-summary { display: flex; align-items: center; gap: 8px; }
.verdict-field { display: flex; flex-direction: column; gap: 4px; }
.verdict-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ha-muted, #909399);
}
.verdict-value {
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.verdict-issues { color: var(--ha-danger, #f56c6c); }
</style>
