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
