package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.DomainSwitchPayload;
import com.aria.conversation.application.service.payload.ErrorPayload;
import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.support.SseJson;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 对话流事件，封装 SSE 事件类型和数据。
 *
 * <p>Application 层返回此对象，Interface 层（Controller）负责将其转换为
 * {@link org.springframework.http.codec.ServerSentEvent}。
 * 解耦业务语义（路由/工具调用/转接人工）与传输协议（SSE 格式）。
 *
 * <p><b>Wire format 契约</b>：所有事件的 {@code data} 字段一律采用紧凑 JSON 信封，
 * 与 OpenAI / Azure OpenAI Chat Completion streaming 官方 SSE 格式保持一致。前端解析器
 * 严格遵循 WHATWG SSE 规范（多条 {@code data:} 行以 {@code \n} 拼接、剥离一个前导空格），
 * 拿到完整 data 字符串后再 {@code JSON.parse}。裸字符串传输会踩两个坑：
 * <ol>
 *   <li>WHATWG 要求剥离 {@code data:} 后的一个前导空格 → LLM 分词器 token 天然带前导空格
 *       （如 " 🔴 "、" 26"）被吃掉，导致 {@code "### 🔴 xx"} 拼成 {@code "###🔴 xx"}，
 *       Markdown 标题识别失败；</li>
 *   <li>{@link org.springframework.http.codec.ServerSentEventHttpMessageWriter} 会把
 *       payload 中的 {@code \n} 拆成多行 {@code data:}，前端逐条 dispatch 会丢换行边界。</li>
 * </ol>
 * JSON 信封通过 {@code JSON.stringify} 天然规避以上两点。
 *
 * <p><b>事件类型约定（前后端共同遵守）</b>：所有 SSE event 名称统一在
 * {@link EventType} 内部类中定义，前端 SSE 解析必须引用相同字符串，
 * 不允许在业务代码中散落字符串字面量。
 *
 * @param eventType SSE 事件类型，null 表示默认 data 事件（无 event: 行，token 走此通道）
 * @param data      事件数据内容，一律为 JSON 字符串（{@link EventType#DONE} 例外，固定为 {@code [DONE]}）
 */
public record ChatEvent(String eventType, String data) {

    // ----------------------------------------------------------------
    // SSE event type 集中定义（消除魔法字符串，前后端约定唯一来源）
    // 前端 currentEvent 判断逻辑必须与此处保持一致
    // ----------------------------------------------------------------
    public static final class EventType {
        /**
         * 知识库溯源标签，data 为 JSON 数组 [{docId, label}]
         */
        public static final String SOURCES = "sources";
        /**
         * 工具调用开始，data 为 {@link com.aria.conversation.application.service.payload.ToolCallPayload} JSON
         */
        public static final String TOOL_CALL = "tool_call";
        /**
         * 工具调用完成，data 为 {@link com.aria.conversation.application.service.payload.ToolDonePayload} JSON
         */
        public static final String TOOL_DONE = "tool_done";
        /**
         * 自动转人工信号，data 为 {@link com.aria.conversation.application.service.payload.TransferPayload} JSON。
         * 前端收到后必须：切换 transferred 状态 → 持久化 localStorage → 建立 WebSocket。
         */
        public static final String TRANSFER = "transfer";
        /**
         * 业务错误，data 为 {@link ErrorPayload} JSON
         */
        public static final String ERROR = "error";
        /**
         * 域切换信号，data 为 {@link DomainSwitchPayload} JSON
         */
        public static final String DOMAIN_SWITCH = "domain_switch";

        /**
         * SSE 流结束信号，data 固定字面量 {@code [DONE]}（与 OpenAI 规范一致，不做 JSON 封装）。
         * Controller 在 Flux 末尾追加此事件，前端据此关闭流并结束 loading 状态。
         */
        public static final String DONE = "done";

        /**
         * CSAT 评价邀请事件。data 为 JSON：
         * {"csatId":123,"sessionId":"sess_abc","message":"请对本次服务评价","expiresAt":"..."}
         */
        public static final String CSAT_REQUEST = "csat_request";

        private EventType() { /* 工具类，不允许实例化 */ }
    }

    // ----------------------------------------------------------------
    // 工厂方法：所有 payload 通过 {@link SseJson} 序列化为 JSON 信封
    // ----------------------------------------------------------------

    /**
     * AI 回复 token（无 event 字段，SSE 默认事件）。
     *
     * <p>使用 JSON 信封 {@code {"content":"..."}} 而非裸字符串传输，
     * 详细动机见类 Javadoc「Wire format 契约」段落。
     *
     * @param content 原始 token 内容，所有空白与换行保持原样
     * @param mapper  Jackson ObjectMapper（Spring 容器注入，本模块无自定义配置）
     */
    public static ChatEvent token(String content, ObjectMapper mapper) {
        return new ChatEvent(null, SseJson.encode(mapper, new TokenPayload(content)));
    }

    /**
     * 知识库溯源标签。
     *
     * <p>data 为已序列化的 JSON 数组字符串（由调用方通过 {@code buildSourcesJson} 生成），
     * 保留字符串入参形式是为了兼容 {@code switchOnFirst} 内的批量构造场景。
     */
    public static ChatEvent sources(String json) {
        return new ChatEvent(EventType.SOURCES, json);
    }

    /**
     * 工具调用开始。data 由调用方序列化 {@code ToolCallPayload}。
     */
    public static ChatEvent toolCall(String json) {
        return new ChatEvent(EventType.TOOL_CALL, json);
    }

    /**
     * 工具调用完成。data 由调用方序列化 {@code ToolDonePayload}。
     */
    public static ChatEvent toolDone(String json) {
        return new ChatEvent(EventType.TOOL_DONE, json);
    }

    /**
     * 自动转人工信号。
     *
     * <p>data 由调用方序列化 {@code TransferPayload}。前端收到此事件后必须：
     * <ol>
     *   <li>将 transferred 状态设为 true</li>
     *   <li>持久化到 localStorage（页面刷新后恢复）</li>
     *   <li>建立 WebSocket 连接到坐席系统</li>
     * </ol>
     */
    public static ChatEvent transfer(String json) {
        return new ChatEvent(EventType.TRANSFER, json);
    }

    /**
     * 业务错误信号。data 为 {@link ErrorPayload} JSON，与其他事件对齐。
     */
    public static ChatEvent error(String message, ObjectMapper mapper) {
        return new ChatEvent(EventType.ERROR, SseJson.encode(mapper, new ErrorPayload(message)));
    }

    /**
     * 域切换信号。data 为 {@link DomainSwitchPayload} JSON。
     */
    public static ChatEvent domainSwitch(String targetDomain, ObjectMapper mapper) {
        return new ChatEvent(EventType.DOMAIN_SWITCH, SseJson.encode(mapper, new DomainSwitchPayload(targetDomain)));
    }
}
