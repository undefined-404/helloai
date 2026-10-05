-- ============================================================
-- V101: LLM Provider 默认模型订正（P3-1：默认值落在停用模型上）
-- ------------------------------------------------------------
-- 背景：GET /api/admin/agents/listLlmProviders（AdminAgentController）直接返回
--   llm_provider.default_model；该列优先于 sys_config / yml，且 Agent 注册校验
--   （AgentSkillPolicyService.validateModelType）与 LLM 连通性校验均以其为准。
--   V46 种子遗留两处已不在启用模型列表内的默认值，导致前端下拉预选一个不可注册
--   的模型、注册直接被拒（与 V61 moonshot 下线订正同类，处置口径一致）：
--     - deepseek : 'deepseek-chat'  —— 目录内已无该模型，'deepseek:deepseek-chat' 注册被拒；
--                  正确目标 = 目录默认位 deepseek-v4-flash（is_default=1, enabled=1，亦为
--                  线上 inner-deepseek-flash-excutor 实际在用模型）
--     - dashscope: 'qwen-plus'      —— 目录内已无该模型；正确目标 = 目录默认位 qwen3.7-plus
--                  （is_default=1, enabled=1）
-- 处置口径（与 V61 一致）：provider 级 default_model 与 llm_provider_model.is_default 保持
--   一致（单一事实来源）。本处两 provider 的目标模型**本就已是目录默认位**，故只需订正
--   provider 级列，无需改动目录 is_default（避免无谓的默认位漂移）。
-- 幂等：条件 UPDATE（仅在仍为旧值时覆盖），重复执行无副作用，不覆盖人工改过的值。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变，CODE_STYLE §16）。
-- ============================================================

-- 1) deepseek：'deepseek-chat'（目录已无）→ 'deepseek-v4-flash'（目录默认位）
UPDATE llm_provider
SET default_model = 'deepseek-v4-flash',
    update_by = 'V101-llm-provider-default-align'
WHERE provider_code = 'deepseek'
  AND deleted = 0
  AND default_model = 'deepseek-chat';

-- 2) dashscope：'qwen-plus'（目录已无）→ 'qwen3.7-plus'（目录默认位）
UPDATE llm_provider
SET default_model = 'qwen3.7-plus',
    update_by = 'V101-llm-provider-default-align'
WHERE provider_code = 'dashscope'
  AND deleted = 0
  AND default_model = 'qwen-plus';
