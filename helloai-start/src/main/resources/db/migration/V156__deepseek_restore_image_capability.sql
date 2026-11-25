-- ============================================================
-- V156__deepseek_restore_image_capability.sql
-- 用途：把 `image` 加回 `deepseek-flash` 的 capabilities.input ——
--   反转 V155 第 4 段 + 断言③ 的 text-only 宣告（口径订正，理由见下）。
-- ------------------------------------------------------------
-- ★口径订正（2026-11-25 方案 A 落地，V155 的 fail-close 止血使命完成）：
--   V155 把 deepseek 全系 capabilities.input 移除 `image`，是**传输层失效**下的 fail-close：
--   专用 SDK（spring-ai-deepseek:1.1.8）的 DeepSeekChatModel#createRequest 对 USER 消息
--   没有 media 分支 ⇒ 送图会被**静默丢弃**（provider 不报错）⇒ 声明 image 反而是
--   「能力闸放行 → 送图 → 模型拿不到像素」的不可知情失败（违 I-4「禁假装看过」）。
--   V155 头部注释写明恢复条件：修 DeepSeekProviderChatClientFactory 使其能装配 media
--  （改走 OpenAI 兼容工厂等），并补增量迁移把 image 加回 input。
--   ——本迁移即该恢复条件的第二条；第一条已由代码落地：
--   `LlmProviderChatClientFactoryRegistry` 不再路由专用 SDK 工厂（已 @Deprecated），
--   deepseek（protocol_type=OPENAI_COMPATIBLE）自然落入 `OpenAiCompatibleProtocolFactory`，
--   OpenAiChatModel 的 mapToMediaContent 可正常装配图片。故传输层失效已解除。
-- ------------------------------------------------------------
-- ★为什么只给 `deepseek-flash` 加回、不给 `deepseek-v4-pro` 加：
--   官方《模型细节》文档（2026-11-25 用户提供）：deepseek-flash 图像理解 = 支持；
--   **deepseek-v4-pro 图像理解 = 不支持**。V155 头部亦留痕「pro 本就是 ["text"]，
--   本次不动它（官方文档证实 pro 确不支持图像理解）」——本迁移保持该口径。
-- ------------------------------------------------------------
-- ★方案 A 的代价（写在这里，避免误以为是能力退化）：
--   OpenAI 兼容通道（spring-ai-openai）响应侧不解析 DeepSeek 的 reasoning_content
--  ⇒ 丢失 thinking 展示；正文与工具调用不受影响。
-- ------------------------------------------------------------
-- ★真机复验（落地后置动作，与 V155 头部「对照实验」口径一致）：
--   用同一张写数字的图（V155 四模型对照实验的 530872 同款）发给 deepseek:deepseek-flash，
--   必须答出图中数字；并核对日志不再出现「模型不支持图片输入…降级」文案。
-- ------------------------------------------------------------
-- ★与 V155 断言③的关系：V155 已 apply（2026-11-25 11:54），Flyway 不会重跑其断言，
--   故本迁移不会与它冲突；文档口径上，本迁移是有意撤销 V155 第 4 段 + 断言③ 的 text-only 宣告
--  （本文件头部即为「口径订正」存档），后人勿再以 V155 断言③ 为据把 deepseek 判为 text-only。
-- ------------------------------------------------------------
-- 幂等性：条件 UPDATE，重复执行结果不变；断言为「终态必须成立」式，天然幂等。
-- ============================================================

-- ============================================================
-- 1) `deepseek-flash` 加回 image（官方文档确认支持图像理解）
--    保持 text 首位，追加 image；其余能力键（output/reasoning/toolCalling/webSearch）不动
-- ============================================================
UPDATE llm_provider_model
SET capabilities = jsonb_set(capabilities, '{input}', '["text","image"]'::jsonb),
    update_by = 'V156-deepseek-restore-image'
WHERE deleted = 0
  AND provider_code = 'deepseek'
  AND model_name = 'deepseek-flash'
  AND NOT (capabilities -> 'input' @> '["image"]'::jsonb);

-- ============================================================
-- 断言
-- ============================================================

-- 断言①：deepseek-flash 必须声明 image（防步骤 1 的 WHERE 因数据形态差异静默 0 行——
--         即「恢复没生效却以为生效」的假绿）
DO $$
DECLARE
    flash_ok INTEGER;
BEGIN
    SELECT count(*) INTO flash_ok
    FROM llm_provider_model
    WHERE deleted = 0 AND provider_code = 'deepseek' AND model_name = 'deepseek-flash'
      AND capabilities -> 'input' @> '["image"]'::jsonb;
    IF flash_ok <> 1 THEN
        RAISE EXCEPTION 'V156 断言①失败：deepseek-flash 应恰好 1 行声明 image，实际 % 行', flash_ok;
    END IF;
END $$;

-- 断言②：deepseek-v4-pro 不得声明 image（官方文档确认 pro 不支持图像理解，
--         防「一刀切恢复」把表达能力不足的模型也放开）
DO $$
DECLARE
    pro_offending INTEGER;
BEGIN
    SELECT count(*) INTO pro_offending
    FROM llm_provider_model
    WHERE deleted = 0 AND provider_code = 'deepseek' AND model_name = 'deepseek-v4-pro'
      AND capabilities -> 'input' @> '["image"]'::jsonb;
    IF pro_offending > 0 THEN
        RAISE EXCEPTION 'V156 断言②失败：deepseek-v4-pro 不应声明 image（官方不支持），实际 % 行', pro_offending;
    END IF;
END $$;

-- 断言③：其余三家（真机验证通道可用的 provider）必须仍声明 image，
--         与 V155 断言④同款反向哨兵——防步骤 1 的 provider_code 写错范围而误伤无关模型
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
        RAISE EXCEPTION 'V156 断言③失败：moonshot/minimax/dashscope 中仅 % 家仍声明 image（期望 3）', ok_count;
    END IF;
END $$;