<template>
  <!-- MA-1 执行结果分片视图：把 A2A `submitResult.parts`（带类型分片）按原序渲染出来。
       结构由后端契约给出（保留原序），这里只做「按类型选渲染器」，不再猜结构。
       text → StructuredTextView；data → 折叠 JSON；file → 附件卡占位（第二批物化）。 -->
  <div class="plv">
    <div
      v-for="(part, idx) in parts || []"
      :key="idx"
      class="plv-part"
    >
      <StructuredTextView
        v-if="part.type === 'text' && part.text"
        :text="part.text"
        class="plv-text"
      />
      <div
        v-else-if="part.type === 'data'"
        class="plv-data"
      >
        <div class="plv-data-head">
          <span class="plv-data-tag">JSON 数据</span>
          <span
            class="plv-data-toggle"
            @click="toggle(idx)"
          >{{ isExpanded(idx) ? '收起' : '展开' }}</span>
          <span
            class="plv-data-copy"
            @click="copyData(idx)"
          >{{ copyState[idx] || '复制' }}</span>
        </div>
        <pre
          v-if="isExpanded(idx)"
          class="plv-data-pre"
        >{{ dataText(part.data) }}</pre>
      </div>
      <div
        v-else-if="part.type === 'file'"
        class="plv-file"
      >
        <el-icon class="plv-file-icon"><Document /></el-icon>
        <span>{{ part.fileName || '附件' }}</span>
        <span
          v-if="part.mimeType"
          class="plv-file-mime"
        >{{ part.mimeType }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Document } from '@element-plus/icons-vue'
import StructuredTextView from '@/components/StructuredTextView.vue'
import type { ExecutionResultPart } from '@/types'

const props = defineProps<{
  parts: ExecutionResultPart[] | null | undefined
}>()

// 窄屏（<480px）默认折叠：data 分片正文可能极大，首屏不该撑爆详情区
const defaultCollapsed = () => typeof window !== 'undefined' && window.innerWidth < 480
const expanded = reactive<Record<number, boolean>>({})
const copyState = ref<Record<number, string>>({})

function isExpanded(idx: number): boolean {
  return expanded[idx] ?? !defaultCollapsed()
}

function toggle(idx: number): void {
  expanded[idx] = !(expanded[idx] ?? !defaultCollapsed())
}

/** data 分片正文只作文本节点输出（禁 v-html 防注入）；非法 JSON 降级为原文纯文本。 */
function dataText(raw: string | undefined | null): string {
  const text = raw ?? ''
  try {
    const parsed = JSON.parse(text)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return text
  }
}

async function copyData(idx: number): Promise<void> {
  const p = props.parts?.[idx]
  const text = p?.data ?? ''
  try {
    await navigator.clipboard.writeText(text)
    copyState.value[idx] = '已复制'
  } catch {
    copyState.value[idx] = '复制失败'
  }
  window.setTimeout(() => {
    copyState.value[idx] = ''
  }, 3000)
}
</script>

<style scoped>
.plv {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.plv-part { min-width: 0; }
.plv-text { min-width: 0; }

.plv-data {
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.2));
  border-radius: 8px;
  overflow: hidden;
  background: var(--ha-surface-hover, rgba(0, 0, 0, 0.05));
}
.plv-data-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--ha-border, rgba(127, 127, 127, 0.16));
  background: var(--ha-surface, rgba(127, 127, 127, 0.08));
}
.plv-data-tag {
  font-size: 12px;
  font-weight: 600;
  color: var(--ha-primary, #7C3AED);
}
.plv-data-toggle,
.plv-data-copy {
  font-size: 12px;
  color: var(--ha-muted, #8A93A5);
  cursor: pointer;
  user-select: none;
}
.plv-data-toggle:hover,
.plv-data-copy:hover {
  color: var(--ha-ink, inherit);
}
.plv-data-pre {
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
  background: transparent;
  font-family: var(--ha-font-mono, ui-monospace, Menlo, Consolas, monospace);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre;
  color: var(--ha-ink, inherit);
}

.plv-file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px dashed var(--ha-border, rgba(127, 127, 127, 0.24));
  border-radius: 8px;
  font-size: 13px;
  color: var(--ha-ink-secondary, inherit);
}
.plv-file-icon {
  color: var(--ha-muted, #8A93A5);
}
.plv-file-mime {
  font-size: 12px;
  color: var(--ha-muted, #8A93A5);
}
</style>