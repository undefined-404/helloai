<template>
  <!-- Markdown 渲染视图：把执行产出/对话内容渲染成带格式的富文本（类 DeepSeek/Kimi 排版）。
       REF-4.3：解析内联引用 token [kb:{source}#{idx}]，渲染为可点开来源的引用卡片。 -->
  <div
    class="md-view"
    v-html="html"
    @click="onViewClick"
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
 * 代码块：语言标签 + 复制按钮（对齐 DeepSeek / Kimi / assistant-ui 的 AI 聊天形态）。
 *
 * <p><b>为什么用 renderer 规则而不是渲染后正则改 DOM</b>：正则改 `v-html` 产物要处理
 * 嵌套、转义与幂等，极易改坏代码内容本身；markdown-it 的 fence 规则拿到的就是
 * `token.content` 原文，`escapeHtml` 后原样输出，语义上零风险。</p>
 */
md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]
  const lang = (token.info || '').trim().split(/\s+/)[0] || 'text'
  const safeLang = md.utils.escapeHtml(lang)
  return [
    '<div class="md-code">',
    '<div class="md-code-head">',
    `<span class="md-code-lang">${safeLang}</span>`,
    '<button type="button" class="md-code-copy" data-md-copy>复制</button>',
    '</div>',
    `<pre class="md-code-pre"><code class="language-${safeLang}">${md.utils.escapeHtml(token.content)}</code></pre>`,
    '</div>'
  ].join('')
}

/**
 * 表格：外包一层横向滚动容器。
 *
 * <p>窄屏（对话右栏常只有 500px）里宽表格必须能滚，否则整条消息被撑破；
 * 而 `table { display:block; overflow-x:auto }` 会让 `<th>` 宽度分配失效
 * （表头与数据列错位），故用 wrapper 承担滚动，表格自身保持 `display:table`。</p>
 */
md.renderer.rules.table_open = () => '<div class="md-table-wrap"><table>'
md.renderer.rules.table_close = () => '</table></div>'

/**
 * 引用溯源 token（REF-4.3）：`[kb:{source}#{chunkIdx}]`。
 * 渲染前先把 token 换成私有区占位符（避免被 markdown/DOMPurify 改写），
 * 渲染后再替换为可点开的引用链接，满足「人读正文、机器读 marker、点开看原文」。
 */
