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

// 【2026-10-09 REF-1.2c 改造】本文件原先还导出 ENG_SKILL_OPTIONS（平台 eng-* 目录对齐副本）
// 与 skillLabelOf（标签→中文名）。技能元数据改为 md frontmatter + 服务端下发后，那份副本必然
// 漂移（新增技能会出现「后端生效、前端看不见」），已删除：
//   · 平台技能目录 → `stores/skillCatalog.ts`（懒加载 /api/skills/catalog）
//   · 标签显示名   → `useSkillCatalogStore().labelOf()`（能力词表 ∪ 目录，未命中回退原文）
// 本文件只保留 AGENT_SKILL_OPTIONS —— 它是**能力词表**（对齐后端 AgentSkillDeriver.KEYWORD_SKILLS
// 与 SkillNormalizer.SYNONYMS），不是可枚举的技能目录，故不适合下发，按裁定归 parity 守卫。

