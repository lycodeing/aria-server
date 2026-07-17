package com.aria.common.web.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 请求通用工具，消除各 Controller/Filter 中重复的请求解析私有方法。
 */
public final class HttpRequestUtils {

    private HttpRequestUtils() {}

    /**
     * 提取客户端真实 IP。
     *
     * <p>依次尝试以下 Header，取首个非空、非 unknown 的值：
     * <ol>
     *   <li>{@code X-Forwarded-For} — 标准反向代理链，格式 {@code client, proxy1, proxy2}，取第一个</li>
     *   <li>{@code X-Real-IP} — Nginx 单跳代理常用</li>
     *   <li>{@code RemoteAddr} — 无代理直连时的对端地址</li>
     * </ol>
     *
     * <p><b>安全提示：</b>该值来自请求头，客户端可伪造。仅用于日志记录、设备信息归档等非安全场景；
     * 不得用于访问控制或身份鉴权。
     *
     * @param request HTTP 请求
     * @return 客户端 IP 字符串，不为 null
     */
    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
