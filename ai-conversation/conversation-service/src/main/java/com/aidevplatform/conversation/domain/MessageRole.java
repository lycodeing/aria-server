package com.aidevplatform.conversation.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 对话消息角色枚举。
 *
 * <p>用于替换代码中散落的角色魔法字符串，通过 {@link EnumValue} 标注，
 * MyBatis-Plus 自动完成枚举与 DB 字符串列的双向映射。
 *
 * <p>DB 字段类型：VARCHAR(20)，存储值为 {@link #value} 字段（小写）。
 *
 * <p>角色约定：
 * <ul>
 *   <li>{@link #USER}      — 访客消息，存储值：{@code user}</li>
 *   <li>{@link #ASSISTANT} — AI 回复，存储值：{@code assistant}（与 OpenAI 标准一致）</li>
 *   <li>{@link #AGENT}     — 人工座席回复，存储值：{@code agent}（DB 专用，区分 AI 和人工）</li>
 *   <li>{@link #SYSTEM}    — 系统消息，存储值：{@code system}（用于发送系统级通知，如接入提示、会话超时等）</li>
 * </ul>
 */
public enum MessageRole {

    /** 访客消息 */
    USER("user"),

    /** AI 助手回复（与 OpenAI role 标准一致，Redis List 存储格式） */
    ASSISTANT("assistant"),

    /** 人工座席回复（DB 专用，与 AI 区分，便于质检分析） */
    AGENT("agent"),

    /**
     * 系统消息。
     * 用于发送系统级通知，例如：
     * <ul>
     *   <li>人工客服已接入</li>
     *   <li>会话即将超时</li>
     *   <li>座席已转交</li>
     *   <li>会话已结束</li>
     * </ul>
     * 前端展示时建议使用居中气泡样式，与普通对话消息视觉区分。
     */
    SYSTEM("system");

    /**
     * DB 存储值（小写字符串）。
     * 【强制】{@link EnumValue} 标注此字段，MyBatis-Plus 以该值与数据库列双向映射。
     */
    @EnumValue
    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    /**
     * 获取 DB 存储值（同时用于 Redis 存储和 AI API 调用）。
     *
     * @return 小写角色字符串
     */
    public String getValue() {
        return value;
    }

    /**
     * 从字符串解析枚举（大小写不敏感）。
     * 兼容历史 Redis 数据中已存储的字符串值。
     *
     * @param value 角色字符串
     * @return 对应枚举，未匹配时返回 null
     */
    public static MessageRole fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MessageRole role : values()) {
            if (role.value.equalsIgnoreCase(value)) {
                return role;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
