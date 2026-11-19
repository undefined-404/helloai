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

/** 步骤流水中的一步（`先…。随后…。再…。最后…`）。 */
export interface StructuredStep {
  /** 步骤正文 */
  text: string
  /** 该步内部的子清单引导句（如「必须含 7 个必填小节：」） */
  subLead?: string
  /** 该步内部的子清单（`①…②…` 或 `1) …2) …`） */
  subItems?: string[]
}

/** 一段可渲染的块。 */
export interface StructuredBlock {
  /** labeled = 带标签的段落；paragraph = 普通段落；list = 无标签条目清单；steps = 步骤流水 */
  kind: 'labeled' | 'paragraph' | 'list' | 'steps'
  /** 标签文本（kind=labeled） */
  label?: string
  /** 标签后的引导句（kind=labeled 且条目清单前还有引子时） */
  lead?: string
  /** 正文（kind=paragraph） */
  text?: string
  /** 条目清单（kind=labeled / kind=list） */
  items?: string[]
  /** 步骤流水（kind=steps） */
  steps?: StructuredStep[]
}

/** 句界：标签只允许出现在这些字符之后（句首语义）。 */
const SENTENCE_BOUNDARY = /[。；;\n]/

/** 标签长度上限：超过这个长度的「X：」多半是正文里的冒号，不是标签。 */
const MAX_LABEL_LEN = 14

/** 极长标签的兜底形态：`动作 (只读, 按原样执行并保留原始 stdout)`。 */
const LONG_LABEL_WITH_PAREN = /^[^\n。；;：:（()）]{1,10}[（(][^（()）]{1,30}[)）]$/

/**
 * 枚举序号：分组为 `数字 + 分隔符` 与 `圈号`。
 *
 * <p><b>分隔符必须单独捕获</b>：`、` 在中文里同时承担「行内列举」（`对应验收条目 1、2、3`）
 * 与「清单序号」（行首 `1、第一条`）两种语义。前者若被当成序号，会让整段序号序列断裂
 * （1..9 之后又出现 1）从而整段放弃切分——正是「验收标准」字段糊成一坨的成因。
 * 故 `、` 只在句界之后才认（见 {@link isBoundaryBefore}），`)` / `）` / 圈号恒强。</p>
 */
const ENUM_MARKER = /(\d{1,2})([)）、])|([①②③④⑤⑥⑦⑧⑨⑩])/g

const CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩'

/**
 * 序号标记是否处在「条目起点」。
 *
 * <p>判据：向左跳过空白后，前一个字符必须是句界（`。；;` / 换行）、冒号（`：` — `必须含 7 项：①…`）、
 * 上一个条目/括号的收尾（`）` / `)`），或已在文本开头。</p>
 *
 * <p><b>必须对圈号也生效</b>：正文里会引用序号——「文件名与①逐字一致」「默认值取①的真实路径」，
 * 这些 `①` 前面是汉字，若算作标记会把序号序列打回 1，导致 `①…⑦` 只切出前两项。
 * 同理 `条目 1、2、3…9）` 的 `9）` 前面是 `、`，也应排除。</p>
 */
function isMarkerStart(body: string, index: number): boolean {
  let i = index - 1
  while (i >= 0 && (body[i] === ' ' || body[i] === '\t')) i--
  if (i < 0) return true
  return /[。；;：:\n）)]/.test(body[i])
}

/**
 * 收集文本中所有**可信**的序号标记。
 *
 * <p>与直接跑正则的区别：过滤掉行内列举与正文里对序号的引用，避免「序号断裂 ⇒ 整段放弃」。</p>
 */
function collectMarks(body: string): Array<{ index: number; end: number; no: number }> {
  ENUM_MARKER.lastIndex = 0
  const marks: Array<{ index: number; end: number; no: number }> = []
  let m: RegExpExecArray | null
  while ((m = ENUM_MARKER.exec(body)) !== null) {
    if (!isMarkerStart(body, m.index)) continue
    const no = m[1] ? Number(m[1]) : CIRCLED.indexOf(m[3]) + 1
    marks.push({ index: m.index, end: m.index + m[0].length, no })
  }
  return marks
}

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
  const marks = collectMarks(body)
  // 起点是**第一个 no=1 的标记**，不是 marks[0]：正文里常先出现 `条目 1、2、3…9）`
  // 这种行内列举，其尾部 `9）` 是强标记（`）` 恒强）却会让 marks[0].no=9，
  // 按「首标记必须是 1」判就直接放弃切分——验收标准字段糊成一坨正是这么来的。
  const from = marks.findIndex(m => m.no === 1)
  if (from < 0) return null
  // 只取「严格递增的连续前缀」：`①…⑦` 之后常出现 `默认值取①的真实路径` 这类引用，
  // 它会把序号打回 1。遇中断即停，剩余文本并入最后一项（语义上本就属于最后一项），
  // 比整段放弃切分好。
  const seq = [marks[from]]
  for (let i = from + 1; i < marks.length; i++) {
    if (marks[i].no === seq[seq.length - 1].no + 1) seq.push(marks[i])
    else break
  }
  if (seq.length < 2) return null
  const items: string[] = []
  for (let i = 0; i < seq.length; i++) {
    const start = seq[i].end
    const end = i + 1 < seq.length ? seq[i + 1].index : body.length
    const item = body.slice(start, end).trim().replace(/[；;]$/, '').trim()
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
    .map(s => s.trim().replace(/[。；;]$/, '').trim())
    .filter(Boolean)
  if (parts.length < 2) return null
  // 逐条禁令都很短；若平均长度过大说明这是散文里的分号，不是清单
  const total = parts.reduce((n, s) => n + s.length, 0)
  if (total / parts.length > 60) return null
  return parts
}

