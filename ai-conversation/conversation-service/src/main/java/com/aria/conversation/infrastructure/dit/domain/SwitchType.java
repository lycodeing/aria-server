package com.aria.conversation.infrastructure.dit.domain;

/** 会话域切换类型常量（阿里规范 §1.4 禁止魔法字符串）。 */
public final class SwitchType {
    public static final String INITIAL       = "INITIAL";
    public static final String ROUTER_MODEL  = "ROUTER_MODEL";
    public static final String LLM_TOOL      = "LLM_TOOL";
    public static final String USER_SELECTED = "USER_SELECTED";
    private SwitchType() {}
}
