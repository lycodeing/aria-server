package com.aria.common.core.util;

import java.util.regex.Pattern;

/**
 * 隐私数据脱敏工具类。
 * 用于历史工单入库前的数据清洗，防止 PII 进入向量库。
 */
public final class SensitiveDataUtils {

    /** 手机号：保留前 3 位和后 4 位 */
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");

    /** 身份证号：保留前 6 位和后 4 位 */
    private static final Pattern ID_CARD_PATTERN =
        Pattern.compile("(\\d{6})\\d{8}(\\d{4}[Xx])");

    /**
     * 银行卡号：16~19 位，使用负向环视确保只匹配独立的数字块，
     * 防止误伤订单号、快递单号等嵌在文本中的长数字串。
     * 替换后保留后 4 位，前缀显示 ************。
     */
    private static final Pattern BANK_CARD_PATTERN =
        Pattern.compile("(?<!\\d)(\\d{12,15})(\\d{4})(?!\\d)");

    private SensitiveDataUtils() {}

    /**
     * 对文本中的 PII 数据进行脱敏处理。
     *
     * @param text 原始文本
     * @return 脱敏后的文本，null 或空直接返回原值
     */
    public static String desensitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
        result = ID_CARD_PATTERN.matcher(result).replaceAll("$1********$2");
        // 银行卡脱敏：负向环视确保只匹配独立数字块，前缀替换为 ************ 后保留后 4 位
        result = BANK_CARD_PATTERN.matcher(result).replaceAll("************$2");
        return result;
    }
}
