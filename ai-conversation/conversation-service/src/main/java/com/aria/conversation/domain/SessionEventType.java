package com.aria.conversation.domain;

/**
 * 会话队列事件类型枚举。
 * 与 SessionStatus 分离，避免 "CLOSED" 字符串在状态和事件两处含义混淆。
 */
public enum SessionEventType {

    /**
     * 用户请求转人工，进入等待队列
     */
    ENQUEUE,

    /**
     * 座席接入会话
     */
    ACCEPTED,

    /**
     * 会话结束（座席主动关闭或断线）
     */
    CLOSED,

    /**
     * 会话转交给其他座席
     */
    TRANSFER,

    /**
     * 系统自动将排队/新入队会话分配给座席（区别于座席手动 ACCEPTED）
     */
    AUTO_ASSIGNED
}
