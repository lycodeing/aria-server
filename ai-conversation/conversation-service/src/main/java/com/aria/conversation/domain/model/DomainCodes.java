package com.aria.conversation.domain.model;

/** 领域相关常量（阿里规范 §1.4 禁止魔法字符串）。 */
public final class DomainCodes {
    /** DIT 系统保留域 code，存储 FAQ 路径通用意图，由运营维护，勿删 */
    public static final String SYSTEM_DOMAIN = "__system__";
    private DomainCodes() {}
}
