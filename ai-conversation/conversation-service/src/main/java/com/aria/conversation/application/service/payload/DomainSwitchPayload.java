package com.aria.conversation.application.service.payload;

/**
 * 域切换信号的 SSE payload（对应 event:domain_switch）。
 *
 * <p>访客端一般静默忽略此事件；管理端可根据 code 联动切换 UI 域标签。
 *
 * @param code 目标域标识（对应 {@code cs_domain.code}）
 */
public record DomainSwitchPayload(String code) {}
