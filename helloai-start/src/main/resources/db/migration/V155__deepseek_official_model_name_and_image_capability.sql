-- ============================================================
-- V155__deepseek_official_model_name_and_image_capability.sql
-- 用途：两件同批订正（同一 provider、同一次真机验证发现，合并为一条迁移）
--   ① 模型名对齐官方文档：`deepseek-v4-flash` → `deepseek-flash`
--   ② `capabilities.input` 移除 `image`（fail-close 止血）
-- ------------------------------------------------------------
-- ① 命名订正的依据（2026-11-25 用户提供 DeepSeek 官方《模型细节》文档）：
--   官方现行模型名只有两个：`deepseek-flash`（模型版本 DeepSeek-V4.1-Flash-1017）
--   与 `deepseek-v4-pro`（DeepSeek-V4-Pro-0813）。
--   `deepseek-v4-flash` 是**目录里的废弃别名**——V109:17 早已留痕
--   「deepseek-v4-flash 在 models.dev 已标 deprecated，本批不改默认位」，
--   即此前就欠一次默认位迁移，本次一并清偿。
--   直接证据：同一 key 直连 `api.deepseek.com` 请求 `model=deepseek-v4-flash`，
--   回包 `"model":"deepseek-flash"` —— 官方侧把它解析成 `deepseek-flash`，
--   说明两者是同一模型的两个名字，别名随时可能失效。
--   行内既有数据：`deepseek-flash`（id=15，sort_order=110）与
--   `deepseek-v4-flash`（id=1，sort_order=10，**当前默认位**）两行并存，
--   故本迁移是「默认位转移 + 旧别名退役」，不是插入新行。
-- ------------------------------------------------------------
-- ② 能力订正的依据（2026-11-25 真机四模型对照实验，同图同题）：
--   图片通道在 deepseek 上是**静默失效**的，不是 provider 不支持：
--   · 同一张图（图上 6 位数字 530872 为唯一答案）：
--       minimax:MiniMax-M3       => 530872（正确）
--       dashscope:qwen3.7-plus   => 530872（正确）
--       moonshot:kimi-k3         => 530972（OCR 误差；直连官方接口可复现，非平台问题）
--       deepseek:deepseek-v4-flash => 「我看不到这张图片的实际内容」
--   · 用同一 key 直连 `api.deepseek.com` 按 OpenAI 兼容协议发 `image_url`
--       => 正确返回 530872；官方文档亦标注 `deepseek-flash` 的「图像理解 = 支持」
--       ⇒ **模型本身能读图**。
--   · 根因在 SDK 层：`spring-ai-deepseek:1.1.8` 的
--       `DeepSeekChatModel#createRequest`（源码 436~472 行）对 USER 消息只构造
--       `new ChatCompletionMessage(message.getText(), Role.USER)`，**完全没有 media 分支**，
--       图片被静默丢弃；而 `spring-ai-openai` 的 `OpenAiChatModel`（600 行）有
--       `mapToMediaContent` 映射。本项目 deepseek 走专用 SDK 工厂
--       （`DeepSeekProviderChatClientFactory`），故只有它受影响。
-- ------------------------------------------------------------
-- ★为什么「官方说支持图像」却要声明 text-only —— 这不是自相矛盾，是 fail-close：
--   `capabilities.input` 在本系统的语义是「经本平台此 provider **实际可达**的输入模态」
--   （`ImageInputCapabilityPolicy` 直接问它，据此决定送不送图）。
--   若照官方声明 `image`：能力闸放行 → 送图 → SDK 静默丢弃 → provider 不报错 →
--   模型收到清单文案「图像内容已另经图像通道送入」却拿不到像素 ⇒
--   只能诚实说看不到、或按文件名**编造**图片内容（后者用户无法识别，违 I-4「禁假装看过」）。
--   声明 text-only 后：能力闸拦下 → 不送图 → 显式追加
--   `ImageInputCapabilityPolicy.MEDIA_NOT_VISIBLE_NOTE`（明确告知看不到 + 禁止凭文件名推测）。
--   两者都是「看不到」，但后者是**可知情的失败**，前者是**不可知情的静默失败**。
-- ------------------------------------------------------------
-- ★恢复条件（写在这里，避免后人误以为是模型能力退化）：
--   修 `DeepSeekProviderChatClientFactory` 使其能把 media 装配进请求
--   （改走 OpenAI 兼容工厂 / 自建带 image_url 的 ChatModel），
--   并补一条增量迁移把 `image` 加回 `input`。届时用同一张写数字的图做对照实验复验。
-- ------------------------------------------------------------
-- ★与 V110 头部「已知口径冲突」的关系：
--   V110 留痕的是 `deepseek-v4-pro`（models.dev=text-only，用户口径称含 image），
--   本次**不动它**（官方文档证实 pro 确不支持图像理解，快照值正确）。
--   本次订正的是 `deepseek-v4-flash`/`deepseek-flash` 两行——其 models.dev 值
--   确实含 image（元数据没错），错在**本平台的传输实现**。
-- ------------------------------------------------------------
-- 幂等性：全部为条件 UPDATE；重复执行结果不变。
-- ============================================================