const KB_CITE = /\[kb:([^[\]@+#]+)#(\d+)\]/g

const citeOpen = ref(false)
const citeTitle = ref('')
const citeContent = ref('')

const html = computed(() => {
  // 剥离 HTML 注释（模板脚手架/元信息）：正常 Markdown 中注释不可见，但 html:false 下 <!-- --> 会作为纯文本露出
  const raw = normalizeHeadings((props.content || '').replace(/<!--[\s\S]*?-->/g, ''))
  if (!raw.trim()) return '<span class="md-empty">-</span>'

  // 1) 收集并屏蔽引用 token → ASCII 占位符（@@ 无 markdown 语义，DOMPurify 视作文本）
  const keys: string[] = []
  const shielded = raw.replace(KB_CITE, (_m, source, idx) => {
    keys.push(`${source}#${idx}`)
    return `@@KB_CITE_${keys.length - 1}@@`
  })

  // 2) 渲染 + 净化
  const rendered = DOMPurify.sanitize(md.render(shielded), {
    // 代码块头部的复制按钮与滚动容器是本组件自己产出的结构，需保留（DOMPurify 默认放行 button/div，此处显式声明意图）
    ADD_ATTR: ['data-md-copy']
  })

  // 3) 占位符还原为可点开引用链接（作者鉴权过的锚，不做第二次净化）
  return rendered.replace(/@@KB_CITE_(\d+)@@/g, (_m, n) => {
    const key = keys[Number(n)]
    return `<a class="kb-cite" data-kb-cite="${escapeAttr(key)}" title="知识库引用 ${escapeAttr(key)}">[kb:${escapeAttr(key)}]</a>`
  })
})

function escapeAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 标题规范化（2026-10-18）：把 `##标题` / `###1. xxx` 这类**缺空格**的 ATX 标题修正为合法 Markdown。
 *
 * <p><b>为什么必须做</b>：CommonMark 规定 ATX 标题的 `#` 系列后<b>必须</b>跟一个空格或行尾，
 * 否则整行按普通文本输出。而中文大模型输出极常见 `##先说结论`、`###1. 项目名` 这种写法
 * ⇒ 用户看到裸露的 `##`，以为渲染坏了（实测库中 8 条 assistant 消息有此问题）。</p>
 *
 * <p><b>两类修正</b>：
 * <ol>
 *   <li>行首（允许前导空白）`#{1,6}` 紧跟非空白 ⇒ 补空格；</li>
 *   <li>中文/中文标点后<b>直接接</b> `#{1,6}` ⇒ 先断行再补空格
 *       （LLM 常在句末直接续写下一个标题，如 `##几个项目###1. xxx`）。</li>
 * </ol>
 * <b>跳过 fenced code block</b>（``` 或 ~~~）内的内容——那里 `#` 是代码/注释，不能改。</p>
 */
function normalizeHeadings(src: string): string {
  const lines = src.split('\n')
  let inFence = false
  return lines
    .map((line) => {
      if (/^\s*(```|~~~)/.test(line)) {
        inFence = !inFence
        return line
      }
      if (inFence) return line
      return line
        // ① 行首标题缺空格：`##标题` → `## 标题`（`#{1,6}` 后紧跟非空白、非 #）
        .replace(/^(\s*)(#{1,6})(?=[^\s#])/, '$1$2 ')
        // ② 中文/中文标点后直接接标题：断行 + 补空格
        .replace(/([\u4e00-\u9fa5）】》」』。！？；：、，])\s*(#{1,6})(?=[^\s#])/g, '$1\n$2 ')
    })
    .join('\n')
}

/** 视图内点击统一分发：引用卡片（REF-4.3）/ 代码块复制。 */
function onViewClick(e: MouseEvent): void {
  const target = e.target as HTMLElement | null
  if (!target?.closest) return

  const copyBtn = target.closest<HTMLElement>('button[data-md-copy]')
  if (copyBtn) {
    void copyCode(copyBtn)
    return
  }
  void onCiteClick(e)
}

/** 复制代码块正文；成功/失败都给出按钮内文字反馈（3 秒后复原）。 */
async function copyCode(btn: HTMLElement): Promise<void> {
  const code = btn.closest('.md-code')?.querySelector('pre code')
  const text = code?.textContent ?? ''
  if (!text) return
  const original = btn.textContent || '复制'
  try {
    await navigator.clipboard.writeText(text)
    btn.textContent = '已复制'
  } catch {
    btn.textContent = '复制失败'
  }
  window.setTimeout(() => {
    btn.textContent = original
  }, 3000)
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
/*
 * 排版基线（2026-10-18 重做，对齐 DeepSeek / Kimi / assistant-ui 的 AI 回复形态）
 *
 * 三处参照：
 *  - assistant-ui（`aui-md-*`）：正文 leading-7（1.75）、段间距 5px 量级、
 *    标题「首尾margin归零 + 自上而下递减」、表格 border-separate + 圆角；
 *  - Streamdown（Vercel）：代码块必须有语言标签 + 复制按钮，GFM 表格；
 *  - DeepSeek / Kimi 网页版：正文 15~16px、大段留白、标题层级明显、列表项间距充足。
 *
 * 本次把「逐元素微调」换成**等比排版体系**：字号用 1.2 倍模数（15 → 18 → 22），
 * 间距用 4 的倍数（6/10/14/18/24），避免每个元素各写一套魔法数字。
 */
.md-view {
  /* 基准：15px / 1.75 —— 比 14px 更接近中文长文舒适区，也是 DeepSeek 的实际取值 */
  font-size: 15px;
  line-height: 1.75;
  color: var(--ha-text, #1f2328);
  word-break: break-word;
  overflow-wrap: anywhere;
}
.md-view :deep(.md-empty) { color: var(--ha-muted); }

/* ── 标题：字号 22/19/17/15.5，段前大于段后（标题领起下文），首个元素去掉上间距 ── */
.md-view :deep(h1),
.md-view :deep(h2),
.md-view :deep(h3),
.md-view :deep(h4),
.md-view :deep(h5),
.md-view :deep(h6) {
  margin: 24px 0 10px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--ha-text, inherit);
}
.md-view :deep(h1) { font-size: 22px; }
.md-view :deep(h2) {
  font-size: 19px;
  /* 二级标题下方极淡分隔线：长文扫读锚点（Kimi / DeepSeek 同款） */
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ha-border, rgba(127, 127, 127, 0.14));
}
.md-view :deep(h3) { font-size: 17px; }
.md-view :deep(h4) { font-size: 15.5px; }
.md-view :deep(h5),
.md-view :deep(h6) { font-size: 15px; }
.md-view :deep(h1:first-child),
.md-view :deep(h2:first-child),
.md-view :deep(h3:first-child),
.md-view :deep(h4:first-child),
.md-view :deep(p:first-child),
.md-view :deep(ul:first-child),
.md-view :deep(ol:first-child) { margin-top: 0; }

/* ── 段落 / 列表：段间距 14px（原 10px 太挤），列表项间距 6px ── */
.md-view :deep(p) { margin: 0 0 14px; }
.md-view :deep(p:last-child) { margin-bottom: 0; }
.md-view :deep(ul),
.md-view :deep(ol) { margin: 0 0 14px; padding-left: 26px; }
.md-view :deep(li) { margin: 6px 0; }
.md-view :deep(li > p) { margin: 6px 0; }
.md-view :deep(li > ul),
.md-view :deep(li > ol) { margin: 6px 0; }
/* 列表标记颜色弱化：正文是主角，圆点是结构提示 */
.md-view :deep(ul > li::marker) { color: var(--ha-muted, #9aa0a6); }
.md-view :deep(ol > li::marker) { color: var(--ha-muted, #9aa0a6); font-variant-numeric: tabular-nums; }
.md-view :deep(li > input[type='checkbox']) { margin-right: 6px; }

/* ── 引用：左侧色条 + 极淡底 + 圆角（原文以「引用块」形态出现时更易与正文区分） ── */
.md-view :deep(blockquote) {
  margin: 0 0 14px;
  padding: 10px 16px;
  border-left: 3px solid var(--ha-primary, #4c8bf5);
  border-radius: 0 8px 8px 0;
  background: var(--ha-surface, rgba(127, 127, 127, 0.06));
  color: var(--ha-muted, #5f6368);
}
.md-view :deep(blockquote > :last-child) { margin-bottom: 0; }

/* ── 行内代码 ── */
.md-view :deep(code) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 0.88em;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--ha-surface, rgba(127, 127, 127, 0.14));
  color: var(--ha-text, inherit);
}

/* ── 代码块：语言标签 + 复制按钮的头部（对齐 Kimi「plain ⧉」形态） ── */
.md-view :deep(.md-code) {
  margin: 0 0 14px;
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.2));
  border-radius: 10px;
  overflow: hidden;
  background: var(--ha-code-bg, rgba(127, 127, 127, 0.07));
}
.md-view :deep(.md-code-head) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  border-bottom: 1px solid var(--ha-border, rgba(127, 127, 127, 0.16));
  background: var(--ha-surface, rgba(127, 127, 127, 0.08));
}
.md-view :deep(.md-code-lang) {
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-muted, #6b7280);
  text-transform: lowercase;
  letter-spacing: 0.02em;
}
.md-view :deep(.md-code-copy) {
  padding: 2px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  color: var(--ha-muted, #6b7280);
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.md-view :deep(.md-code-copy:hover) {
  background: var(--ha-surface-elevated, rgba(127, 127, 127, 0.14));
  border-color: var(--ha-border, rgba(127, 127, 127, 0.2));
  color: var(--ha-text, inherit);
}
.md-view :deep(.md-code-pre) {
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
  background: transparent;
  border: none;
  border-radius: 0;
}
.md-view :deep(.md-code-pre code) {
  padding: 0;
  background: transparent;
  font-size: 13px;
  line-height: 1.7;
}
/* 兼容：非 fence 规则产出的裸 pre（如 html 里手写的）仍保持块状外观 */
.md-view :deep(pre:not(.md-code-pre)) {
  margin: 0 0 14px;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  background: var(--ha-code-bg, rgba(127, 127, 127, 0.1));
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.18));
}

/* ── 表格：横向滚动容器 + 圆角 + 弱化网格（assistant-ui 的 border-separate 思路） ── */
.md-view :deep(.md-table-wrap) {
  margin: 0 0 14px;
  overflow-x: auto;
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.2));
  border-radius: 10px;
}
.md-view :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  line-height: 1.6;
}
.md-view :deep(th),
.md-view :deep(td) {
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid var(--ha-border, rgba(127, 127, 127, 0.16));
  border-right: 1px solid var(--ha-border, rgba(127, 127, 127, 0.14));
}
.md-view :deep(th:last-child),
.md-view :deep(td:last-child) { border-right: none; }
.md-view :deep(tr:last-child td) { border-bottom: none; }
.md-view :deep(th) {
  background: var(--ha-surface, rgba(127, 127, 127, 0.1));
  font-weight: 600;
  white-space: nowrap;
}

/* ── 分隔线 / 链接 / 图片 ── */
.md-view :deep(hr) {
  border: none;
  border-top: 1px solid var(--ha-border, rgba(127, 127, 127, 0.2));
  margin: 22px 0;
}
.md-view :deep(a) {
  color: var(--ha-primary, #4c8bf5);
  text-decoration: none;
  border-bottom: 1px solid transparent;
}
.md-view :deep(a:hover) {
  border-bottom-color: currentColor;
}
.md-view :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}

/* ── 引用溯源卡片（REF-4.3） ── */
.md-view :deep(.kb-cite) {
  display: inline;
  cursor: pointer;
  font-size: 12px;
  color: var(--ha-primary, #4c8bf5);
  background: var(--ha-surface, rgba(127, 127, 127, 0.1));
  border-radius: 4px;
  padding: 0 4px;
  margin: 0 2px;
  white-space: nowrap;
  vertical-align: 1px;
}
.md-view :deep(.kb-cite:hover) { background: rgba(76, 139, 245, 0.18); }

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
