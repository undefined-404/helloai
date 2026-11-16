/**
 * 子任务长文本结构化解析（内容 / 交付物 / 验收标准 / 执行约束）。
 *
 * <p><b>为什么需要它</b>：拆解产出的这几个字段在库里是「一段话」，里面其实带着结构——
 * `触发条件：…。执行通道：…。执行者：…。动作 (…)：1) …；2) …`。直接 `{{ text }}` 输出
 * 就成了一坨文字，读者要自己在大段里找边界。本模块把这类**已存在于文本中的轻结构**
 * 还原成「标签 + 条目清单」，不改动任何原文用词（只做切分与高亮，不重写内容）。</p>
 *
 * <p><b>为什么不用 markdown 渲染</b>：这些字段不是 markdown（枚举写成 `1)` 而不是 `1.`），
 * 走 markdown-it 反而不认列表；反过来，把 `1)` 硬转成 `1.` 再交给 markdown 会连带把
 * `>`、`#`、`|` 当块语法吃掉（约束里就写着「不得出现 >、>>」）。切分模型自己做，语义可控。</p>
 *
 * <p><b>未知形态一律降级为段落</b>：任何判据不满足就不切，宁可少格式，不可切错把原文断坏。</p>
 */

/** 一段可渲染的块。 */
export interface StructuredBlock {
  /** labeled = 带标签的段落；paragraph = 普通段落；list = 无标签条目清单 */
  kind: 'labeled' | 'paragraph' | 'list'
  /** 标签文本（kind=labeled） */
  label?: string
  /** 标签后的引导句（kind=labeled 且条目清单前还有引子时） */
  lead?: string
  /** 正文（kind=paragraph） */
  text?: string
  /** 条目清单（kind=labeled / kind=list） */
  items?: string[]
}

/** 句界：标签只允许出现在这些字符之后（句首语义）。 */
const SENTENCE_BOUNDARY = /[。；;\n]/

/** 标签长度上限：超过这个长度的「X：」多半是正文里的冒号，不是标签。 */
const MAX_LABEL_LEN = 14

/** 极长标签的兜底形态：`动作 (只读, 按原样执行并保留原始 stdout)`。 */
const LONG_LABEL_WITH_PAREN = /^[^\n。；;：:（()）]{1,10}[（(][^（()）]{1,30}[)）]$/

/** 枚举序号：`1)` `1）` `1、` 与 ①~⑩。 */
const ENUM_MARKER = /(\d{1,2})[)）、]|([①②③④⑤⑥⑦⑧⑨⑩])/g

const CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩'

/**
 * 判断某段文本是不是「标签」。
 *
 * <p>两条通道：短标签（≤14 字，覆盖 `触发条件` / `验收标准`）与「短词 + 括号说明」
 * （覆盖 `动作 (只读, …)` / `AUTOMATED（逐条可执行核定）`）。后者是必需的——动作段的
 * 标签里带逗号，若按「不含标点」判就会被漏掉，而它恰恰是最需要被看见的一段。</p>
 */
export function isLabel(candidate: string): boolean {
  const s = candidate.trim()
  if (!s) return false
  if (/[。；;\n：:]/.test(s)) return false
  if (s.length <= MAX_LABEL_LEN) return true
  return LONG_LABEL_WITH_PAREN.test(s)
}

/**
 * 在文本中定位所有标签起点。
 *
 * @returns `{ index, length, label }`：`index` 是标签首字符下标，`length` 含结尾冒号。
 */
function findLabels(text: string): Array<{ index: number; length: number; label: string }> {
  const found: Array<{ index: number; length: number; label: string }> = []
  // 逐字符扫描冒号，向左回退到最近的句界取候选标签：不用 lookbehind（旧内核不支持）
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (ch !== '：' && ch !== ':') continue
    let start = i - 1
    while (start >= 0 && !SENTENCE_BOUNDARY.test(text[start])) start--
    start += 1
    // 跳过句子开头与冒号之间的空白，避免标签带上前导空格
    while (start < i && /\s/.test(text[start])) start++
    const candidate = text.slice(start, i)
    if (!isLabel(candidate)) continue
    found.push({ index: start, length: i - start + 1, label: candidate.trim() })
  }
  return found
}

/**
 * 把一个正文段拆成条目清单。
 *
 * <p>判据（全部满足才拆）：至少 2 个序号、首个序号为 1、序号严格连续递增。
 * 只要有一条不满足就返回 null——宁可当段落读，也不能把 `sha256` 里的数字当序号。</p>
 */
export function splitEnumerated(body: string): string[] | null {
  ENUM_MARKER.lastIndex = 0
  const marks: Array<{ index: number; end: number; no: number }> = []
  let m: RegExpExecArray | null
  while ((m = ENUM_MARKER.exec(body)) !== null) {
    const no = m[1] ? Number(m[1]) : CIRCLED.indexOf(m[2]) + 1
    marks.push({ index: m.index, end: m.index + m[0].length, no })
  }
  if (marks.length < 2) return null
  if (marks[0].no !== 1) return null
  for (let i = 1; i < marks.length; i++) {
    if (marks[i].no !== marks[i - 1].no + 1) return null
  }
  const items: string[] = []
  for (let i = 0; i < marks.length; i++) {
    const from = marks[i].end
    const to = i + 1 < marks.length ? marks[i + 1].index : body.length
    const item = body.slice(from, to).trim().replace(/[；;]$/, '').trim()
    if (item) items.push(item)
  }
  // 序号有、条目空（如「1) 2) 3)」连写）时退出，避免渲染出三个空圆点
  if (items.length < 2) return null
  return items
}

