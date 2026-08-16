// ============================================================
// HelloAI 类型定义 — Barrel，对齐后端 com.helloai 枚举和实体
//
// 拆包后仍以 `from '@/types'` 为主入口，新增类型请就近归类：
//   common.ts   通用基础（PageResult / R / LongId / IntCount）
//   enums.ts    枚举类型（status / role / policy）
//   entities.ts 业务实体（Task / SubTask / Agent ...）
//   dtos.ts     请求 DTO（ChangeStatusRequest / CreateSubTaskPayload ...）
//   clarify.ts  需求澄清专用（ClarifyQuestion / PlannerOption ...）
//   dialog.ts   UI 标签 / 颜色映射（STATUS_MAP / ROLE_COLOR_MAP ...）
// ============================================================

export * from './common'
export * from './enums'
export * from './entities'
export * from './dtos'
export * from './clarify'
export * from './dialog'
