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
export function fmtTime(t: string | null | undefined): string {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

// ---- 时间拆为日期/时分秒两部分 ----
// 窄列场景下上下两行展示，节省横向空间；返回结构含 null 让模板走占位分支
export function splitDateTime(t: string | null | undefined): { date: string; time: string } | null {
  if (!t) return null
  const normalized = t.replace('T', ' ')
  const s = normalized.substring(0, 19)
  const sp = s.indexOf(' ')
  if (sp < 0) return { date: s, time: '' }
  return { date: s.substring(0, sp), time: s.substring(sp + 1) }
}

// ---- 文件大小格式化 ----
export function fmtSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
