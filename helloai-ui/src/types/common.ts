// ============================================================
// HelloAI 通用基础类型 — PageResult / R / LongId / IntCount
// ============================================================

// --- 分页 ---
// 数值字段（total / pages / current）后端保证为 JSON 数字：JacksonConfig 按 ID 语义
// 收窄 Long 序列化，仅 ID 字段写字符串、非 ID Long 数值写数字，契约由
// helloai-api 的 JacksonConfigLongSerializationTest 守护。
export interface PageResult<T> {
  list: T[]
  total: number
  pages: number
  current: number
}

// --- 统一返回 ---
export interface R<T> {
  code: number
  msg: string
  data: T
  traceId: string | null
}

// --- 主键 / 计数字段 ---
// v1.1 修复: Long ID 后端已序列化为 string, 前端类型用 string | number 兼容
// 任何对外使用的 Long 主键 / 外键字段（如 id、taskId、subTaskId、agentId、userId 等）均为 string
export type LongId = string | number
export type IntCount = number  // 普通计数字段仍按 number