-- ============================================================
-- 1) 默认位让出：`deepseek-v4-flash` 退役（先降，避免撞部分唯一索引
--    uk_provider_model_active / idx_provider_model_default）
-- ============================================================
UPDATE llm_provider_model
SET is_default = 0,
    enabled = 0,
    update_by = 'V155-deepseek-official-name'
WHERE deleted = 0
  AND provider_code = 'deepseek'
  AND model_name = 'deepseek-v4-flash'
  AND (is_default = 1 OR enabled = 1);

-- ============================================================
-- 2) 默认位接管：`deepseek-flash` 升为默认位（官方名）
--    防御性补齐：若历史环境没有该行则插入（V109 已种，此处仅兜手工删过的场景）
-- ============================================================
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order,
                                capabilities, create_by, update_by)
SELECT (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + 1,
       p.id, p.provider_code, 'deepseek-flash', 0, 1, 110,
       '{"input":["text"],"output":["text"],"reasoning":true,"toolCalling":true,"webSearch":false}'::jsonb,
       'system', 'V155-deepseek-official-name'
FROM llm_provider p
WHERE p.provider_code = 'deepseek' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM llm_provider_model m
      WHERE m.provider_id = p.id AND m.model_name = 'deepseek-flash' AND m.deleted = 0)
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

UPDATE llm_provider_model
SET is_default = 1,
    enabled = 1,
    sort_order = 10,
    update_by = 'V155-deepseek-official-name'
WHERE deleted = 0
  AND provider_code = 'deepseek'
  AND model_name = 'deepseek-flash'
  AND (is_default = 0 OR enabled = 0 OR sort_order <> 10);

-- ============================================================
-- 3) provider 级默认模型对齐（与目录 is_default 单一事实来源，同 V61/V101 口径）
-- ============================================================
UPDATE llm_provider
SET default_model = 'deepseek-flash',
    update_by = 'V155-deepseek-official-name'
WHERE provider_code = 'deepseek'
  AND deleted = 0
  AND (default_model IS NULL OR default_model IN ('deepseek-v4-flash', 'deepseek-chat'));

-- ============================================================
-- 4) 能力订正：deepseek 全系移除 image（fail-close，理由见头部 ②）
--    注意 pro 本就是 ["text"]，此处仅在含 image 时改写（当前环境 0 行命中也是正常）
-- ============================================================
UPDATE llm_provider_model
SET capabilities = jsonb_set(capabilities, '{input}', '["text"]'::jsonb),
    update_by = 'V155-deepseek-official-name'
WHERE deleted = 0
  AND provider_code = 'deepseek'
  AND capabilities -> 'input' @> '["image"]'::jsonb;

-- ============================================================
-- 5) 存量引用改写：把钉在退役别名上的会话/Agent 指向官方名
--    为什么改：`PlannerAgentPicker#validateModelUsable` 要求
--    `isModelAvailable`（enabled=1）——旧别名已 enabled=0，
--    不改写的会话下次发消息会直接抛「指定的模型不存在或已停用」。
--    为什么安全：两者是同一模型的两个名字（官方回包已证），
--    改写不改推理行为，只去掉已失效的别名。
--    Agent 侧：V61 口径是「不动存量 Agent 配置、交人工核实」；
--    但此处别名已**不可用**（非仅「不推荐」），不改会留下必然 403/400 的 Agent，
--    故一并改写——与 V61 的差异已在此说明。
-- ============================================================
UPDATE requirement_conversation
SET model_type = 'deepseek:deepseek-flash',
    update_by = 'V155-deepseek-official-name'
