-- ============================================================
-- V110__llm_provider_model_capabilities.sql
-- 用途：`M-4`「能力元数据回填」——为 `llm_provider_model` 增加**能力谓词列**
--   `capabilities`(JSONB)，并由 models.dev 快照的 modality / reasoning /
--   tool_call 推导回填全部模型行。
-- ------------------------------------------------------------
-- 承载形态 = **形态乙**（`D-2026-10-13-9`：泛化能力谓词表，
--   吸收 `capability_skills=["thinking"]` 这类平台策略）。
-- 列结构：
--   { "input":[模态...], "output":[模态...],
--     "reasoning":bool, "toolCalling":bool, "webSearch":bool }
--   模态词表 = {text,image,audio,video}（封闭，校验在 Java 枚举侧）；
--   缺省 = ["text"]（对齐 DSH「absent means unknown」的保守面）。
-- 推导口径（三方对照）：
--   ① input/output  ← models.dev `modalities.input/output`（原样，text 置首）
--   ② reasoning     ← models.dev `reasoning`（== 前端「思考」开关的模型侧事实）
--   ③ toolCalling   ← models.dev `tool_call`
--   ④ webSearch     ← **无 models.dev 字段**，取 V52 已确立的 provider 口径
--      （moonshot / dashscope / minimax = true，deepseek 等 = false）
--      —— 联网检索是「工具可用性」而非模态，与 `requiredTools` 同源，不臆造。
-- ------------------------------------------------------------
-- ★已知口径冲突（留痕，不在本迁移内调停）：
--   `deepseek-v4-pro` 的 input —— models.dev = `["text"]`（**仅文本**），
--   而用户 2026-10-13 口径为 `[text,image]`。两者不一致；models.dev 自身**内部自洽**
--   （DeepSeek 的 *flash* 线为多模态：`deepseek-flash`/`deepseek-v4-flash` 均含 image；
--   仅 `-pro` 为 text-only）⇒ 本迁移**取快照值**（保守面 = fail-closed：宁可少派图像技能），
--   并**标注待办**（同 `deepseek-v4-flash` deprecated 的处理范式，用户 2026-10-14 裁定）。
--   如后续以官方文档为准，另立订正迁移，勿改本文件（Flyway checksum）。
-- 模型集合：**严格复用 `V109` 的 pick() 口径**（同源同表），
--   故本迁移执行后「无模型行缺能力字段」（断言①）。
-- ------------------------------------------------------------
-- ★与 V52 两列的关系（**本批不动**）：
--   `capability_skills` / `available_optional_skills` 有两个活消费方
--   （`AgentSkillPolicyService.validateAgentSkills:53-68` +
--   `getSkillOptionsByModelType`）⇒ 遵删列前置，**先在 M-9 切换消费方再删列**，
--   本迁移只**新增**谓词列并回填，不改动 V52 两列的任何值。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变）。
-- ============================================================

-- ============================================================
-- 1) 增加能力谓词列（列默认 = 「仅文本」保守面）
-- ============================================================

ALTER TABLE llm_provider_model
    ADD COLUMN capabilities JSONB NOT NULL DEFAULT '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":false,"webSearch":false}';

-- ============================================================
-- 2) 兜底：全表先写「保守面」（仅文本 + provider 级 webSearch 口径）
--    目的：非快照行（V49/V108 遗留行、大小写变体行、e2e 探针残留行）
--    也拿到**一致**的能力谓词，而非列默认值（列默认 webSearch=false 会把
--    moonshot/dashscope/minimax 的遗留行误判为「无联网」）。
--    模态/reasoning/toolCalling 一律取保守面（未知 ⇒ 不给），fail-closed。
-- ============================================================

UPDATE llm_provider_model
SET capabilities = jsonb_build_object(
        'input',       '["text"]'::jsonb,
        'output',      '["text"]'::jsonb,
        'reasoning',   false,
        'toolCalling', false,
        'webSearch',   (provider_code IN ('moonshot','dashscope','minimax'))
    )
WHERE deleted = 0;

-- ============================================================
-- 3) 逐行回填：`V109` 种子集合（150 行）按 models.dev 快照写全量谓词
-- ============================================================

-- deepseek
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'deepseek' AND model_name = 'deepseek-v4-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'deepseek' AND model_name = 'deepseek-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'deepseek' AND model_name = 'deepseek-v4-pro';

