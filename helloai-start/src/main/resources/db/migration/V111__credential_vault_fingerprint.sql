-- ============================================================
-- V111__credential_vault_fingerprint.sql
-- 用途：`M-6`「Key 指纹标识」——为 `credential_vault` 增列 `fingerprint`，
--   承载明文的 SHA-256 **前缀**指纹，用于：
--     ① 同一 Key 二次录入 ⇒ 识别为「已存在」（不重复落库）；
--     ② 多 Key 区分（无需比较明文）。
--   指纹**不可逆** ⇒ 不暴露明文。
-- ------------------------------------------------------------
-- 口径（对齐 magpie `internal/access/access.go:420-421`：
--   `sum := sha256.Sum256([]byte(secret)); hex.EncodeToString(sum[:12])`）：
--   指纹 = SHA-256(明文 UTF-8) 的**前 12 字节**小写十六进制串 = **24 字符**。
--   Java 侧唯一实现 = `com.helloai.common.crypto.CredentialFingerprint#of`。
-- ------------------------------------------------------------
-- ★为什么本迁移**不回填数据**：
--   `credential_vault.encrypted_value` 是 **AES-GCM 密文**（每次加密随机 nonce
--   ⇒ 同一明文两次加密得到不同密文），**无法在 SQL 侧还原明文**，故也无法由
--   密文推导指纹。历史行指纹由**应用层惰性回填**：
--   `PlatformProviderConfigServiceImpl#saveApiKey` 在写入前，对本 provider 的
--   `fingerprint IS NULL` 且 `encrypted_value IS NOT NULL` 的历史行解密补写
--   （幂等，只补不覆盖）。本迁移只建列 + 建索引。
-- ------------------------------------------------------------
-- 可空性：列**允许 NULL**，两类合法 NULL：
--   ① `secret_ref` 型凭证（无 `encrypted_value`，无明文可摘要）；
--   ② 迁移前的历史行（待应用层惰性回填）。
-- ★**不设 DEFAULT**：`ADD COLUMN ... DEFAULT` 会让「列为空」的行静默取默认值，
--   从而使后续按列值的判据拿到**未回填的假值**（`V110` 已登记该教训）。
--   本列语义上「未知」，NULL 才是正确表达。
-- ============================================================

ALTER TABLE credential_vault
    ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);

COMMENT ON COLUMN credential_vault.fingerprint IS
    'M-6 Key 指纹：SHA-256(明文) 前 12 字节的小写十六进制串（24 字符），用于同 provider 内同 Key 去重与多 Key 区分，不可逆、不暴露明文。NULL = secretRef 型凭证或待回填的历史行。';

-- 去重查询谓词 = (provider, fingerprint) + 未删除 ⇒ 建匹配的部分索引
-- （`listByOwnerAndProvider` 的回填扫描不走此索引，只有 existsByProviderAndFingerprint 走）
CREATE INDEX IF NOT EXISTS idx_credential_vault_fingerprint
    ON credential_vault (provider, fingerprint)
    WHERE deleted = 0;

-- ── 验收断言（M-6 data 层）──────────────────────────────────
DO $$
DECLARE
    col_exists int;
    idx_exists int;
    null_default int;
BEGIN
    -- ① 列已建立
    SELECT count(*) INTO col_exists
    FROM information_schema.columns
    WHERE table_name = 'credential_vault' AND column_name = 'fingerprint';
    IF col_exists <> 1 THEN
        RAISE EXCEPTION 'V111 失败：credential_vault.fingerprint 未建立';
    END IF;

    -- ② 部分索引已建立
    SELECT count(*) INTO idx_exists
    FROM pg_indexes
    WHERE tablename = 'credential_vault' AND indexname = 'idx_credential_vault_fingerprint';
    IF idx_exists <> 1 THEN
        RAISE EXCEPTION 'V111 失败：idx_credential_vault_fingerprint 未建立';
    END IF;

    -- ③ 列必须**无默认值**（防「列默认值是隐性事实源」）
    SELECT count(*) INTO null_default
    FROM information_schema.columns
    WHERE table_name = 'credential_vault' AND column_name = 'fingerprint'
      AND column_default IS NULL;
    IF null_default <> 1 THEN
        RAISE EXCEPTION 'V111 失败：fingerprint 不应有默认值';
    END IF;
END $$;