/**
 * 按句号切句（括号内不切）。
 *
 * <p>不切括号内是硬要求：拆解产出的句子常把「退出码语义表（0=…；单链路非 0=…）」整段
 * 塞在括号里，按裸 `。` 切会把表格语义拦腰截断。</p>
 */
function splitSentences(text: string): string[] {
  const out: string[] = []
  let depth = 0
  let start = 0
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (ch === '（' || ch === '(') depth++
    else if (ch === '）' || ch === ')') depth = Math.max(0, depth - 1)
    else if (ch === '。' && depth === 0) {
      const s = text.slice(start, i + 1).trim()
      if (s) out.push(s)
      start = i + 1
    }
  }
  const tail = text.slice(start).trim()
  if (tail) out.push(tail)
  return out
}

/** 流水连接词：出现 ≥2 个才判定为「步骤流水」，单个「再/然后」不算。 */
const STEP_CONNECTIVE = /^(随后|其次|再次|接着|然后|再说|再|最后|最终|之后|下一步)/

/**
 * 把「先…。随后…。再…。最后…」的流水句拆成有序步骤。
 *
 * <p><b>为什么要拆</b>：content 字段常是一整段（零空行）却含 4 个动作阶段，
 * 不拆就是 1500 字一堵墙。判据保守——必须 ≥3 句且 ≥2 句以连接词开头，
 * 否则散文叙述也会被切成列表。</p>
 *
 * <p>每步内部若含 `①…②…` 这类子清单（「必须含 7 个必填小节：①…」），
 * 一并拆成 subItems，否则「最后一步」仍会是一坨。</p>
 */
export function splitSteps(text: string): StructuredStep[] | null {
  const sentences = splitSentences(text)
  if (sentences.length < 3) return null
  const connectives = sentences.filter((s, i) => i > 0 && STEP_CONNECTIVE.test(s.trim()))
  if (connectives.length < 2) return null
  return sentences.map(s => {
    const subItems = splitEnumerated(s)
    if (!subItems) return { text: s }
    const lead = s.slice(0, firstMarkerIndex(s)).trim().replace(/[：:；;，,]$/, '').trim()
    if (!lead) return { text: s }
    return { text: '', subLead: lead, subItems }
  })
}

/**
 * 拆「括号内的斜杠清单」：`…全文（7 个必填小节：路径确认记录 / 链路清单 / …），并登记为附件`。
 *
 * <p><b>为什么限定括号在句尾</b>：`定位四条脚本（A / B / C）的真实文件名` 这种括号夹在句中，
 * 拆成列表会把句子拦腰截断（列表插在主语与宾语之间）。只在括号后紧接收尾标点
 * （`，` `。` `；` `、`）或已到文末时才拆。</p>
 */
export function extractParenSlashList(
  text: string
): { lead: string; items: string[] } | null {
  const m = /[（(]([^（()）]{20,})[)）]/.exec(text)
  if (!m) return null
  const inner = m[1]
  const parts = inner.split(/\s+\/\s+/).map(s => s.trim()).filter(Boolean)
  if (parts.length < 3) return null
  if (parts.some(p => p.length < 2)) return null
  const after = text.slice(m.index + m[0].length).trim()
  if (after && !/^[，。；、]/.test(after)) return null
  const colon = inner.search(/[：:]/)
  const prefix = colon > 0 ? inner.slice(0, colon).trim() : ''
  // 前缀从首项里剥掉：`7 个必填小节：路径确认记录` 的第一项应是「路径确认记录」
  const firstItem = colon > 0 ? parts[0].slice(colon + 1).trim() : parts[0]
  if (!firstItem) return null
  const items = [firstItem, ...parts.slice(1)]
  const head = text.slice(0, m.index).trim()
  const lead = [head + (prefix ? '（' + prefix + '）' : ''), after.replace(/^[，、]\s*/, '')]
    .filter(Boolean)
    .join('，')
  return { lead, items }
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

/** 首个可信枚举序号的下标（用于把「五项证据：」这类引子从清单里摘出来）。 */
function firstMarkerIndex(body: string): number {
  const marks = collectMarks(body)
  const first = marks.find(m => m.no === 1)
  return first ? first.index : body.length
}

/**
 * 无标签段落的降级处理，按「信息量从高到低」逐级尝试：
 * 步骤流水 → 枚举清单 → 括号斜杠清单 → （可选）分号清单 → 普通段落。
 *
 * <p><b>步骤流水必须排在枚举之前</b>：`最后撰写…必须含 7 个必填小节：①…⑦…` 同时满足
 * 两者，但整段的主结构是「4 个动作阶段」，`①…⑦` 只是最后一步的子清单。
 * 若先按枚举拆，会得到「1 条超长条目 + 7 条失去步骤上下文的碎片」。</p>
 */
function blocksWithoutLabel(
  text: string,
  options: { splitSemicolon?: boolean }
): StructuredBlock[] {
  const body = text.trim()
  if (!body) return []
  const steps = splitSteps(body)
  if (steps) return [{ kind: 'steps', steps }]
  const items = splitEnumerated(body)
  if (items) {
    const lead = body.slice(0, firstMarkerIndex(body)).trim().replace(/[：:；;]$/, '').trim()
    const blocks: StructuredBlock[] = []
    if (lead) blocks.push({ kind: 'paragraph', text: lead })
    blocks.push({ kind: 'list', items })
    return blocks
  }
  const slash = extractParenSlashList(body)
  if (slash) {
    const blocks: StructuredBlock[] = []
    if (slash.lead) blocks.push({ kind: 'paragraph', text: slash.lead })
    blocks.push({ kind: 'list', items: slash.items })
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
  return blocks.some(b => b.kind === 'labeled' || b.kind === 'list' || b.kind === 'steps')
}