WHERE deleted = 0
  AND model_type = 'deepseek:deepseek-v4-flash';

UPDATE agent
SET model_type = 'deepseek:deepseek-flash',
    update_by = 'V155-deepseek-official-name'
WHERE deleted = 0
  AND model_type = 'deepseek:deepseek-v4-flash';

-- ============================================================
-- 断言
-- ============================================================

-- 断言①：deepseek 默认位唯一且指向官方名
DO $$
DECLARE
    default_cnt  INTEGER;
    default_name TEXT;
BEGIN
    SELECT count(*), max(model_name) INTO default_cnt, default_name
    FROM llm_provider_model
    WHERE deleted = 0 AND provider_code = 'deepseek' AND is_default = 1;

    IF default_cnt <> 1 THEN
        RAISE EXCEPTION 'V155 断言①失败：deepseek 默认位应恰好 1 行，实际 % 行', default_cnt;
    END IF;
    IF default_name <> 'deepseek-flash' THEN
        RAISE EXCEPTION 'V155 断言①失败：deepseek 默认位应为 deepseek-flash，实际 %', default_name;
    END IF;
END $$;

-- 断言②：旧别名不得仍处于启用态（否则「两条路径都活着」会继续误导选择器）
DO $$
DECLARE
    alias_enabled INTEGER;
BEGIN
    SELECT count(*) INTO alias_enabled
    FROM llm_provider_model
    WHERE deleted = 0 AND provider_code = 'deepseek'
      AND model_name = 'deepseek-v4-flash' AND enabled = 1;
    IF alias_enabled > 0 THEN
        RAISE EXCEPTION 'V155 断言②失败：废弃别名 deepseek-v4-flash 仍 enabled=1';
    END IF;
END $$;

-- 断言③：deepseek 全系不得再声明 image（防步骤 4 的 WHERE 因数据形态差异静默 0 行）
DO $$
DECLARE
    offending INTEGER;
BEGIN
    SELECT count(*) INTO offending
    FROM llm_provider_model
    WHERE deleted = 0 AND provider_code = 'deepseek'
      AND capabilities -> 'input' @> '["image"]'::jsonb;
    IF offending > 0 THEN
        RAISE EXCEPTION 'V155 断言③失败：仍有 % 行 deepseek 模型声明 image 输入', offending;
    END IF;
END $$;

-- 断言④：其余三家（真机验证通道**可用**的 provider）必须仍声明 image，
--         防步骤 4 的 WHERE 写得过宽而误伤。
--         这同时是一条回归哨兵：若哪天有人「一刀切」把 image 全删了，这里会红。
DO $$
DECLARE
    ok_count INTEGER;
BEGIN
    SELECT count(DISTINCT provider_code) INTO ok_count
    FROM llm_provider_model
    WHERE deleted = 0
      AND provider_code IN ('moonshot', 'minimax', 'dashscope')
      AND capabilities -> 'input' @> '["image"]'::jsonb;
    IF ok_count <> 3 THEN
        RAISE EXCEPTION 'V155 断言④失败：moonshot/minimax/dashscope 中仅 % 家仍声明 image（期望 3）', ok_count;
    END IF;
END $$;

-- 断言⑤：存量不得留有任何指向退役别名的会话 / Agent
DO $$
DECLARE
    leftover INTEGER;
BEGIN
    SELECT (SELECT count(*) FROM requirement_conversation
             WHERE deleted = 0 AND model_type = 'deepseek:deepseek-v4-flash')
         + (SELECT count(*) FROM agent
             WHERE deleted = 0 AND model_type = 'deepseek:deepseek-v4-flash')
      INTO leftover;
    IF leftover > 0 THEN
        RAISE EXCEPTION 'V155 断言⑤失败：仍有 % 处引用已退役别名 deepseek:deepseek-v4-flash', leftover;
    END IF;
END $$;