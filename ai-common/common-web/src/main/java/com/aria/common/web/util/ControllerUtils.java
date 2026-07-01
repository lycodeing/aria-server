package com.aria.common.web.util;

import java.util.List;
import java.util.Map;

/** Controller 层通用类型转换工具，消除各 Controller 中重复的 toLong/castList 私有方法。 */
public final class ControllerUtils {

    private ControllerUtils() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> castList(Object obj) {
        return (List<Map<String, Object>>) obj;
    }

    public static long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number n) return n.longValue();
        return Long.parseLong(obj.toString());
    }

    public static Long toLongOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        return Long.parseLong(obj.toString());
    }
}
