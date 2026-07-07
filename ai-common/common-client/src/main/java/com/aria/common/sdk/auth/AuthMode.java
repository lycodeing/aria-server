package com.aria.common.sdk.auth;

/**
 * SDK 客户端鉴权模式。
 *
 * <p>各服务 SDK 通过 {@link com.aria.common.sdk.ClientConfig#getAuthMode()} 声明
 * 使用哪种鉴权协议；{@link com.aria.common.sdk.BaseClient} 依此装配对应拦截器。
 *
 * <ul>
 *   <li>{@link #AK_SK}         — HMAC-SHA256 签名（AK/SK），面向对外接口</li>
 *   <li>{@link #SHARED_SECRET} — X-Internal-Secret 头，面向内网服务间调用</li>
 *   <li>{@link #NONE}          — 无鉴权，仅用于本地测试或健康探针</li>
 * </ul>
 *
 * @author lycodeing
 * @since 2026-07
 */
public enum AuthMode {

    /** AK/SK HMAC-SHA256 签名（默认，保持既有 SDK 行为不变） */
    AK_SK,

    /** 静态共享密钥（X-Internal-Secret 请求头），面向内网服务间调用 */
    SHARED_SECRET,

    /** 无鉴权 */
    NONE
}
