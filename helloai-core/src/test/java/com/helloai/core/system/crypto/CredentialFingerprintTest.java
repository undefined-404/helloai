package com.helloai.core.system.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialFingerprint} 单测（`M-6`）：口径锁定 / 确定性 / 不泄明文 / 空值语义。
 *
 * <p><b>golden 向量</b>由外部工具独立计算（Python {@code hashlib.sha256(...).hexdigest()[:24]}），
 * 不是用被测实现自证 —— 若实现改了摘要算法或前缀长度，此处必红。</p>
 */
@DisplayName("CredentialFingerprint（Key 指纹：SHA-256 前 12 字节 hex）")
class CredentialFingerprintTest {

    @Test
    @DisplayName("golden 向量：锁定 magpie 口径（sha256 前 12 字节 hex，小写，24 字符）")
    void shouldMatchExternalGoldenVectors() {
        assertThat(CredentialFingerprint.of("sk-test")).isEqualTo("f3abf2a6cc4f00987743db5f");
        assertThat(CredentialFingerprint.of("sk-abc12345")).isEqualTo("d7fbb78ad59288c4bbec9290");
        assertThat(CredentialFingerprint.of("sk-plain")).isEqualTo("433e1caaaa5f94cf8c399397");
    }

    @Test
    @DisplayName("长度恒为 24，且字符集为小写十六进制")
    void shouldBeLowercaseHexOfFixedLength() {
        assertThat(CredentialFingerprint.HEX_LENGTH).isEqualTo(24);

        String fingerprint = CredentialFingerprint.of("any-key-value");

        assertThat(fingerprint).hasSize(CredentialFingerprint.HEX_LENGTH);
        assertThat(fingerprint).matches("[0-9a-f]{24}");
    }

    @Test
    @DisplayName("同为全量 SHA-256 的前缀（截断口径正确，非另起算法）")
    void shouldBePrefixOfFullSha256() throws Exception {
        String plaintext = "sk-prefix-check";
        String full = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));

        assertThat(full).startsWith(CredentialFingerprint.of(plaintext));
    }

    @Test
    @DisplayName("确定性：同一明文多次计算一致；不同明文指纹不同")
    void shouldBeDeterministicAndDiscriminating() {
        assertThat(CredentialFingerprint.of("sk-same")).isEqualTo(CredentialFingerprint.of("sk-same"));
        assertThat(CredentialFingerprint.of("sk-a")).isNotEqualTo(CredentialFingerprint.of("sk-b"));
    }

    @Test
    @DisplayName("不暴露明文：指纹不含明文片段，也不等于明文")
    void shouldNotLeakPlaintext() {
        String plaintext = "sk-super-secret-value-1234567890";

        String fingerprint = CredentialFingerprint.of(plaintext);

        assertThat(fingerprint).doesNotContain(plaintext);
        assertThat(fingerprint).doesNotContain("sk-");
        assertThat(fingerprint).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("null / 空白 ⇒ null（secretRef 型凭证与待回填历史行无明文可摘要）")
    void shouldReturnNullForNullOrBlank() {
        assertThat(CredentialFingerprint.of(null)).isNull();
        assertThat(CredentialFingerprint.of("")).isNull();
        assertThat(CredentialFingerprint.of("   ")).isNull();
    }

    @Test
    @DisplayName("首尾空白敏感：指纹描述的是「将被加密的那份字节」，不做 trim")
    void shouldTreatSurroundingWhitespaceAsSignificant() {
        assertThat(CredentialFingerprint.of(" sk")).isNotEqualTo(CredentialFingerprint.of("sk"));
        assertThat(CredentialFingerprint.of("sk ")).isNotEqualTo(CredentialFingerprint.of("sk"));
    }
}
