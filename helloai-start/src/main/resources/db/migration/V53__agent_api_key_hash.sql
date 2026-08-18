-- ============================================================
-- Agent api_key 等保加固：api_key_hash 认证点查列（V53）
-- ============================================================
-- 背景：等保三级"数据保密性"要求 agent.api_key（consumerToken 工牌）
--       改为 AES-GCM 密文落库（enc:v1:<base64>）。AES-GCM 每次 nonce
--       随机，密文不可用 SQL eq 精确定位，故新增 SHA-256 哈希列：
--       认证先用 hash 点查（O(1)），命中后再解密比对防碰撞。
-- 存量明文：本脚本尽力回填 hash（PG 11+ 内置 sha256(bytea)），
--       环境不支持时跳过，由应用层惰性迁移兜底（认证命中即加密回写）。
ALTER TABLE agent ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(64);

COMMENT ON COLUMN agent.api_key_hash IS 'consumerToken SHA-256 hex（等保存储加密后的认证点查列；api_key 列仅存加密形态或存量明文）';

-- 存量回填（失败不阻断：应用惰性迁移兜底）
DO $$
BEGIN
    UPDATE agent SET api_key_hash = encode(sha256(api_key::bytea), 'hex')
    WHERE deleted = 0 AND api_key IS NOT NULL AND api_key_hash IS NULL;
EXCEPTION
    WHEN undefined_function THEN
        RAISE NOTICE 'sha256(bytea) 不可用，跳过存量回填（应用层惰性迁移兜底）';
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_api_key_hash_active
    ON agent(api_key_hash) WHERE deleted = 0 AND api_key_hash IS NOT NULL;