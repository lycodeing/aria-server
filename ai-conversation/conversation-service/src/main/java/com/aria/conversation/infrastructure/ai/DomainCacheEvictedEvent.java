package com.aria.conversation.infrastructure.ai;

import org.springframework.context.ApplicationEvent;

/** 域配置缓存失效事件，由 DomainRepository.evict() 发布 */
public class DomainCacheEvictedEvent extends ApplicationEvent {

    private final String domainCode;

    public DomainCacheEvictedEvent(Object source, String domainCode) {
        super(source);
        this.domainCode = domainCode;
    }

    public String getDomainCode() { return domainCode; }
}
