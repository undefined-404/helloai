<template>
  <!-- Markdown 渲染视图：把执行产出/对话内容渲染成带格式的富文本（类 DeepSeek/Kimi 排版）。
       REF-4.3：解析内联引用 token [kb:{source}#{idx}]，渲染为可点开来源的引用卡片。 -->
  <div
    class="md-view"
    :class="{ 'is-compact': compact }"
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

const props = withDefaults(defineProps<{
  content: string | null | undefined
  /** 紧凑变体：思考过程等「次要层级」文本用——字号/段距降一档，行内代码底衬取亮面（面板本身是浅灰） */
  compact?: boolean
}>(), { compact: false })

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
 *   <li>中文/中文标点后<b>直接接</b> `#{1,6}` ⇒ 先断行，补空格交由① 统一处理
 *       （LLM 常在句末直接续写下一个标题，如 `##几个项目###1. xxx`）。</li>
 * </ol>
 * <b>跳过 fenced code block</b>（``` 或 ~~~）内的内容——那里 `#` 是代码/注释，不能改。</p>
 *
 * <p><b>2026-10-23 补：粘连标题必须判长度</b>。实测库里存在这一类真实输出：
 * <pre>
 * ##核对范围本次能核对的是检索召回的7个片段：RabbitMQ架构设计（#157、#158）……
 * ##四、可以打磨但不影响对错的地方1. **“平均分摊”**：加上 prefetch后应按处理能力竞争……
 * ##一、RabbitMQ部分逐条核对|原文表述 |判断 |说明 |
 * |---|---|---|
 * </pre>
 * 「小节名」后面直接粘上整段正文/表格头/列表首项。原来无脑补空格的结果是
 * <b>整段正文被当成标题</b>：19px + 700 字重 + 撑满整屏——正是用户反馈的「加粗突兀、比正文大一号」。
 * 更糟的是被吞掉表头后，下面的 `|---|---|---|` 失去表格上下文，<b>整张表格一起塌成段落</b>。</p>
 *
 * <p><b>处理策略（只作用于粘连写法，带空格的正规标题一律不动）</b>：
 * <ol>
 *   <li>余文 ≤ {@link MD_HEADING_MAX} 字 ⇒ 就是漏了空格，补空格当普通标题（保持既有行为）；</li>
 *   <li>余文超长且能识别出「小节名 / 正文」边界（全角冒号、&gt;、|、列表标记、嵌套 #、引号）且小节名本身不过长
 *       ⇒ 切成「标题 + 余下内容」，表格与列表因此能正常解析；</li>
 *   <li>余文超长又找不到可信边界 ⇒ 整行降级为普通段落。<b>宁可丢掉标题，也绝不把整段正文顶成巨号粗体。</b></li>
 * </ol>
 * 两条配套约束（均由真实数据逼出来）：
 * <ul>
 *   <li><b>规则 ② 切出的每一段都要再走一遍规则 ①</b>——它把\n插在字符串中间，
 *       若只处理整串开头，`##三、必须修正的四条###修正1：…` 的第二段会漏网，
 *       于是 151 字正文又被当成标题（原样例子的最大坑）。</li>
 *   <li><b>知识库引用编号不是标题</b>（见 {@link CITE_HASH}）。真实正文里 `（#157、#158）` 极其密集，
 *       若不排除，`（`／`、` 后的 `#` 会被规则 ② 切成新行再被规则 ① 补空格 ⇒ 凭空冒出一排 `<h1>157</h1>`。</li>
 * </ul></p>
 */
function normalizeHeadings(src: string): string {
  const lines = src.split('\n')
  let inFence = false
  const out: string[] = []
  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence
      out.push(line)
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }
    // ② 中文/中文标点后直接续写标题：只负责断行。
    //    这里**绝不能顺手补空格**——补了之后切出的第二段就成了合法标题，① 的长度判断再没机会跑，
    //    `##三、必须修正的四条###修正1：…` 的后半段仍会整段变标题（实测 151 字巨号粗体）。
    const fixed = line.replace(
      /([一-龥）】》」』。！？；：、，])\s*(#{1,6})(?=[^\s#\d])/g,
      '$1\n$2'
    )
    // ① 行首粘连标题：按长度决定是补空格 / 切断 / 降级。
    //    逐段处理，② 刚在字符串中间插了 \n，漏掉第二段同样会重演「整段正文变标题」。
    for (const seg of fixed.split('\n')) out.push(splitGluedHeading(seg))
  }
  return out.join('\n')
}

