/**
 * 长整型 ID（雪花 ID）的统一处理入口。
 *
 * ============================================================================
 * 为什么必须有这个文件
 * ============================================================================
 * 后端主键是 `Long` + MyBatis-Plus `ASSIGN_ID`（雪花算法）⇒ 19 位十进制数，
 * 最大值约 1.9e18，而 JavaScript `Number` 只有 53 位有效位（安全整数上限
 * `Number.MAX_SAFE_INTEGER = 9007199254740991`，16 位）。
 *
 * 一旦把 ID 转成 `number`，超出部分会被**静默四舍五入**——不报错、不告警，
 * 只是末尾几位变成 `...00`。典型现场：
 *
 * ```text
 * 真实 ID   2115975708197916799
 * Number()  2115975708197916700   ← 后端查不到 ⇒ "Agent 不存在或非 ACTIVE"
 * ```
 *
 * 这种 bug 极难排查：接口返回 200、字段看着像 ID、日志里的 ID 也是"合法"数字，
 * 只有比对数据库才发现不一致。
 *
 * 后端的防线是 `JacksonConfig`（`Long` 一律序列化为 JSON 字符串）；前端唯一要做的
 * 就是**绝不把它转回数字**——ID 从进来到传出，全程按 `string` 透传。
 *
 * ============================================================================
 * 使用约定
 * ============================================================================
 * - 类型：ID 字段/参数一律用 `LongId`（`types/common.ts`），**不要**写 `number`。
 * - 传参：直接把接口返回的 ID 传给下一个接口，不做任何转换（字符串进 URL / body
 *   都能被后端正确反序列化）。
 * - 比较：用 {@link sameId}，不要用 `String(a) === String(b)` 到处写（后者容易被
 *   复制成 `a === b`，一混用数字就静默失配）。
 * - 需要字符串形态（拼 URL、做 `Set` 键、`v-model`）时用 {@link toId}。
 *
 * 禁止写法已由 ESLint 门禁拦下（`.eslintrc.cjs` 的 `no-restricted-syntax`）：
 * `Number(xxxId)` / `parseInt(xxxId)` / `+xxxId` 会直接报错。
 */
import type { LongId } from '@/types/common'

/**
 * 把 ID 规范化为字符串。
 *
 * 只在「需要字符串形态」时用（拼 URL、`Set` 键、`===` 之前的归一化）；
 * **不要**用它做数值运算。
 *
 * @param value 任意来源的 ID（接口返回值、路由 query、`v-model` 值）
 * @returns 字符串 ID；`null` / `undefined` 返回空串 `''`
 */
export function toId(value?: LongId | null): string {
  return value == null ? '' : String(value)
}

/**
 * 判断两个 ID 是否指向同一实体（`string` / `number` 混用也安全）。
 *
 * **空 ID 永不相等**：两个 `null` 返回 `false`。这样 `list.find(x => sameId(x.parentId, id))`
 * 不会把「都没有父节点」的多个元素误判成同一个。
 *
 * @param a 待比较 ID
 * @param b 待比较 ID
 */
export function sameId(a?: LongId | null, b?: LongId | null): boolean {
  const left = toId(a)
  return left !== '' && left === toId(b)
}
