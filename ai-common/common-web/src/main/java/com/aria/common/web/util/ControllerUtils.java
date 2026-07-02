package com.aria.common.web.util;

import java.util.List;
import java.util.Map;

/**
 * Controller 层通用类型转换工具，消除各 Controller 中重复的 toLong/castList 私有方法。
 *
 * <p>toLong/toLongOrNull 容错处理：非数字字符串时不抛 NumberFormatException，
 * 而是返回默认值（0 或 null），避免因请求参数格式异常导致 500 错误。
 */
public final class ControllerUtils {

    private ControllerUtils() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> castList(Object obj) {
        return (List<Map<String, Object>>) obj;
    }

    /**
     * 将 Object 转为 long，null 或非数字字符串时返回 0。
     */
    public static long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 将 Object 转为 Long，null 或非数字字符串时返回 null。
     */
    public static Long toLongOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
