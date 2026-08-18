package com.helloai.core.system.crypto;

import com.helloai.common.base.BizException;
import com.helloai.common.crypto.CredentialCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Agent 工牌 consumerToken（agent.api_key）存储加密器（等保三级"数据保密性"）。
 *
 * <p>存储形态：<code>enc:v1:{AES-GCM-Base64}</code>（版本前缀便于未来算法轮换）；
 * 存量明文（无前缀）由 {@link #matches} 兼容比对，认证命中后由调用方触发
 * {@link #encrypt} 惰性迁移回写（加密 + hash 双写）。</p>
 *
 * <p>认证点查用 {@link #sha256Hex}——AES-GCM 密文每次 nonce 随机，无法用 SQL eq
 * 精确定位；先按 hash 定位行，再 {@link #matches} 解密比对防哈希碰撞。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentApiKeyCipher {

    private static final String VERSION_PREFIX = "enc:v1:";

    private final CredentialCryptoService credentialCryptoService;

    /** 加密存储形态：{@code enc:v1:}{base64(nonce+密文)}；null 原样返回。 */
    public String encrypt(String plainKey) {
        if (plainKey == null) {
            return null;
        }
        return VERSION_PREFIX + credentialCryptoService.encryptToBase64(plainKey);
    }

    /** 解密为明文；无版本前缀视为存量明文原样返回（兼容迁移前数据）。 */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (isEncrypted(stored)) {
            return credentialCryptoService.decryptFromBase64(stored.substring(VERSION_PREFIX.length()));
        }
        return stored;
    }

    /** 校验明文与存储形态（密文/存量明文）是否一致。 */
    public boolean matches(String plainKey, String stored) {
        if (plainKey == null || stored == null) {
            return false;
        }
        byte[] expected = decrypt(stored).getBytes(StandardCharsets.UTF_8);
        byte[] actual = plainKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }

    /** 是否已加密存储（带 {@code enc:v1:} 前缀）。 */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(VERSION_PREFIX);
    }

    /** 认证点查哈希（SHA-256 hex）。 */
    public String sha256Hex(String plainKey) {
        if (plainKey == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plainKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BizException("SHA-256 不可用: " + e.getMessage());
        }
    }
}