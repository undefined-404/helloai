<template>
  <!-- Markdown 渲染视图：把执行产出/对话内容渲染成带格式的富文本（类 DeepSeek/Kimi 聊天样式） -->
  <div class="md-view" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const props = defineProps<{ content: string | null | undefined }>()

// linkify：自动识别裸链接；breaks：单换行也换行（更贴近聊天观感）
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const html = computed(() => {
  // 剥离 HTML 注释（模板脚手架/元信息）：正常 Markdown 中注释不可见，但 html:false 下 <!-- --> 会作为纯文本露出
  const raw = (props.content || '').replace(/<!--[\s\S]*?-->/g, '')
  if (!raw.trim()) return '<span class="md-empty">-</span>'
  return DOMPurify.sanitize(md.render(raw))
})
</script>

<style scoped>
.md-view {
  font-size: 14px;
  line-height: 1.7;
  color: var(--ha-text, inherit);
  word-break: break-word;
}
.md-view :deep(.md-empty) { color: var(--ha-muted); }

/* 标题 */
.md-view :deep(h1),
.md-view :deep(h2),
.md-view :deep(h3),
.md-view :deep(h4) { margin: 16px 0 8px; font-weight: 600; line-height: 1.3; }
.md-view :deep(h1) { font-size: 20px; }
.md-view :deep(h2) { font-size: 18px; }
.md-view :deep(h3) { font-size: 16px; }
.md-view :deep(h4) { font-size: 14px; }
.md-view :deep(h1:first-child),
.md-view :deep(h2:first-child),
.md-view :deep(h3:first-child),
.md-view :deep(p:first-child) { margin-top: 0; }

/* 段落 / 列表 */
.md-view :deep(p) { margin: 8px 0; }
.md-view :deep(ul),
.md-view :deep(ol) { margin: 8px 0; padding-left: 22px; }
.md-view :deep(li) { margin: 4px 0; }

/* 引用 */
.md-view :deep(blockquote) {
  margin: 10px 0;
  padding: 6px 14px;
  border-left: 3px solid var(--ha-primary, #4c8bf5);
  background: var(--ha-surface, rgba(127,127,127,0.08));
  color: var(--ha-muted, inherit);
}

/* 行内代码 / 代码块 */
.md-view :deep(code) {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--ha-surface, rgba(127,127,127,0.15));
}
.md-view :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: 8px;
  overflow-x: auto;
  background: var(--ha-code-bg, rgba(127,127,127,0.12));
  border: 1px solid var(--ha-border, rgba(127,127,127,0.18));
}
.md-view :deep(pre code) { padding: 0; background: transparent; }

/* 表格（对比表格核心展示） */
.md-view :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
  font-size: 13px;
}
.md-view :deep(th),
.md-view :deep(td) {
  border: 1px solid var(--ha-border, rgba(127,127,127,0.25));
  padding: 6px 10px;
  text-align: left;
}
.md-view :deep(th) { background: var(--ha-surface, rgba(127,127,127,0.12)); font-weight: 600; }

/* 分隔线 / 链接 */
.md-view :deep(hr) { border: none; border-top: 1px solid var(--ha-border, rgba(127,127,127,0.2)); margin: 16px 0; }
.md-view :deep(a) { color: var(--ha-primary, #4c8bf5); text-decoration: none; }
.md-view :deep(a:hover) { text-decoration: underline; }
</style>
