import request from './request'
import { paths } from './paths'

/**
 * 平台技能包目录项（REF-1.2c 服务端下发）。
 *
 * `label` 由服务端从 `description` 派生（`name（描述首段）`），与迁移前本文件所在目录
 * 原先硬编码的 `ENG_SKILL_OPTIONS` 文案逐字一致——因此前端不再需要任何技能目录副本。
 */
export interface SkillPackageView {
  /** 技能标签（= required_skills 命中的键）。 */
  name: string
  version: string
  description: string
  requiredTools: string[]
  dependencies: string[]
  inputSchema: Record<string, unknown>
  outputSchema: Record<string, unknown>
  validationRules: string[]
  /** 定义文件名（由后端目录扫描推导）。 */
  fileName: string
  /** 显示名（服务端派生）。 */
  label: string
  /** 是否为不可用技能包（frontmatter 缺失/非法）。 */
  corrupt: boolean
  /** 不可用原因；健康时为 null。 */
  error: string | null
}

export const skillApi = {
  /** 技能包目录（含 corrupt 项）。调用方须自行处理失败（见 stores/skillCatalog）。 */
  catalog: () => request.get<any, SkillPackageView[]>(paths.skills.catalog)
}
