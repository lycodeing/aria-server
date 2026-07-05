package com.aria.conversation.application.service;

/**
 * 对话流事件，封装 SSE 事件类型和数据。
 *
 * <p>Application 层返回此对象，Interface 层（Controller）负责将其转换为
 * {@link org.springframework.http.codec.ServerSentEvent}。
 * 解耦业务语义（路由/工具调用/槽位询问）与传输协议（SSE 格式）。
 *
 * <p><b>事件类型约定（前后端共同遵守）</b>：所有 SSE event 名称统一在
 * {@link EventType} 内部类中定义，前端 SSE 解析必须引用相同字符串，
 * 不允许在业务代码中散落字符串字面量。
 *
 * @param eventType SSE 事件类型，null 表示默认 data 事件（无 event: 行）
 * @param data      事件数据内容
 */
public record ChatEvent(String eventType, String data) {

    // ----------------------------------------------------------------
    // SSE event type 集中定义（消除魔法字符串，前后端约定唯一来源）
    // 前端 currentEvent 判断逻辑必须与此处保持一致
    // ----------------------------------------------------------------
    public static final class EventType {
        /** 知识库溯源标签，data 为 JSON 数组 [{docId, label}] */
        public static final String SOURCES    = "sources";
        /** 槽位缺失，等待用户文字输入，data 为提示语字符串 */
        public static final String SLOT_ASK   = "slot_ask";
        /** 槽位发现候选项，等待用户选择，data 为 JSON 数组 [{id, label}] */
        public static final String CANDIDATES = "candidates";
        /** 工具调用开始，data 为 ToolCallPayload JSON */
        public static final String TOOL_CALL  = "tool_call";
        /** 工具调用完成，data 为 ToolDonePayload JSON */
        public static final String TOOL_DONE  = "tool_done";
        /**
         * 自动转人工信号，data 为 TransferPayload JSON。
         * 前端收到后必须：切换 transferred 状态 → 持久化 localStorage → 建立 WebSocket。
         */
        public static final String TRANSFER   = "transfer";
        /** 业务错误，data 为错误描述字符串 */
        public static final String ERROR         = "error";
        /** 域切换信号，data 为目标域 code */
        public static final String DOMAIN_SWITCH = "domain_switch";

        /**
         * SSE 流结束信号，data 固定为 "[DONE]"。
         * Controller 在 Flux 末尾追加此事件，前端据此关闭流并结束 loading 状态。
         */
        public static final String DONE       = "done";

        private EventType() { /* 工具类，不允许实例化 */ }
    }

    // ----------------------------------------------------------------
    // 工厂方法（每个方法只使用 EventType 常量，不出现字符串字面量）
    // ----------------------------------------------------------------

    /** 普通 AI 回复 token（无 event 字段，SSE 默认事件） */
    public static ChatEvent data(String data) {
        return new ChatEvent(null, data);
    }

    /** 知识库溯源标签 */
    public static ChatEvent sources(String json) {
        return new ChatEvent(EventType.SOURCES, json);
    }

    /** 槽位缺失询问 */
    public static ChatEvent slotAsk(String json) {
        return new ChatEvent(EventType.SLOT_ASK, json);
    }

    /** 槽位候选项 */
    public static ChatEvent candidates(String json) {
        return new ChatEvent(EventType.CANDIDATES, json);
    }

    /** 工具调用开始 */
    public static ChatEvent toolCall(String json) {
        return new ChatEvent(EventType.TOOL_CALL, json);
    }

    /** 工具调用完成 */
    public static ChatEvent toolDone(String json) {
        return new ChatEvent(EventType.TOOL_DONE, json);
    }

    /**
     * 自动转人工信号。
     *
     * <p>前端收到此事件后必须：
     * <ol>
     *   <li>将 transferred 状态设为 true</li>
     *   <li>持久化到 localStorage（页面刷新后恢复）</li>
     *   <li>建立 WebSocket 连接到坐席系统</li>
     * </ol>
     */
    public static ChatEvent transfer(String json) {
        return new ChatEvent(EventType.TRANSFER, json);
    }

    /** 业务错误信号 */
    public static ChatEvent error(String message) {
        return new ChatEvent(EventType.ERROR, message);
    }

    /** 域切换信号，data 为目标域 code */
    public static ChatEvent domainSwitch(String targetDomain) {
        return new ChatEvent(EventType.DOMAIN_SWITCH, targetDomain);
    }
}
