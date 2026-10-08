// V47 技能规范标签选项（多选下拉用）
// 与后端词表对齐：AgentSkillDeriver.KEYWORD_SKILLS / SkillNormalizer.SYNONYMS 的规范标签
// （shell / docker / sql / web-search / code-review / python / java / thinking），允许自定义技能
// （如 kubernetes / golang），匹配时大小写不敏感、首尾空白忽略。
// V52: thinking（深度思考/推理）为模型能力锁定技能，通常由后端 capabilitySkills 自动并入，不在下拉中重复可选。
export const AGENT_SKILL_OPTIONS = [
  { label: 'shell（脚本/命令行）', value: 'shell' },
  { label: 'docker（容器）', value: 'docker' },
  { label: 'sql（数据库）', value: 'sql' },
  { label: 'web-search（联网检索）', value: 'web-search' },
  { label: 'code-review（代码审查）', value: 'code-review' },
  { label: 'python', value: 'python' },
  { label: 'java', value: 'java' },
  { label: 'thinking（深度思考/推理）', value: 'thinking' },
] as const

// 平台 eng-* 规范技能目录（与后端 AgentSkillSpecServiceImpl.KNOWN_SPECS 对齐）
// 任务 required_skills 中由 Planner 按「平台技能目录」指派的规范标签；description 取自后端
// SkillPackage.description（中文）。前端渲染 requiredSkills 时经 skillLabelOf 映射为中文。
export const ENG_SKILL_OPTIONS = [
  { label: 'eng-code-review（代码评审规范）', value: 'eng-code-review' },
  { label: 'eng-doc-standard（文档规范）', value: 'eng-doc-standard' },
  { label: 'eng-verification（验证规范）', value: 'eng-verification' },
  { label: 'eng-web-research（联网调研规范）', value: 'eng-web-research' },
] as const

/** 技能标签 → 中文显示名（能力声明 ∪ eng-* 规范目录）；未命中回退原文，兼容自定义技能。 */
export function skillLabelOf(s: string | null | undefined): string {
  if (!s) return ''
  return (
    AGENT_SKILL_OPTIONS.find(o => o.value === s)?.label
    ?? ENG_SKILL_OPTIONS.find(o => o.value === s)?.label
    ?? s
  )
}
