// ============================================================
// HelloAI 类型定义 — 对齐后端 com.helloai 枚举和实体
// ============================================================

// --- 分页 ---
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
  hasMore: boolean
}

// --- 统一返回 ---
export interface R<T> {
  code: number
  msg: string
  data: T
  traceId: string | null
}

// --- 枚举 ---
export type SubTaskStatus = 'PENDING' | 'ASSIGNED' | 'IN_PROGRESS' | 'REVIEW'
  | 'DONE' | 'REWORK' | 'BLOCKED' | 'CANCELLED'

export type AgentRole = 'PLANNER' | 'EXECUTOR' | 'REVIEWER' | 'PATROL'

export type AgentStatus = 'ACTIVE' | 'DISABLED'

export type ReviewResult = 'APPROVED' | 'REJECTED'

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'

export type AttachmentStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED'

// --- 实体 ---
export interface Task {
  id: number
  title: string
  description: string
  status: TaskStatus
  createTime: string
  updateTime: string
}

export interface SubTask {
  id: number
  taskId: number
  moduleId: number | null
  title: string
  status: SubTaskStatus
  assignedAgent: number | null
  assignedAgentName?: string
  content: string
  context: Record<string, any> | null
  scoreFactors: Record<string, any> | null
  compositeScore: number | null
  scoreGrade: string | null
  deadline: string | null
  timeoutCount: number
  createTime: string
  updateTime: string
}

export interface Agent {
  id: number
  name: string
  role: AgentRole
  modelType: string | null
  status: AgentStatus
  score: number
  remark: string | null
  createTime: string
}

export interface ReviewRecord {
  id: number
  subTaskId: number
  reviewerAgent: number
  result: ReviewResult
  score: number
  issues: string | null
  comment: string | null
  round: number
  createTime: string
}

export interface RewardLog {
  id: number
  agentId: number
  subTaskId: number | null
  reason: string
  delta: number
  balance: number
  createTime: string
}

export interface ActivityLog {
  id: number
  agentId: number
  subTaskId: number | null
  action: string
  detail: Record<string, any> | null
  createTime: string
}

export interface PatrolRecord {
  id: number
  subTaskId: number
  patrolAgent: number
  alertType: string
  description: string | null
  createTime: string
}

export interface AgentOutboxEvent {
  id: number
  eventId: string
  eventType: string
  routingKey: string
  payload: Record<string, any>
  status: number
  retryCount: number
  errorMsg: string | null
  createTime: string
}

// --- Dashboard ---
export interface DashboardStats {
  totalTasks: number
  activeSubTasks: number
  pendingReviews: number
  blockedTasks: number
  agentRanking: { name: string; role: string; score: number }[]
  throughput: { date: string; count: number }[]
}

// --- 请求 DTO ---
export interface ChangeStatusRequest {
  subTaskId: number
  newStatus: SubTaskStatus
  agentId: number | null
}

export interface CreateReviewRequest {
  subTaskId: number
  result: ReviewResult
  score: number
  issues: string
  comment: string
  reworkAgentId: number | null
}

export interface AdjustScoreRequest {
  agentId: number
  scoreDelta: number
  reason: string
  subTaskId: number | null
}

// --- 状态标签映射 ---
export const SUB_TASK_STATUS_MAP: Record<SubTaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' }> = {
  PENDING:     { label: '待分配',   type: 'info' },
  ASSIGNED:    { label: '已分配',   type: '' },
  IN_PROGRESS: { label: '执行中',   type: 'primary' },
  REVIEW:      { label: '审查中',   type: 'warning' },
  DONE:        { label: '已完成',   type: 'success' },
  REWORK:      { label: '返工',     type: 'danger' },
  BLOCKED:     { label: '阻塞',     type: 'danger' },
  CANCELLED:   { label: '已取消',   type: 'info' }
}

export const SCORE_GRADE_MAP: Record<string, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  S: { label: 'S 卓越', type: 'success' },
  A: { label: 'A 优秀', type: 'success' },
  B: { label: 'B 良好', type: 'warning' },
  C: { label: 'C 不足', type: 'danger' },
  D: { label: 'D 差',   type: 'danger' }
}

export interface Rule {
  id: number
  name: string
  ruleType: string
  priority: number
  content: string
  remark: string | null
  createTime: string
  updateTime: string
}

export interface Attachment {
  id: number
  subTaskId: number
  fileName: string
  fileType: string
  mimeType: string
  fileSize: number
  bucketName: string
  objectKey: string
  storageUrl: string
  previewUrl: string | null
  status: AttachmentStatus
  createTime: string
}
