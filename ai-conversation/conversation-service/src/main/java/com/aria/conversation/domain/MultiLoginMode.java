package com.aria.conversation.domain;

/**
 * 座席多端登录策略。
 *
 * <p>通过 {@code agent.ws.multi-login-mode} 配置，由 Spring 自动将字符串值转换为枚举；
 * 配置值拼写错误时启动即失败，不会静默 fallback。
 */
public enum MultiLoginMode {
    /** 多端并发在线，所有连接均收到消息（默认） */
    BROADCAST,
    /** 新端登录时踢出旧端，推送 KICKED_OUT 后关闭旧连接 */
    KICK
}