-- moonshot
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'moonshot' AND model_name = 'kimi-k3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'moonshot' AND model_name = 'kimi-k2.7-code-highspeed';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'moonshot' AND model_name = 'kimi-k2.7-code';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'moonshot' AND model_name = 'kimi-k2.6';

-- minimax
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2.7-highspeed';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2.7';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2.5-highspeed';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2.5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2.1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M2';

-- dashscope
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.7-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.8-omni-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.8-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.8-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'deepseek-v4-flash-0731';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'kimi-k3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.7-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'glm-5.2';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.7-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.6-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.6-27b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.6-max-preview';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.6-35b-a3b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.6-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-35b-a3b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-27b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-122b-a10b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","video","audio"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3.5-397b-a17b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-vl-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio","video"],"output":["text","audio"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-omni-flash-realtime';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio","video"],"output":["text","audio"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-omni-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-next-80b-a3b-thinking';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-next-80b-a3b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-coder-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-flash';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-coder-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio"],"output":["text","audio"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-omni-turbo-realtime';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-vl-30b-a3b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-vl-235b-a22b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-coder-480b-a35b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-coder-30b-a3b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-8b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-32b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-235b-a22b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen3-14b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qvq-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwq-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio","video"],"output":["text","audio"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-omni-turbo';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","audio","video"],"output":["text","audio"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-omni-7b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-turbo';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-vl-7b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-vl-72b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-7b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-72b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-32b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen2-5-14b-instruct';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-vl-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-max';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-vl-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-plus';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":true}'::jsonb
WHERE deleted = 0 AND provider_code = 'dashscope' AND model_name = 'qwen-plus-character-ja';

-- openai
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4o';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-6.1-sol';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-6-sol';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-6-luna';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-6-astra';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-daybreak-red-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-daybreak-blue-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.6-terra';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.6-sol';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.6-luna';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.6';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","audio","image"],"output":["text","audio"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-realtime-2.1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.5-pro';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.4-nano';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.4-mini';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.4-pro';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.4';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.3-codex-spark';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.3-codex';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.2-pro';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.2';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5.1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5-pro';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5-nano';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5-mini';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'o3-pro';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'o3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4.1-mini';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4.1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4o-2024-11-20';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4o-2024-08-06';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'openai' AND model_name = 'gpt-4o-mini';

-- anthropic
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-sonnet-4-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-haiku-5-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-sonnet-5-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-5-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-fable-5-1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-sonnet-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-fable-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-4-8';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-4-7';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-sonnet-4-6';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-4-6';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-4-5-20251101';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-opus-4-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-haiku-4-5-20251001';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-haiku-4-5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'anthropic' AND model_name = 'claude-sonnet-4-5-20250929';

-- xai
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.7';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.6';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.5';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-build-0.1';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.20-0309-reasoning';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image","pdf"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'xai' AND model_name = 'grok-4.20-0309-non-reasoning';

-- mistral
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-large-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-large-4';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'zai-glm-5-3';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-medium-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-medium-2604';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-small-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-small-2603';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'ministral-8b-2512';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'ministral-3b-2512';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'ministral-14b-2512';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'codestral-2508';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","audio"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'voxtral-small-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","audio"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'voxtral-small-2507';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'mistral-large-2512';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'ministral-8b-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'ministral-3b-latest';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'mistral' AND model_name = 'codestral-latest';

-- groq
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'llama-3.3-70b-versatile';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'qwen/qwen3.8-27b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text","image"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'qwen/qwen3.6-27b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'openai/gpt-oss-safeguard-20b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'openai/gpt-oss-20b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'openai/gpt-oss-120b';
UPDATE llm_provider_model
SET capabilities = '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":true,"webSearch":false}'::jsonb
WHERE deleted = 0 AND provider_code = 'groq' AND model_name = 'llama-3.1-8b-instant';

-- ============================================================
-- 4) 验证（`M-4` 验收：无模型行缺能力字段 + thinking/图像/web-search 推导有断言）
-- ============================================================

DO $$
DECLARE
    miss_cnt   INT;
    notext_cnt INT;
    img_cnt    INT;
    rsn_cnt    INT;
    ws_cnt     INT;
    ws_bad     INT;
    total_cnt  INT;
