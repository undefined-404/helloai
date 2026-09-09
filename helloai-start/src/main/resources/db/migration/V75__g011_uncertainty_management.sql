-- ============================================================
-- V75__g011_uncertainty_management.sql
-- 用途：需求包准入与不确定性显式管理（G-011）—— 子任务不确定性申报 + 澄清终稿需求包
-- 背景：
--   准入侧+契约侧深化批次：澄清终稿从纯文本升级为结构化需求包
--   （goal/scope/outOfScope/assumptions/openQuestions），拆解时把推断与缺口
--   显式继承为 sub_task.uncertainties（kind=ASSUMPTION 已申报假设 /
--   UNCONFIRMED 待确认缺口），执行侧分级消费（验证动作指引）、审查侧分级核验
--   （假设存在不构成驳回，未验证缺口按不达标）。
--   - sub_task.uncertainties：JSONB 数组 [{kind, note}]，拆解侧指派，
--     执行/审查侧按 kind 分级语义消费；默认 '[]'：存量数据执行/审查零注入，
--     行为与现状完全一致
--   - requirement_conversation.final_package：JSONB，澄清终稿需求包权威存储
--     （终稿轮覆盖写，同 final_title/final_description 模式）；终稿确认建任务时
--     双写 task.context.requirementPackage 供拆解链读取；NULL：存量会话 /
--     未走澄清链路的直建任务无需求包，拆解渲染占位文案，行为等于现状
-- 机制：
--   - 默认 '[]' / NULL：全链行为零变化
--   - 暂不加索引（数据量小，仅在澄清 / 拆解 / 执行 / 审查侧按行读取）
-- 参考：doc/design/Requirement_Package_Uncertainty.md（D1 / D2 / D3）
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS uncertainties JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS final_package JSONB;

COMMENT ON COLUMN sub_task.uncertainties IS '不确定性申报（JSONB 数组：kind=ASSUMPTION 已申报假设/UNCONFIRMED 待确认缺口；拆解侧指派，执行/审查侧分级消费）';
COMMENT ON COLUMN requirement_conversation.final_package IS '澄清终稿结构化需求包（goal/scope/outOfScope/assumptions/openQuestions；终稿轮覆盖写，建任务时双写 task.context.requirementPackage）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V75] sub_task.uncertainties / requirement_conversation.final_package 列已就绪';
END $$;