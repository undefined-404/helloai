package com.helloai.core.system.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 凭证指纹：明文的 SHA-256 **前缀**十六进制串（`M-6`）。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>同一 Key 二次录入 ⇒ 可被识别为「已存在」，避免重复落库；</li>
 *   <li>多 Key 场景下区分不同 Key（不依赖明文比较）。</li>
 * </ul>
 *
 * <p><b>不暴露明文</b>：指纹为单向摘要前缀，不可由指纹还原 Key。</p>
 *
 * <p>与同包 {@link AgentApiKeyCipher#sha256Hex(String)} 的关系：后者是「认证点查哈希」，
 * 用**全量** SHA-256 hex 在 SQL 里精确定位行；本类是「去重 / 区分标识」，只用
 * **前 {@link #HEX_LENGTH} 个字符**，两者输出口径与用途均不同，故不合并。</p>
 *
 * <p>口径：取摘要**前 12 字节**转小写十六进制，即 {@value #HEX_LENGTH} 个字符。
 * API Key 属高熵随机串，无需加盐或迭代 KDF。</p>
 *
 * <p><b>★与 magpie 的关系（2026-10-14 订正）</b>：magpie 存在**两个同名「指纹」、语义不同** ——
 * {@code internal/provider/keys.go:69-70} 用「SHA-256 前 5 字节 / 10 字符」标识**供应商 API Key**
 * （{@code AddKeys} 去重即用该值比对，{@code keys.go:194,198}）；而
 * {@code internal/access/access.go:417-419} 的 {@code legacyLANKey} 用「前 12 字节 / 24 字符」
 * 标识 **LAN 调用方访问令牌**，与供应商 Key 无关。本类取 12 字节是**对 magpie 供应商 Key 口径的
 * 有意加强**（以留碰撞余量），**并非**对齐 magpie 的供应商 Key 口径（5 字节）。本 javadoc 早期
 * 版本引 {@code access.go:420-421} 作为对齐依据，属**归属错误**，已在此订正。</p>
 *
 * <p><b>比较作用域</b>：指纹只在**同一 provider 内**比较 —— 同一把 Key 被两个
 * provider 合法复用时，不应互相判重。</p>
 */
public final class CredentialFingerprint {

    /** 摘要前缀字节数（12 字节 = 96 bit，碰撞概率远低于本场景 Key 数量级）。 */
    static final int PREFIX_BYTES = 12;

    /** 指纹字符串长度 = 12 字节 × 2（十六进制字符）。 */
    public static final int HEX_LENGTH = PREFIX_BYTES * 2;

    private CredentialFingerprint() {
    }

    /**
     * 计算明文凭证的指纹。
     *
     * <p>传入的必须是**将被加密存储的同一份字节**（不做 trim、不做大小写归一、
     * 不舍弃首尾空格），否则指纹与其对应密文不一致。</p>
     *
     * @param plaintext 明文凭证
     * @return 24 字符小写十六进制指纹；{@code null} / 空白（{@code secret_ref} 型凭证、
     *         待回填的历史行）返回 {@code null}
     */
    public static String of(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] digest = sha256(plaintext.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, PREFIX_BYTES);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，正常环境不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
