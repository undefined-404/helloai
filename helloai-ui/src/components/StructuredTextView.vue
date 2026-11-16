<template>
  <!-- 长文本结构化视图：把「一段话」还原成「标签 + 条目」，只切分不改写原文。
       已自带 markdown 块结构的文本直接交 MarkdownView（见 looksLikeMarkdown）。 -->
  <MarkdownView
    v-if="asMarkdown"
    :content="text"
    :compact="compact"
  />
  <div
    v-else
    class="stv"
  >
    <template
      v-for="(block, idx) in blocks"
      :key="idx"
    >
      <!-- 带标签段：标签做行内色块前缀，正文/条目跟随其后。
           不排成左右两栏——`动作 (只读, 按原样执行并保留原始 stdout)` 这类长标签
           放进窄栏会折成三四行，比不格式化还难读。 -->
      <div
        v-if="block.kind === 'labeled'"
        class="stv-item"
      >
        <div class="stv-line">
          <span class="stv-label">{{ block.label }}</span>
          <span
            v-if="block.text"
            class="stv-text"
            v-html="inline(block.text)"
          />
        </div>
        <div
          v-if="block.lead"
          class="stv-lead"
          v-html="inline(block.lead)"
        />
        <ol
          v-if="block.items"
          class="stv-list"
        >
          <li
            v-for="(it, i) in block.items"
            :key="i"
            v-html="inline(it)"
          />
        </ol>
      </div>
      <!-- 无标签条目清单（如执行约束逐条禁令） -->
      <ul
        v-else-if="block.kind === 'list'"
        class="stv-list stv-list--dot"
      >
        <li
          v-for="(it, i) in block.items || []"
          :key="i"
          v-html="inline(it)"
        />
      </ul>
      <p
        v-else
        class="stv-para"
        v-html="inline(block.text || '')"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { looksLikeMarkdown, parseStructuredText, renderInline } from '@/utils/structuredText'

const props = withDefaults(defineProps<{
  text: string | null | undefined
  /** 逐条禁令类文本（执行约束）：允许按 `；` 拆条目 */
  splitSemicolon?: boolean
  /** 紧凑排版（次要层级用） */
  compact?: boolean
}>(), { splitSemicolon: false, compact: false })

const asMarkdown = computed(() => looksLikeMarkdown(props.text))
const blocks = computed(() => parseStructuredText(props.text, { splitSemicolon: props.splitSemicolon }))

/** 行内渲染（`命令` → 代码片、**强调** → 粗体）；已在工具层完成转义 */
function inline(text: string): string {
  return renderInline(text)
}
</script>

<style scoped>
.stv {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
}
.stv-item { min-width: 0; }
/* 标签行：标签与正文同一行起始，长正文自然在标签后换行，不会撑出窄栏 */
.stv-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.stv-label {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--ha-primary-muted, rgba(124, 58, 237, 0.12));
  color: var(--ha-primary, #7C3AED);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  letter-spacing: 0.01em;
}
.stv-text {
  flex: 1 1 260px;
  min-width: 0;
  line-height: 1.75;
  word-break: break-word;
}
.stv-lead {
  margin-top: 3px;
  line-height: 1.75;
  color: var(--ha-ink-secondary, inherit);
  word-break: break-word;
}
.stv-para {
  margin: 0;
  line-height: 1.75;
  word-break: break-word;
}
/* 条目清单：缩进 + 序号色，让「逐条」这件事一眼可见 */
.stv-list {
  margin: 4px 0 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.stv-list > li {
  line-height: 1.7;
  word-break: break-word;
}
.stv-list > li::marker {
  color: var(--ha-primary, #7C3AED);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.stv-list--dot { list-style: disc outside; }

/* v-html 内容不参与 scoped 编译，行内代码与强调需穿透 */
.stv :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--ha-surface-hover, rgba(0, 0, 0, 0.05));
  border: 1px solid var(--ha-border-light, rgba(0, 0, 0, 0.06));
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  word-break: break-all;
}
.stv :deep(strong) {
  font-weight: 600;
  color: var(--ha-ink, inherit);
}
</style>