/** 粘连标题里「小节名」的长度上限；超过就不信任它是个标题（中文小节名实测 2~18 字）。 */
const MD_HEADING_MAX = 24

/**
 * 冒号边界只认<b>短标签</b>：LLM 写 `##结论：正文`、`###修正1：RabbitMQ…` 时冒号前是标签，
 * 而 `##核对范围本次能核对的是检索召回的7个片段：RabbitMQ…` 冒号前是<b>半句话</b>。
 * 同一条回复里两种形态都有，所以冒号边界卡 12 字，比结构性边界严得多。
 *
 * <p>不设这条规则的话，`修正1~修正4` 四个平行条目会一半渲染成标题、一半降级成段落——
 * 结构本身不一致，比「大一号」更难看。</p>
 */
const MD_LABEL_MAX = 12

/**
 * `#` 后紧跟数字、且不是「数字. 」列表形态 ⇒ 判定为<b>知识库引用编号</b>而非标题。
 *
 * <p>真实 AI 回复里这类编号密度极高（`架构设计（#157、#158）`、`[#159][#160]`、`（#165末尾截断）`），
 * 与 `##1. 项目名` 这类真粘连标题仅一字之差，靠「后面跟什么」区分：
 * 编号后面跟的是标点／汉字／行尾，列表标题后面固定是 `. `。</p>
 */
const CITE_HASH = /^#{1,6}\d+(?!\.\s)/

/**
 * 处理行首 `#{1,6}` 后<b>紧跟非空白</b>的粘连写法。带空格的正规标题原样返回。
 * 见 {@link normalizeHeadings} 的 2026-10-23 补充说明。
 */
