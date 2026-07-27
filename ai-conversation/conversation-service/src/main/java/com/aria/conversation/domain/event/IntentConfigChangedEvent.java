package com.aria.conversation.domain.event;

/**
 * 意图配置变更领域事件。
 *
 * <p>领域事件定义在 domain 层，监听器（
 * {@link com.aria.conversation.infrastructure.event.IntentPrototypeStoreRefreshListener}）
 * 在 infrastructure 层响应，保持 DDD 分层。
 */
public record IntentConfigChangedEvent(String domainCode) {}
