// ============================================================
// HelloAI UI 标签 / 颜色映射 — 纯前端展示用
// ============================================================

import type { AgentRole, SubTaskStatus, TaskStatus } from './enums'

// --- 角色颜色映射 ---
export const ROLE_COLOR_MAP: Record<AgentRole, { bar: string; bg: string; text: string; border: string; tagType: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PLANNER:  { bar: '#7C3AED', bg: '#F5F3FF', text: '#6D28D9', border: '#EDE9FE', tagType: '' },
  EXECUTOR: { bar: '#3B82F6', bg: '#EFF6FF', text: '#2563EB', border: '#DBEAFE', tagType: 'primary' },
  REVIEWER: { bar: '#F59E0B', bg: '#FFFBEB', text: '#D97706', border: '#FEF3C7', tagType: 'warning' },
}

// --- 状态标签映射 ---
export const SUB_TASK_STATUS_MAP: Record<SubTaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PENDING:     { label: '待分配',   type: 'info' },
  ASSIGNED:    { label: '已分配',   type: '' },
  IN_PROGRESS: { label: '执行中',   type: 'primary' },
  PAUSED:      { label: '已暂停',   type: 'warning' },
  REVIEW:      { label: '审查中',   type: 'warning' },
  DONE:        { label: '已完成',   type: 'success' },
  REWORK:      { label: '返工',     type: 'danger' },
  BLOCKED:     { label: '阻塞',     type: 'danger' },
  CANCELLED:   { label: '已取消',   type: 'info' },
  DEAD_LETTER: { label: '死信待人工', type: 'danger' },
  PENDING_PLAN_REVIEW: { label: '草案待审', type: 'warning' }
}

export const TASK_STATUS_MAP: Record<TaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PENDING:     { label: '待规划',   type: 'info' },
  PLANNING:    { label: '拆解中',   type: 'warning' },
  IN_PROGRESS: { label: '进行中',   type: 'primary' },
  DONE:        { label: '已完成',   type: 'success' },
  CANCELLED:   { label: '已取消',   type: 'info' }
}

export const SCORE_GRADE_MAP: Record<string, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  S: { label: 'S 卓越', type: 'success' },
  A: { label: 'A 优秀', type: 'success' },
  B: { label: 'B 良好', type: 'warning' },
  C: { label: 'C 不足', type: 'danger' },
  D: { label: 'D 差',   type: 'danger' }
}

export const PROMPT_CATEGORY_MAP: Record<string, string> = {
  ROLE_TEMPLATE: '角色模板',
  AGENT_SPECIALIZATION: 'Agent 专业化',
  SKILL: '技能文档'
}

export const INBOX_EVENT_TYPE_MAP: Record<string, string> = {
  'sub_task.assigned': '新任务',
  'sub_task.submitted': '待审查',
  'sub_task.rejected': '需返工',
  'sub_task.blocked': '任务阻塞',
  'sub_task.paused': '已暂停',
  'sub_task.resumed': '已恢复',
  'sub_task.cancelled': '已取消',
  'task.completed': '任务完成'
}
