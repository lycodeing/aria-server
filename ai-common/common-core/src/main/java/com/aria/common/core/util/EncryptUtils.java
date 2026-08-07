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
     * <p>安全策略（修复 fail-open）：
     * <ul>
     *   <li>环境变量已设置但格式非法/长度不符：一律 fail-fast，无论何种 profile（明显的配置错误，
     *       不能静默回退到弱密钥而误以为已加密）。</li>
     *   <li>生产 profile（spring.profiles.active 含 prod）下未提供有效密钥：fail-fast，
     *       禁止用硬编码开发密钥加密生产敏感数据。</li>
     *   <li>仅非生产 profile 允许回退到开发默认密钥，并打印醒目告警。</li>
     * </ul>
     */
    private static byte[] initKeyBytes() {
        String env = System.getenv("ADP_SK_ENCRYPT_KEY");
        if (env != null && !env.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(env.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "ADP_SK_ENCRYPT_KEY 不是合法的 Base64，请用 `openssl rand -base64 32` 生成 32 字节密钥", e);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "ADP_SK_ENCRYPT_KEY 解码后长度为 " + decoded.length + " 字节，AES-256 要求恰好 32 字节");
            }
            return decoded;
        }
        // 未提供环境变量：生产 profile 直接拒绝启动，防止用公开的开发密钥加密生产数据
        if (isProductionProfile()) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 ADP_SK_ENCRYPT_KEY 注入 AES 密钥（Base64 编码的 32 字节），"
                    + "禁止回退到开发默认密钥。生成命令：openssl rand -base64 32");
        }
        // 非生产环境：回退到开发默认密钥（Base64 编码，真随机 32 字节，仅限本地调试）
        // 对应明文：dev-only-key-not-for-production!!
        System.err.println("[EncryptUtils][WARN] 未配置 ADP_SK_ENCRYPT_KEY，正在使用公开的开发默认密钥，"
                + "严禁在生产环境使用！生产请通过环境变量注入。");
        return Base64.getDecoder().decode("ZGV2LW9ubHkta2V5LW5vdC1mb3ItcHJvZHVjdGlvbiE=");
    }

    /**
     * 判断当前是否为生产 profile。
     *
     * <p>依次读取系统属性 {@code spring.profiles.active} 与环境变量 {@code SPRING_PROFILES_ACTIVE}，
     * 逗号分隔的 profile 列表中包含 {@code prod} 或 {@code production}（忽略大小写）即视为生产环境。
     */
    private static boolean isProductionProfile() {
        String profiles = System.getProperty("spring.profiles.active");
        if (profiles == null || profiles.isBlank()) {
            profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (profiles == null || profiles.isBlank()) {
            return false;
        }
        for (String p : profiles.split(",")) {
            String t = p.trim();
            if (t.equalsIgnoreCase("prod") || t.equalsIgnoreCase("production")) {
                return true;
            }
        }
        return false;
    }
}
