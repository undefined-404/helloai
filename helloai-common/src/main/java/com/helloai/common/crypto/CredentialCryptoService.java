package com.helloai.common.crypto;

import com.helloai.common.base.BizException;
import com.helloai.common.config.CredentialCryptoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class CredentialCryptoService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;

    private final CredentialCryptoProperties cryptoProperties;

    public String encryptToBase64(String plaintext) {
        if (plaintext == null) {
            throw new BizException("plaintext 不能为空");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + cipherText.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(cipherText, 0, out, nonce.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BizException("凭证加密失败: " + e.getMessage());
        }
    }

    public String decryptFromBase64(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            throw new BizException("cipher 不能为空");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(cipherBase64);
        } catch (Exception e) {
            throw new BizException("cipher base64 无效");
        }
        if (data.length <= NONCE_LENGTH_BYTES) {
            throw new BizException("cipher 数据长度无效");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] cipherText = new byte[data.length - NONCE_LENGTH_BYTES];
        System.arraycopy(data, 0, nonce, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(data, NONCE_LENGTH_BYTES, cipherText, 0, cipherText.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("凭证解密失败: " + e.getMessage());
        }
    }

    private SecretKey getKey() {
        String base64Key = cryptoProperties.getAesKeyBase64();
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getenv("HELLOAI_CREDENTIAL_AES_KEY_BASE64");
        }
        if (base64Key == null || base64Key.isBlank()) {
            throw new BizException("缺少凭证加密密钥配置: helloai.security.credential.aes-key-base64 或 HELLOAI_CREDENTIAL_AES_KEY_BASE64");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (Exception e) {
            throw new BizException("凭证加密密钥 base64 无效");
        }
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new BizException("凭证加密密钥长度无效，必须为 16/24/32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}

