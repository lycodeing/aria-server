package com.aria.conversation.infrastructure.ai;

/** AI 协议标识常量。禁止在代码中使用字符串字面量标识协议（阿里规范 §1.4）。 */
public final class AiProtocol {
    public static final String OPENAI             = "openai";
    public static final String OPENAI_COMPATIBLE  = "openai_compatible";
    public static final String DEEPSEEK           = "deepseek";
    public static final String MOONSHOT           = "moonshot";
    public static final String QIANWEN            = "qianwen";
    public static final String ANTHROPIC          = "anthropic";
    private AiProtocol() {}
}
