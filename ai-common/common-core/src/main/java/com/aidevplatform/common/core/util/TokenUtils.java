package com.aidevplatform.common.core.util;

/**
 * Token 计数工具类。
 * 精确计数需调用 Tokenizer API，此处提供本地快速估算（误差 ±10%）。
 * 规则：中文按 1 字 ≈ 1 token，英文按 4 字符 ≈ 1 token。
 */
public final class TokenUtils {

    private TokenUtils() {}

    /**
     * 快速估算文本的 token 数量（本地，无 API 调用开销）。
     *
     * @param text 待估算文本
     * @return 估算 token 数
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chineseCount = 0;
        int otherCount   = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        // 中文 1 字 ≈ 1 token；英文/符号 4 字符 ≈ 1 token，+1 兜底
        return chineseCount + (otherCount / 4) + 1;
    }

    /**
     * 判断文本是否超过 token 上限。
     *
     * @param text     待检测文本
     * @param maxToken token 上限
     * @return 超出则返回 true
     */
    public static boolean exceeds(String text, int maxToken) {
        return estimate(text) > maxToken;
    }
}
