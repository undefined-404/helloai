-- V124__task_archetype.sql
-- 目的：任务拆解领域无关化 TD-1 —— task 级新增「交付物原型」维度。
--
-- 背景：当前拆解只有 GRANULARITY（FINE/STANDARD/COARSE）一个维度，它只控制「拆多细」，
--   不控制「按什么维度拆」；planner-decompose.md 的拆解原则整段是软件交付语法
--   （契约先行 / 接口签名 / 「运行 X 命令输出含 Y」），非代码任务无从下手。
--   本列把「按什么维度拆」「终止锚在哪」变成显式声明（design §2 / §3）。
--
-- 值域（与 com.helloai.common.constant.TaskArchetype 一一对应）：
--   BUILD    覆盖所有改动点 / 编译·测试·lint / 模块分层 + 契约先行
--   DOCUMENT 覆盖读者决策链 / 结构可检查 + 审查 / 读者问题序
--   RESEARCH 子问题可独立佐证或推翻 / 证据充分性 / 问题分解树
--   DATA     数据流每阶段校验通过 / 行数·分布·对账 / 采集→清洗→校验→加载→复验
--   OPERATE  时间轴节点全部触发且留痕 / 可观测结果 / 前置→触发→监控→复盘
--   DECIDE   选项穷尽 + 维度覆盖 / 决策记录（ADR 式）/ 选项→评估→推荐
--
-- 存量语义：默认 'BUILD'，等于改造前的行为（零行为变化）。不做数据回填——
--   历史任务一律 BUILD 是刻意口径，不会自动「变正确」（plan §0 ④）。
--
-- 回滚：ALTER TABLE task DROP COLUMN task_archetype;（整列独立，无其他列依赖）
--
-- 注：本迁移只建列，不参与任何查询路径；按 archetype 的检索当前不存在，
--   六值低基数故不加索引（plan §3 TD-1「不做的」）。

ALTER TABLE task
    ADD COLUMN task_archetype VARCHAR(16) NOT NULL DEFAULT 'BUILD';

ALTER TABLE task
    ADD CONSTRAINT chk_task_archetype
        CHECK (task_archetype IN ('BUILD', 'DOCUMENT', 'RESEARCH', 'DATA', 'OPERATE', 'DECIDE'));

COMMENT ON COLUMN task.task_archetype IS
    '交付物原型（拆解维度）：BUILD / DOCUMENT / RESEARCH / DATA / OPERATE / DECIDE；默认 BUILD = 改造前行为。'
    '判定时机为澄清终稿产出（不在拆解时判），判不出回落 BUILD，人工可改。';
