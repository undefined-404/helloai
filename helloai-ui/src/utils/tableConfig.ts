/**
 * HelloAI 表格样式统一配置
 * 所有列表页引用此文件，确保列宽、间距、操作列一致
 */

// ---- 标准列宽 (px) ----
export const COL = {
  STATUS: 90,
  SCORE: 70,
  TIME: 170,
  TAG: 100,
  COUNT: 80,
  VERSION: 60,
} as const

// ---- 操作列宽度 ----
export const ACTION = {
  ONE: 80,
  TWO: 150,
  THREE: 220,
  FOUR: 280,
} as const

// ---- 时间格式化 ----
// 把时间字符串规范成浏览器本地时区的 "YYYY-MM-DD HH:mm:ss"。
// 后端 OffsetDateTime 会序列化成带时区偏移的 ISO8601（如 ...Z 或 ...+08:00），
// 旧实现直接 substring(0,19) 把偏移砍掉，UTC 值会显示成比北京时间少 8 小时；
// 这里对带时区语义的串用 Date 正确转换到本地时区，无时区信息的串回退为去后缀原样展示。
function normalizeTime(t: string | null | undefined): string | null {
  if (!t) return null
  const s = t.trim()
  if (!s) return null
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)
  if (!hasZone) return t.replace('T', ' ').substring(0, 19)
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return t.replace('T', ' ').substring(0, 19)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtTime(t: string | null | undefined): string {
  return normalizeTime(t) ?? '-'
}

// ---- 时间拆为日期/时分秒两部分 ----
// 窄列场景下上下两行展示，节省横向空间；返回结构含 null 让模板走占位分支
export function splitDateTime(t: string | null | undefined): { date: string; time: string } | null {
  const n = normalizeTime(t)
  if (!n) return null
  const sp = n.indexOf(' ')
  if (sp < 0) return { date: n, time: '' }
  return { date: n.substring(0, sp), time: n.substring(sp + 1) }
}

// ---- 文件大小格式化 ----
export function fmtSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
