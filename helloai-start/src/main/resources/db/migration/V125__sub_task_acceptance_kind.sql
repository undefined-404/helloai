-- V125__sub_task_acceptance_kind.sql
-- 目的：任务拆解领域无关化 TD-2 —— 子任务级新增「验收判据」维度。
--
-- 背景：sub_task.acceptance 是自由文本。代码场景可以写「运行 X 命令输出含 Y」，
--   非代码场景只能编「质量良好」这类无法验证的表述。本列显式声明「谁来判、怎么判」，
--   把「验收假可判定」挡在落库之前（design §4）。
--
-- 值域（与 com.helloai.common.constant.AcceptanceKind 一一对应）：
--   AUTOMATED  机器判 —— 代码 / 配置 / 数据文件；acceptance 须含命令或断言 + 预期结果
--   STRUCTURAL 规则+机器判 —— 文档 / 报告 / 清单；须为可数结构断言（章节数 / 条目数 / 字数下限）
--   REVIEWED   审查 agent 判 —— 方案 / 设计 / 分析；须为可逐条打勾的审查清单（≥2 条独立检查项）
--   ATTESTED   人判 —— 决策 / 创意 / 对外沟通 / 现场动作；须写明「谁 / 什么时点 / 留什么痕」
--
-- 存量语义：默认 'AUTOMATED'，等于改造前的行为（既有 VERIFICATION 围栏口径）。
--
-- 回滚：ALTER TABLE sub_task DROP COLUMN acceptance_kind;
--
-- 注：本列不参与依赖图（Kahn 拓扑与环检测不受影响，design §7 第 2 条）；
--   不做「按 acceptance 文本自动推断 kind」——推断错会让校验走错分支。

ALTER TABLE sub_task
    ADD COLUMN acceptance_kind VARCHAR(16) NOT NULL DEFAULT 'AUTOMATED';

ALTER TABLE sub_task
    ADD CONSTRAINT chk_sub_task_acceptance_kind
        CHECK (acceptance_kind IN ('AUTOMATED', 'STRUCTURAL', 'REVIEWED', 'ATTESTED'));

COMMENT ON COLUMN sub_task.acceptance_kind IS
    '验收判据：AUTOMATED（机器）/ STRUCTURAL（规则+机器）/ REVIEWED（审查 agent）/ ATTESTED（人）。'
    '默认 AUTOMATED = 改造前行为。由拆解侧或人工显式声明，不从 acceptance 文本推断。';