/** 按 `；` 拆条目（仅用于「执行约束」这类逐条禁令文本，调用方显式开启）。 */
export function splitSemicolon(body: string): string[] | null {
  const parts = body
    .split(/[；;]/)
    .map(s => s.trim())
    .filter(Boolean)
  if (parts.length < 2) return null
  // 逐条禁令都很短；若平均长度过大说明这是散文里的分号，不是清单
  const total = parts.reduce((n, s) => n + s.length, 0)
  if (total / parts.length > 60) return null
  return parts
}

/**
 * 这段文本是否已自带 markdown 块结构（代码围栏 / 标题 / 表格）。
 *
 * <p>命中的文本不参与结构化切分，直接交给 {@code MarkdownView}——它本来就是 markdown，
 * 再切一遍会把 `>`、`#`、`|` 这些块语法断坏。</p>
 */
export function looksLikeMarkdown(text: string | null | undefined): boolean {
  if (!text) return false
  return /(^|\n)\s*(```|~~~|#{1,6}\s|\|)/.test(String(text).replace(/\r\n/g, '\n'))
}

/**
 * 解析长文本为可渲染块序列。
 *
 * @param text 原始文本
 * @param options.splitSemicolon 是否允许按 `；` 拆条目（执行约束用）
 */
export function parseStructuredText(
  text: string | null | undefined,
  options: { splitSemicolon?: boolean } = {}
): StructuredBlock[] {
  if (!text) return []
  const raw = String(text).replace(/\r\n/g, '\n')
  if (!raw.trim()) return []

  // 已带 markdown 块结构时不再自造结构，整段交给调用方原样渲染
  if (looksLikeMarkdown(raw)) {
    return [{ kind: 'paragraph', text: raw }]
  }

  const blocks: StructuredBlock[] = []
  const paragraphs = raw.split(/\n{2,}/)

  for (const para of paragraphs) {
    const labels = findLabels(para)
    if (!labels.length) {
      blocks.push(...blocksWithoutLabel(para, options))
      continue
    }
    // 标签前的引导文字（首个标签不在段首时）单独成段
    const head = para.slice(0, labels[0].index).trim()
    if (head) blocks.push(...blocksWithoutLabel(head, options))

    for (let i = 0; i < labels.length; i++) {
      const cur = labels[i]
      const from = cur.index + cur.length
      const to = i + 1 < labels.length ? labels[i + 1].index : para.length
      const body = para.slice(from, to).trim().replace(/[。；;]$/, '').trim()
      const block: StructuredBlock = { kind: 'labeled', label: cur.label }
      const items = splitEnumerated(body)
      if (items) {
        block.items = items
        const lead = body.slice(0, firstMarkerIndex(body)).trim().replace(/[：:；;]$/, '').trim()
        if (lead) block.lead = lead
      } else if (options.splitSemicolon) {
        // 执行约束是逐条禁令：有标签也要拆条目，否则「严格只读：不得…；不得…」仍是一句长文
        const parts = splitSemicolon(body)
        if (parts) block.items = parts
        else block.text = body
      } else {
        block.text = body
      }
      blocks.push(block)
    }
  }
  return blocks
}

/** 首个枚举序号的下标（用于把「五项证据：」这类引子从清单里摘出来）。 */
function firstMarkerIndex(body: string): number {
  ENUM_MARKER.lastIndex = 0
  const m = ENUM_MARKER.exec(body)
  return m ? m.index : body.length
}

/** 无标签段落的降级处理：先试枚举清单，再试（可选）分号清单，最后当普通段落。 */
function blocksWithoutLabel(
  text: string,
  options: { splitSemicolon?: boolean }
): StructuredBlock[] {
  const body = text.trim()
  if (!body) return []
  const items = splitEnumerated(body)
  if (items) {
    const lead = body.slice(0, firstMarkerIndex(body)).trim().replace(/[：:；;]$/, '').trim()
    const blocks: StructuredBlock[] = []
    if (lead) blocks.push({ kind: 'paragraph', text: lead })
    blocks.push({ kind: 'list', items })
    return blocks
  }
  if (options.splitSemicolon) {
    const parts = splitSemicolon(body)
    if (parts) return [{ kind: 'list', items: parts }]
  }
  return [{ kind: 'paragraph', text: body }]
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

/** 转义 HTML：所有原文先转义，再由调用方拼标签，避免 v-html 注入。 */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, ch => HTML_ESCAPES[ch] || ch)
}

/**
 * 行内渲染：把 `` `命令` `` 变成代码片，`**强调**` 变成粗体。
 *
 * <p>先按反引号切段再各自转义——顺序反了会把 `<code>` 本身当成原文转义掉。</p>
 */
export function renderInline(text: string): string {
  const parts = String(text).split(/(`[^`]+`)/g)
  return parts
    .map(part => {
      if (part.length > 2 && part.startsWith('`') && part.endsWith('`')) {
        return '<code>' + escapeHtml(part.slice(1, -1)) + '</code>'
      }
      return escapeHtml(part).replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    })
    .join('')
}

/**
 * 有没有可渲染的结构。
 *
 * <p>调用方用它决定「要不要走结构化视图」：纯净单段文本仍按纯文本渲染更省事。</p>
 */
export function hasStructure(blocks: StructuredBlock[]): boolean {
  return blocks.some(b => b.kind === 'labeled' || b.kind === 'list')
}
