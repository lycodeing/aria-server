package com.aria.conversation.application.service;

/**
 * 对话流事件，封装 SSE 事件类型和数据。
 *
 * <p>Application 层返回此对象，Interface 层（Controller）负责将其转换为
 * {@link org.springframework.http.codec.ServerSentEvent}。
 * 解耦业务语义（路由/工具调用/槽位询问）与传输协议（SSE 格式）。
 *
 * @param eventType SSE 事件类型，null 表示默认 data 事件
 * @param data      事件数据内容
 */
public record ChatEvent(String eventType, String data) {

    /** 普通 AI 回复 token */
    public static ChatEvent data(String data)       { return new ChatEvent(null, data); }

    /** 知识库溯源标签（JSON 数组） */
    public static ChatEvent sources(String json)    { return new ChatEvent("sources", json); }

    /** 槽位缺失，询问用户输入 */
    public static ChatEvent slotAsk(String json)    { return new ChatEvent("slot_ask", json); }

    /** 槽位发现候选项，等待用户选择 */
    public static ChatEvent candidates(String json) { return new ChatEvent("candidates", json); }

    /** 工具调用开始 */
    public static ChatEvent toolCall(String json)   { return new ChatEvent("tool_call", json); }

    /** 工具调用完成 */
    public static ChatEvent toolDone(String json)   { return new ChatEvent("tool_done", json); }
}
