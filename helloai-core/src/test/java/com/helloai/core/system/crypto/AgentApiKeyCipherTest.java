package com.helloai.core.system.crypto;

import com.helloai.common.config.CredentialCryptoProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentApiKeyCipher 单测：加密/解密往返、明文兼容（存量迁移）、哈希点查列。
 *
 * <p>使用真实 {@link CredentialCryptoService}（AES-GCM）+ 固定测试密钥，
 * 覆盖"密钥由配置供给"的完整加密链路，非 mock 层 crosstalk。</p>
 */
@DisplayName("AgentApiKeyCipher（agent.api_key 等保存储加密）")
class AgentApiKeyCipherTest {

    /** 32 字节（AES-256）测试密钥，仅测试使用。 */
    private static final String TEST_KEY_BASE64 = "MEsmol1MWDTT69/lAOGeYGmDa9/a6S/lfHdfmekX0MM=";

    private AgentApiKeyCipher cipher;

    @BeforeEach
    void setUp() {
        CredentialCryptoProperties props = new CredentialCryptoProperties();
        props.setAesKeyBase64(TEST_KEY_BASE64);
        cipher = new AgentApiKeyCipher(new CredentialCryptoService(props));
    }

    @Test
    @DisplayName("encrypt/decrypt 往返还原明文，且密文带 enc:v1: 前缀")
    void roundTrip_shouldRestorePlaintext() {
        String stored = cipher.encrypt("ak_0123456789abcdef0123456789abcdef");

        assertThat(stored).startsWith("enc:v1:");
        assertThat(cipher.decrypt(stored)).isEqualTo("ak_0123456789abcdef0123456789abcdef");
    }

    @Test
    @DisplayName("每次加密密文不同（AES-GCM 随机 nonce），matches 仍稳定命中")
    void encrypt_shouldProduceDistinctCiphertextButStableMatch() {
        String plain = "ak_abc";
        String stored1 = cipher.encrypt(plain);
        String stored2 = cipher.encrypt(plain);

        assertThat(stored1).isNotEqualTo(stored2);
        assertThat(cipher.matches(plain, stored1)).isTrue();
        assertThat(cipher.matches(plain, stored2)).isTrue();
        assertThat(cipher.matches("ak_wrong", stored1)).isFalse();
    }

    @Test
    @DisplayName("存量明文兼容：无前缀原样解密，matches 直接比对")
    void legacyPlaintext_shouldBeCompatible() {
        String legacy = "ak_legacy_plain";

        assertThat(cipher.decrypt(legacy)).isEqualTo(legacy);
        assertThat(cipher.isEncrypted(legacy)).isFalse();
        assertThat(cipher.matches(legacy, legacy)).isTrue();
        assertThat(cipher.matches("ak_other", legacy)).isFalse();
    }

    @Test
    @DisplayName("null 透传安全（encrypt/decrypt/sha256Hex/matches）")
    void nullInputs_shouldBeSafe() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.sha256Hex(null)).isNull();
        assertThat(cipher.matches(null, "x")).isFalse();
        assertThat(cipher.matches("x", null)).isFalse();
        assertThat(cipher.isEncrypted(null)).isFalse();
    }

    @Test
    @DisplayName("sha256Hex 确定性且 64 位 hex")
    void sha256Hex_shouldBeDeterministic() {
        String h1 = cipher.sha256Hex("ak_test_key");
        String h2 = cipher.sha256Hex("ak_test_key");

        assertThat(h1).isEqualTo(h2).hasSize(64);
        assertThat(cipher.sha256Hex("ak_other")).isNotEqualTo(h1);
    }
}