package com.aria.common.core.util;

import java.util.regex.Pattern;

/**
 * 隐私数据脱敏工具类。
 * 用于历史工单入库前的数据清洗，防止 PII 进入向量库。
 */
public final class SensitiveDataUtils {

    /**
     * 身份证号（18 位）：保留前 6 位和后 4 位（末位校验码可为数字或 X/x）。
     * 加负向环视边界，避免与更长数字串重叠误匹配。
     */
    private static final Pattern ID_CARD_PATTERN =
        Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");

    /**
     * 银行卡号：16~19 位，使用负向环视确保只匹配独立的数字块，
     * 防止误伤订单号、快递单号等嵌在文本中的长数字串。
     * 替换后保留后 4 位，前缀显示 ************。
     */
    private static final Pattern BANK_CARD_PATTERN =
        Pattern.compile("(?<!\\d)(\\d{12,15})(\\d{4})(?!\\d)");

    /**
     * 手机号：保留前 3 位和后 4 位。
     * 加负向环视边界，避免匹配到身份证/银行卡等更长数字串中内嵌的 11 位子串，
     * 导致更长号码被破坏而漏脱敏。故手机号必须<b>最后</b>执行。
     */
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    /**
     * 邮箱：保留首字符与 @ 域名，中间本地部分脱敏。
     * 如 {@code alice@example.com → a****@example.com}。
     */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("(\\w)[\\w.+-]*(@[\\w.-]+\\.[a-zA-Z]{2,})");

    private SensitiveDataUtils() {}

    /**
     * 对文本中的 PII 数据进行脱敏处理。
     *
     * <p>执行顺序：先处理更长/更具体的模式（身份证 → 银行卡），
     * 再处理手机号（11 位，易被更长数字串内嵌），最后邮箱。
     * 顺序错误会导致长号码被短模式先行破坏从而漏脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本，null 或空直接返回原值
     */
    public static String desensitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 1. 身份证（18 位，最长最具体，最先处理）
        String result = ID_CARD_PATTERN.matcher(text).replaceAll("$1********$2");
        // 2. 银行卡（16~19 位，负向环视保证独立数字块）
        result = BANK_CARD_PATTERN.matcher(result).replaceAll("************$2");
        // 3. 手机号（11 位，负向环视避免内嵌误匹配，须在长号码之后）
        result = PHONE_PATTERN.matcher(result).replaceAll("$1****$2");
        // 4. 邮箱
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1****$2");
        return result;
    }
}
