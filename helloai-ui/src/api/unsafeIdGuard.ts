/**
 * 开发期「雪花 ID 被当数字下发」哨兵。
 *
 * ============================================================================
 * 它解决什么
 * ============================================================================
 * 后端 `JacksonConfig` 把 `Long` 一律序列化成 JSON 字符串，正常情况下前端拿到
 * 的 ID 就是字符串。但只要有一处漏网（新 DTO 用了 `double`/`BigDecimal` 字段、
 * 某个 `Map<String,Object>` 走了别的序列化器、或是别的服务返回裸 SQL 行），
 * 19 位 ID 就会以 JSON **数字**出现——JSON.parse 那一刻已经静默丢精度，
 * 而且**无法 recover**：`2115975708197916799` 进来就变成 `2115975708197916800`。
 *
 * 这类问题的现象是「某个页面某次操作查不到数据」，与代码逻辑无关，极难反推。
 * 本哨兵在它还发生在**响应体**里时就把接口路径打出来，把"猜"变成"看"。
 *
 * ============================================================================
 * 为什么只在 DEV 生效
 * ============================================================================
 * - 生产环境已经没有修复空间（精度在 parse 时丢了），打日志只增加噪声与开销；
 * - 开发/联调阶段发现问题才有价值。
 *
 * 判据：`Number.isSafeInteger` 为 false 且不是小数（`0.1` 之类不是 ID）。
 * 误报面接近零——业务里没有 `|value| > 9e15` 的普通计数字段（字节数、耗时、token
 * 数都在 9 位以内）。
 */

/** 递归扫描的最大深度，防止超大对象图拖慢开发期请求（业务嵌套一般 < 8 层）。 */
const MAX_DEPTH = 12

/** 单次响应最多报告多少条，避免控制台被刷爆。 */
const MAX_REPORTS = 5

interface Hit {
  path: string
  value: number
}

function walk(node: unknown, path: string, depth: number, hits: Hit[]): void {
  if (hits.length >= MAX_REPORTS || depth > MAX_DEPTH || node == null) return

  if (typeof node === 'number') {
    // 非整数（小数）不可能是 ID；安全整数范围内也不会丢精度
    if (Number.isInteger(node) && !Number.isSafeInteger(node)) {
      hits.push({ path, value: node })
    }
    return
  }
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      walk(node[i], `${path}[${i}]`, depth + 1, hits)
      if (hits.length >= MAX_REPORTS) return
    }
    return
  }
  if (typeof node === 'object') {
    for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
      walk(v, path ? `${path}.${k}` : k, depth + 1, hits)
      if (hits.length >= MAX_REPORTS) return
    }
  }
}

/**
 * 扫描一个响应体，把「超出 Number 安全整数范围的数值字段」打到控制台。
 *
 * @param payload 已经 `JSON.parse` 过的响应体（精度已丢，只能用于定位）
 * @param url     请求地址，用于在报错里指明是哪个接口
 */
export function warnUnsafeIds(payload: unknown, url?: string): void {
  const hits: Hit[] = []
  walk(payload, '', 0, hits)
  if (hits.length === 0) return
  // eslint-disable-next-line no-console
  console.error(
    '[ID 精度哨兵] 接口返回了超出 Number 安全整数范围的数值字段，这些值已被静默舍入，'
    + '后端应把该字段按字符串下发（Long → JacksonConfig / ToStringSerializer）。\n'
    + `  接口: ${url ?? '(unknown)'}\n`
    + hits.map(h => `  字段: ${h.path} = ${h.value}（真实值末尾几位已丢失）`).join('\n')
  )
}
