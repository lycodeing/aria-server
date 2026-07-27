package com.aria.conversation.domain.model;

/**
 * 意图路由优先级。
 *
 * <p>当用户消息包含多个意图时，{@link MultiIntentResult#primaryIntent()} 按此优先级
 * 选出"驱动分叉"的主意图。优先级数值越小，优先级越高（COMPLAINT 最高）。
 *
 * <p>设计原则：安全保障类意图（投诉、转人工）优先于服务类意图（FAQ），
 * 服务类意图优先于修饰类意图（闲聊），拒答最低。
 *
 * <p><b>维护约束：</b>{@link IntentType} 与本枚举的枚举项必须保持一一对应，同步新增/删除。
 * {@code switch} 语句的 exhaustive 检查（Java 17+）会在编译期保护该约束。
 *
 * @see IntentType 两者枚举项必须保持同步
 */
public enum IntentPriority {
    COMPLAINT(1),
    TRANSFER_REQUEST(2),
    FAQ_QUERY(3),
    CHITCHAT(4),
    OUT_OF_SCOPE(5),
    UNKNOWN(99);

    private final int order;

    IntentPriority(int order) { this.order = order; }

    public static IntentPriority of(IntentType type) {
        return switch (type) {
            case COMPLAINT         -> COMPLAINT;
            case TRANSFER_REQUEST  -> TRANSFER_REQUEST;
            case FAQ_QUERY         -> FAQ_QUERY;
            case CHITCHAT          -> CHITCHAT;
            case OUT_OF_SCOPE      -> OUT_OF_SCOPE;
            case UNKNOWN           -> UNKNOWN;
        };
    }

    public int getOrder() { return order; }
}