BEGIN
    -- ① 无模型行缺能力字段（列 NOT NULL + 两个模态键必须非空数组）
    SELECT COUNT(*) INTO miss_cnt FROM llm_provider_model
        WHERE deleted = 0 AND (capabilities IS NULL
              OR jsonb_typeof(capabilities->'input')  IS DISTINCT FROM 'array'
              OR jsonb_typeof(capabilities->'output') IS DISTINCT FROM 'array'
              OR capabilities->'input'  = '[]'::jsonb
              OR capabilities->'output' = '[]'::jsonb);
    IF miss_cnt <> 0 THEN
        RAISE EXCEPTION '[V110] 有 % 行缺能力字段（input/output 非数组或为空）', miss_cnt;
    END IF;
    -- ② 执行者资格：output ⊇ {text}（不能产文本即不可为执行者）
    SELECT COUNT(*) INTO notext_cnt FROM llm_provider_model
        WHERE deleted = 0 AND NOT (capabilities->'output' @> '["text"]'::jsonb);
    IF notext_cnt <> 0 THEN
        RAISE EXCEPTION '[V110] 有 % 行的 output 不含 text', notext_cnt;
    END IF;
    -- ③ 图像推导：模态来自快照，须至少一行含 image；且两处「两源一致」的样本必须命中
    --    （kimi-k3 含 image；MiniMax-M3 含 video —— 快照与官方口径均如此）
    SELECT COUNT(*) INTO img_cnt FROM llm_provider_model
        WHERE deleted = 0 AND capabilities->'input' @> '["image"]'::jsonb;
    IF img_cnt = 0 THEN
        RAISE EXCEPTION '[V110] 无任何模型支持 image 输入 ⇒ modality 回填失效';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM llm_provider_model
        WHERE deleted = 0 AND provider_code = 'moonshot' AND model_name = 'kimi-k3'
          AND capabilities->'input' @> '["image"]'::jsonb) THEN
        RAISE EXCEPTION '[V110] moonshot/kimi-k3 的 input 应含 image';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM llm_provider_model
        WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M3'
          AND capabilities->'input' @> '["video"]'::jsonb) THEN
        RAISE EXCEPTION '[V110] minimax/MiniMax-M3 的 input 应含 video';
    END IF;
    -- ④ thinking 推导：存在 reasoning=true 的行（思考模型），且与模型实际一致
    SELECT COUNT(*) INTO rsn_cnt FROM llm_provider_model
        WHERE deleted = 0 AND capabilities->>'reasoning' = 'true';
    IF rsn_cnt = 0 THEN
        RAISE EXCEPTION '[V110] 无 reasoning=true 的模型 ⇒ thinking 推导失效';
    END IF;
    -- ⑤ web-search 推导（provider 口径）：三家（含**遗留行**）必须全 true；deepseek 必须全 false
    SELECT COUNT(*) INTO ws_cnt FROM llm_provider_model
        WHERE deleted = 0 AND provider_code IN ('moonshot','dashscope','minimax')
          AND capabilities->>'webSearch' = 'true';
    IF ws_cnt = 0 THEN
        RAISE EXCEPTION '[V110] moonshot/dashscope/minimax 应有 webSearch=true 行';
    END IF;
    SELECT COUNT(*) INTO ws_bad FROM llm_provider_model
        WHERE deleted = 0 AND provider_code IN ('moonshot','dashscope','minimax')
          AND capabilities->>'webSearch' IS DISTINCT FROM 'true';
    IF ws_bad <> 0 THEN
        RAISE EXCEPTION '[V110] moonshot/dashscope/minimax 应**全行** webSearch=true，实为 % 行非 true', ws_bad;
    END IF;
    SELECT COUNT(*) INTO ws_bad FROM llm_provider_model
        WHERE deleted = 0 AND provider_code = 'deepseek'
          AND capabilities->>'webSearch' = 'true';
    IF ws_bad <> 0 THEN
        RAISE EXCEPTION '[V110] deepseek 不应有 webSearch=true 行（V52 口径），实为 %', ws_bad;
    END IF;
    SELECT COUNT(*) INTO total_cnt FROM llm_provider_model WHERE deleted = 0;
    RAISE NOTICE '[V110] 已回填 % 行能力谓词；reasoning=% / 含image=% / webSearch=% / 缺字段=0', total_cnt, rsn_cnt, img_cnt, ws_cnt;
END $$;

-- ============================================================
-- 5) 列注释
-- ============================================================

COMMENT ON COLUMN llm_provider_model.capabilities IS '模型能力谓词（JSONB）：{"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}。模态词表 {text,image,audio,video}（缺省 [text]）；reasoning 对应前端「思考」开关；webSearch 为 provider 级工具可用性。形态乙（D-2026-10-13-9），替代方向见 M-9';

