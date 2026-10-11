<template>
  <!-- Markdown 渲染视图：把执行产出/对话内容渲染成带格式的富文本（类 DeepSeek/Kimi 聊天样式）。
       REF-4.3：解析内联引用 token [kb:{source}#{idx}]，渲染为可点开来源的引用卡片。 -->
  <div
    class="md-view"
    v-html="html"
    @click="onCiteClick"
  />
  <el-dialog
    v-model="citeOpen"
    :title="citeTitle"
    width="560px"
    append-to-body
  >
    <pre class="kb-cite-content">{{ citeContent }}</pre>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { kbApi } from '@/api/kb'

const props = defineProps<{ content: string | null | undefined }>()

// linkify：自动识别裸链接；breaks：单换行也换行（更贴近聊天观感）
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

/**
 * 引用溯源 token（REF-4.3）：`[kb:{source}#{chunkIdx}]`。
 * 渲染前先把 token 换成私有区占位符（避免被 markdown/DOMPurify 改写），
 * 渲染后再替换为可点开的引用链接，满足「人读正文、机器读 marker、点开看原文」。
 */
const KB_CITE = /\[kb:([^\[\]@+#]+)#(\d+)\]/g

const citeOpen = ref(false)
const citeTitle = ref('')
const citeContent = ref('')

const html = computed(() => {
  // 剥离 HTML 注释（模板脚手架/元信息）：正常 Markdown 中注释不可见，但 html:false 下 <!-- --> 会作为纯文本露出
  const raw = (props.content || '').replace(/<!--[\s\S]*?-->/g, '')
  if (!raw.trim()) return '<span class="md-empty">-</span>'

  // 1) 收集并屏蔽引用 token → ASCII 占位符（@@ 无 markdown 语义，DOMPurify 视作文本）
  const keys: string[] = []
  const shielded = raw.replace(KB_CITE, (_m, source, idx) => {
    keys.push(`${source}#${idx}`)
    return `@@KB_CITE_${keys.length - 1}@@`
  })

  // 2) 渲染 + 净化
  const rendered = DOMPurify.sanitize(md.render(shielded))

  // 3) 占位符还原为可点开引用链接（作者鉴权过的锚，不做第二次净化）
  return rendered.replace(/@@KB_CITE_(\d+)@@/g, (_m, n) => {
    const key = keys[Number(n)]
    return `<a class="kb-cite" data-kb-cite="${escapeAttr(key)}" title="知识库引用 ${escapeAttr(key)}">[kb:${escapeAttr(key)}]</a>`
  })
})

function escapeAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 点击引用卡片 → 取分片正文 → 弹层展示原文（懒加载，仅在点击时取）。 */
async function onCiteClick(e: MouseEvent): Promise<void> {
  const target = (e.target as HTMLElement | null)?.closest?.<HTMLElement>('a[data-kb-cite]')
  if (!target) return
  const key = target.getAttribute('data-kb-cite') || ''
  const hash = key.lastIndexOf('#')
  if (hash <= 0) return
  const source = key.slice(0, hash)
  const chunkIdx = Number(key.slice(hash + 1))
  if (Number.isNaN(chunkIdx)) return

  citeTitle.value = '知识库引用 ' + key
  citeContent.value = '加载中…'
  citeOpen.value = true
  try {
    const c = await kbApi.chunk(source, chunkIdx)
    citeContent.value = c.content
  } catch (err) {
    citeContent.value = '无法加载该分片：' + (err instanceof Error ? err.message : String(err))
  }
}
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

/* 引用溯源卡片（REF-4.3） */
.md-view :deep(.kb-cite) {
  display: inline;
  cursor: pointer;
  font-size: 12px;
  color: var(--ha-primary, #4c8bf5);
  background: var(--ha-surface, rgba(127,127,127,0.1));
  border-radius: 4px;
  padding: 0 4px;
  margin: 0 2px;
  white-space: nowrap;
}
.md-view :deep(.kb-cite:hover) { text-decoration: none; background: rgba(76,139,245,0.18); }

.kb-cite-content {
  margin: 0;
  padding: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  max-height: 60vh;
  overflow: auto;
}
</style>