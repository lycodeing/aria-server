package com.aria.conversation.domain.service;

import com.aria.conversation.domain.ConversationMessage;
import java.util.List;

/**
 * 域路由领域服务接口。
 * 接口在 domain 层，实现在 infrastructure 层，不暴露 LangChain4j 类型。
 */
public interface DomainRoutingService {

    record RouteResult(String suggestedDomain, boolean shouldSwitch) {}

    RouteResult route(String userMessage, String currentDomain,
                      List<ConversationMessage> recentHistory);
}
