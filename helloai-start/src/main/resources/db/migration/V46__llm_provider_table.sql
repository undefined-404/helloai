-- =========================================================
-- V46：LLM Provider 动态化（方案B）
--   - 新增 llm_provider 表：所有 LLM Provider 配置（已生效 / 内置 / 自定义）统一收口
--   - 启动期把现有 4 家 yml provider 一次性 INSERT 为 builtin 记录
--   - 旧 sys_config["llm.provider.<name>.base-url"] / [.default-model] 保留作为兜底
--     （仅在 llm_provider 中无对应配置时回退，先到先得）
--   - 字段命名遵循 V23 规范化规则：xxx_time / xxx_id / xxx_count
-- =========================================================

CREATE TABLE IF NOT EXISTS llm_provider (
    id              BIGINT NOT NULL,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255) DEFAULT NULL,
    -- 业务字段
    provider_code   VARCHAR(64) NOT NULL,         -- 唯一标识，如 deepseek / moonshot / custom-gpt-4
    provider_name   VARCHAR(128) NOT NULL,        -- 显示名，如 "DeepSeek"、"我的 OpenAI"
    protocol_type   VARCHAR(32) NOT NULL,         -- OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE
    base_url        VARCHAR(255) NOT NULL,        -- API Base URL
    default_model   VARCHAR(128) DEFAULT NULL,    -- 默认模型，可空
    enabled         SMALLINT NOT NULL DEFAULT 1,  -- 启用 / 禁用
    builtin         SMALLINT NOT NULL DEFAULT 0,  -- 是否内置（不可删除）
    sort_order      INT NOT NULL DEFAULT 0,       -- 列表排序
    extra_config    JSONB DEFAULT NULL,           -- 扩展配置（completionsPath / messagesPath）
    CONSTRAINT pk_llm_provider PRIMARY KEY (id),
    CONSTRAINT uk_llm_provider_code UNIQUE (provider_code)
);

CREATE INDEX IF NOT EXISTS idx_llm_provider_enabled
    ON llm_provider(enabled) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_llm_provider_update_time ON llm_provider;
CREATE TRIGGER update_llm_provider_update_time BEFORE UPDATE ON llm_provider
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 种子数据：4 家内置 provider
-- 用途：原 application.yml 中 helloai.providers.deepseek/moonshot/dashscope/minimax
--       改为 DB 驱动，迁移首期一次性 INSERT 进去（ON CONFLICT 幂等）；
--       - deepseek 走官方 SDK（DeepSeekApi），兼容协议类型仍标 OPENAI_COMPATIBLE
--       - moonshot / dashscope 走 OpenAI 兼容端点
--       - minimax 走 Anthropic 兼容端点
INSERT INTO llm_provider
    (id, provider_code, provider_name, protocol_type, base_url, default_model, enabled, builtin, sort_order, create_by, update_by)
VALUES
    (1, 'deepseek',  'DeepSeek',             'OPENAI_COMPATIBLE',   'https://api.deepseek.com',                     'deepseek-chat',    1, 1, 10, 'system', 'system'),
    (2, 'moonshot',  'Moonshot (Kimi)',      'OPENAI_COMPATIBLE',   'https://api.moonshot.cn',                      'moonshot-v1-8k',   1, 1, 20, 'system', 'system'),
    (3, 'minimax',   'MiniMax',              'ANTHROPIC_COMPATIBLE','https://api.minimaxi.com/anthropic',           'MiniMax-M2.5',     1, 1, 30, 'system', 'system'),
    (4, 'dashscope', 'DashScope (通义千问)', 'OPENAI_COMPATIBLE',   'https://dashscope.aliyuncs.com/compatible-mode','qwen-plus',       1, 1, 40, 'system', 'system')
ON CONFLICT (provider_code) DO NOTHING;

-- 字段注释（PG 不支持 COMMENT ON COLUMN 在 IF NOT EXISTS 之后重复添加，先做一次注释）
COMMENT ON COLUMN llm_provider.provider_code IS '唯一标识（全小写、字母数字中划线、2-64 字符）';
COMMENT ON COLUMN llm_provider.provider_name IS '显示名（管理后台列表与下拉框展示）';
COMMENT ON COLUMN llm_provider.protocol_type IS '协议类型：OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE';
COMMENT ON COLUMN llm_provider.base_url IS 'API Base URL（OpenAiApi 在其后拼接 /v1/chat/completions；AnthropicApi 在其后拼接 /v1/messages）';
COMMENT ON COLUMN llm_provider.default_model IS '默认模型；空时由调用方显式传入';
COMMENT ON COLUMN llm_provider.enabled IS '启用 / 禁用：1=启用、0=禁用（禁用后目录与 Agent 注册下拉不展示）';
COMMENT ON COLUMN llm_provider.builtin IS '是否内置：1=内置（不可删除、不可改 code）；0=自定义（可删可改）';
COMMENT ON COLUMN llm_provider.sort_order IS '列表排序（数值越小越靠前）';
COMMENT ON COLUMN llm_provider.extra_config IS '扩展配置（JSONB）：如 completionsPath / messagesPath 等';

-- 验证
DO $$
BEGIN
    RAISE NOTICE 'V46 完成: llm_provider 表已创建，% 条 builtin 记录',
        (SELECT COUNT(*) FROM llm_provider WHERE deleted = 0 AND builtin = 1);
END $$;
