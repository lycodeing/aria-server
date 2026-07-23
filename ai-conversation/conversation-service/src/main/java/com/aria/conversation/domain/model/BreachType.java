package com.aria.conversation.domain.model;

/**
 * SLA 违规类型。
 *
 * <ul>
 *   <li>{@link #WAIT}   — 等待时长（访客从入队到座席接入的时间）</li>
 *   <li>{@link #FRT}    — 首次响应时长（First Reply Time）</li>
 *   <li>{@link #HANDLE} — 处理时长（整个会话时长）</li>
 * </ul>
 */
public enum BreachType {
    WAIT,
    FRT,
    HANDLE
}
