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

    /** 银行卡号：保留后 4 位 */
    private static final Pattern BANK_CARD_PATTERN =
        Pattern.compile("\\d{12,19}(\\d{4})");

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
        result = BANK_CARD_PATTERN.matcher(result).replaceAll("************$1");
        return result;
    }
}
