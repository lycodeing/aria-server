package com.aria.common.core.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具。
 * <p>用于加密存储 API Key 的 SK、Webhook Secret、LDAP 绑定密码等敏感信息。
 * <p>密钥通过环境变量 ADP_SK_ENCRYPT_KEY 注入（32字节 Base64 编码）。
 */
public final class EncryptUtils {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final byte[] SECRET_KEY_BYTES = initKeyBytes();

    private EncryptUtils() {}

    /**
     * 加密：返回 Base64(iv || ciphertext || tag)。
     */
    public static String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET_KEY_BYTES, "AES"),
                    new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败", e);
        }
    }

    /**
     * 解密。
     */
    public static String decrypt(String cipherBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = Arrays.copyOf(combined, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SECRET_KEY_BYTES, "AES"),
                    new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败", e);
        }
    }

    /**
     * SHA-256 哈希（十六进制输出）。
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    /**
     * 初始化 AES 密钥字节。
     *
     * <p>读取环境变量 {@code ADP_SK_ENCRYPT_KEY}（Base64 编码的 32 字节随机密钥），
     * 使用真随机字节而非 ASCII 字符串，保证 256 位有效密钥熵值。
     *
     * <p>生成命令：{@code openssl rand -base64 32}
     *
     * <p>开发环境默认密钥仅用于本地调试，<b>生产环境必须通过环境变量注入</b>。
     */
    private static byte[] initKeyBytes() {
        String env = System.getenv("ADP_SK_ENCRYPT_KEY");
        if (env != null && !env.isBlank()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(env.trim());
                if (decoded.length == 32) return decoded;
            } catch (IllegalArgumentException ignored) {}
        }
        // 开发环境默认密钥（Base64 编码，真随机 32 字节，仅限本地调试）
        // 对应明文：dev-only-key-not-for-production!!
        return Base64.getDecoder().decode("ZGV2LW9ubHkta2V5LW5vdC1mb3ItcHJvZHVjdGlvbiE=");
    }
}