function splitGluedHeading(line: string): string {
  const m = /^(\s*)(#{1,6})(?=[^\s#])/.exec(line)
  if (!m) return line

  const rest = line.slice(m[0].length)
  // 知识库引用编号（`#157`）不是标题，原样返回——否则会凭空生成一排 <h1>157</h1>
  if (CITE_HASH.test(line.slice(m[1].length))) return line

  const indent = m[1]
  const hashes = m[2]

  // 余文够短：只是漏了空格，补上即可
  if (charCount(rest) <= MD_HEADING_MAX) return indent + hashes + ' ' + rest

  // 余文过长：伪标题。先找「小节名 / 正文」的自然边界
  const cut = findSplitPoint(rest)
  if (cut) {
    const label = rest.slice(0, cut.labelEnd).trimEnd()
    const body = rest.slice(cut.bodyStart)
    if (label && body) return indent + hashes + ' ' + label + '\n' + body
  }

  // 无可信边界：降级为普通段落（去掉 #），避免整段正文变成巨号粗体
  return rest
}

/** 「小节名 / 正文」的切分位置：labelEnd 供标题取用，bodyStart 跳过冒号一类的分隔符。 */
interface HeadingCut {
  labelEnd: number
  bodyStart: number
}

/**
 * 在粘连标题正文里找「小节名结束、正文开始」的位置，找不到返回 null。
 *
 * <p>只认<b>结构性强边界</b>：全角冒号、引用块 &gt;、表格竖线 |、列表标记、嵌套标题、引号。
 * 这些符号出现在小节名里的概率极低，误切风险远低于按字数盲猜。
 * 逐个向后找，<b>取第一个能产出合规小节名的边界</b>——首个边界的小节名就已超长时，
 * 继续找也只会更长，直接放弃比乱切安全。</p>
 */
function findSplitPoint(rest: string): HeadingCut | null {
  for (let i = 1; i < rest.length; i++) {
    // 全角冒号：分隔符本身不进正文
    if (rest[i] === '：' && charCount(rest.slice(0, i)) <= MD_LABEL_MAX) {
      return { labelEnd: i, bodyStart: i + 1 }
    }
    if (!isSectionBoundary(rest, i)) continue
    if (charCount(rest.slice(0, i)) <= MD_HEADING_MAX) {
      return { labelEnd: i, bodyStart: i }
    }
  }
  return null
}

/** rest[i] 处是否是「小节名 / 正文」的结构性边界（引用编号除外，见 {@link CITE_HASH}）。 */
function isSectionBoundary(rest: string, i: number): boolean {
  const ch = rest[i]
  // 引用块 / 表格列 / 引号
  if (ch === '>' || ch === '|' || ch === '“' || ch === '「' || ch === '【') return true
  // 嵌套标题（##小节名###小节名 —— ② 已处理带空格的情形，这里兜底无空格的）
  if (ch === '#') {
    const tail = rest.slice(i)
    return /^#{1,6}(?=[^\s#])/.test(tail) && !CITE_HASH.test(tail)
  }
  // 无序列表标记
  if ((ch === '-' || ch === '*' || ch === '+') && rest[i + 1] === ' ') return true
  // 有序列表标记：1. / 12.
  if (ch >= '0' && ch <= '9' && rest[i + 1] === '.' && rest[i + 2] === ' ') return true
  return false
}

/** 按字符数（含中文）计长；用展开写法避免代理对被算成两个。 */
function charCount(s: string): number {
  let n = 0
  for (const _ of s) n++
  return n
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
 * 本次把「逐元素微调」换成**等比排版体系**：字号用 1.1 倍模数（15 → 16.5 → 18.5 → 21），
 * 间距用 4 的倍数（4/6/8/10/12），避免每个元素各写一套魔法数字。
 *
 * 2026-10-23 收窄：原模数是 1.2 倍（15 → 18 → 22），一级标题比正文大 47%，
 * 叠加强调文字用 700 字重后就是用户反馈的「加粗突兀、比正文大一号」。1.1 倍下
 * 一级标题只大 40%、四级标题仍有 1px 可感差异（原来 h4 只差 0.5px、h5/h6 与正文同号）。
 */
.md-view {
  /* 基准：15px / 1.75 —— 比 14px 更接近中文长文舒适区，也是 DeepSeek 的实际取值 */
  font-size: 15px;
  line-height: 1.75;
  /* 用 --ha-ink（曾误写 --ha-text，该变量全项目未定义，导致暗色主题下正文被硬编码成深色） */
  color: var(--ha-ink, #1a2233);
  word-break: break-word;
  overflow-wrap: anywhere;
  text-wrap: pretty;
}
.md-view :deep(.md-empty) { color: var(--ha-muted); }

/* ── 标题：15(正文) → 15.5 / 16.5 / 18.5 / 21，等比约 1.1，段前间距随层级递减 ──
   旧值 22/19/17/15.5/15 的问题是 h4 与正文仅差 0.5px、h5/h6 与正文同号，
   层级既不靠字号也不靠间距；同时 6 个标题共用 margin: 24px 0 10px，
   于是 15px 的 h6 也占 24px 段前距。 */
.md-view :deep(h1),
.md-view :deep(h2),
.md-view :deep(h3),
.md-view :deep(h4),
.md-view :deep(h5),
.md-view :deep(h6) {
  font-weight: 600;
  color: var(--ha-ink);
  text-wrap: balance;
  letter-spacing: -0.01em;
}
.md-view :deep(h1) { font-size: 21px; line-height: 1.45; margin: 30px 0 12px; }
.md-view :deep(h2) {
  font-size: 18.5px;
  line-height: 1.45;
  margin: 26px 0 10px;
  /* 二级标题下方极淡分隔线：长文扫读锚点（Kimi / DeepSeek 同款） */
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ha-border);
}
.md-view :deep(h3) { font-size: 16.5px; line-height: 1.5; margin: 20px 0 8px; }
.md-view :deep(h4) { font-size: 15.5px; line-height: 1.5; margin: 18px 0 6px; }
.md-view :deep(h5),
.md-view :deep(h6) { font-size: 15px; line-height: 1.5; margin: 16px 0 4px; }
.md-view :deep(h1:first-child),
.md-view :deep(h2:first-child),
.md-view :deep(h3:first-child),
.md-view :deep(h4:first-child),
.md-view :deep(p:first-child),
.md-view :deep(ul:first-child),
.md-view :deep(ol:first-child) { margin-top: 0; }

/* ── 行内强调：不用浏览器默认的 bolder(=700) ──
   项目字重词汇是 400/500/600，700 越界会让「行内加粗比任何标题都重」，层级倒挂。
   注意：本机字体栈（Inter 未加载 → system-ui + 微软雅黑）没有 Semibold 字面，
   600 与 700 落到同一物理字面（实测像素差 0），所以这条规则在当前环境下
   不改变粗细，只保证字重语义正确、并在有 Semibold 的环境（PingFang SC）真正变柔。
   中文没有「半粗」，加粗段的粗细无法再压缩——真正的层级差异交给标题字号承担。 */
.md-view :deep(strong),
.md-view :deep(b) {
  font-weight: 600;
}

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

/* ── 引用：整圈细描边 + 极淡底（不用 3px 左侧色条——侧条强调是禁用的装饰手法，
   且原文以「引用块」形态出现时靠底色与正文区分已经足够） ── */
.md-view :deep(blockquote) {
  margin: 0 0 14px;
  padding: 10px 16px;
  border: 1px solid var(--ha-border);
  border-radius: 8px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
}
.md-view :deep(blockquote > :last-child) { margin-bottom: 0; }

/* ── 行内代码 ──
   底衬不能用 --ha-surface：亮色主题下它就是 #FFFFFF，与白底页面同色（实测 computed 对比度 1.00:1，
   chip 完全不可见）。改用 --ha-surface-hover（浅灰面），与 DeepSeek 参考形态一致。 */
.md-view :deep(code) {
  font-family: var(--ha-font-mono);
  font-size: 0.88em;
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink);
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
  color: var(--ha-ink, inherit);
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
  border: 1px solid var(--ha-border);
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
  border-bottom: 1px solid var(--ha-border-light);
  border-right: 1px solid var(--ha-border-light);
}
.md-view :deep(th:last-child),
.md-view :deep(td:last-child) { border-right: none; }
.md-view :deep(tr:last-child td) { border-bottom: none; }
.md-view :deep(th) {
  background: var(--ha-surface-hover);
  font-weight: 600;
  white-space: nowrap;
}

/* ── 分隔线 / 链接 / 图片 ── */
.md-view :deep(hr) {
  border: none;
  border-top: 1px solid var(--ha-border);
  margin: 22px 0;
}
.md-view :deep(a) {
  color: var(--ha-primary);
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

/* ── 紧凑变体（思考过程等次要层级文本）
   面板本身是浅灰底，所以行内代码底衬取「亮面」(--ha-surface-elevated) 才看得见；
   标题在思考流里只是分隔线索，统一降到 14.5px。 */
.md-view.is-compact {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ha-ink-secondary);
}
.md-view.is-compact :deep(p) { margin: 0 0 8px; }
.md-view.is-compact :deep(ul),
.md-view.is-compact :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.md-view.is-compact :deep(li) { margin: 3px 0; }
.md-view.is-compact :deep(h1),
.md-view.is-compact :deep(h2),
.md-view.is-compact :deep(h3),
.md-view.is-compact :deep(h4),
.md-view.is-compact :deep(h5),
.md-view.is-compact :deep(h6) {
  font-size: 14.5px;
  line-height: 1.5;
  margin: 12px 0 6px;
  padding-bottom: 0;
  border-bottom: none;
}
.md-view.is-compact :deep(h1:first-child),
.md-view.is-compact :deep(h2:first-child),
.md-view.is-compact :deep(h3:first-child),
.md-view.is-compact :deep(h4:first-child),
.md-view.is-compact :deep(p:first-child),
.md-view.is-compact :deep(ul:first-child),
.md-view.is-compact :deep(ol:first-child) { margin-top: 0; }
.md-view.is-compact :deep(code) {
  background: var(--ha-surface-elevated);
  color: var(--ha-ink);
}
.md-view.is-compact :deep(hr) { margin: 12px 0; }
.md-view.is-compact :deep(blockquote) {
  padding: 8px 12px;
  background: var(--ha-surface-elevated);
}

/* ── 引用溯源卡片（REF-4.3） ── */
.md-view :deep(.kb-cite) {
  display: inline;
  cursor: pointer;
  font-size: 12px;
  color: var(--ha-primary);
  background: var(--ha-surface-hover);
  border-radius: 4px;
  padding: 0 4px;
  margin: 0 2px;
  white-space: nowrap;
  vertical-align: 1px;
}
.md-view :deep(.kb-cite:hover) { background: var(--ha-primary-muted); }

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
