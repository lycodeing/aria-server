package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 基于 LangChain4j 小模型的域路由服务。
 *
 * <p>根据用户消息和近期对话历史，调用 router 小模型判断是否需要切换服务域。
 * 任何异常均降级返回当前域（不切换），保证主流程不中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jDomainRoutingService implements DomainRoutingService {

    /** 路由判断时拼入 prompt 的历史窗口大小 */
    private static final int ROUTER_HISTORY_WINDOW = 4;

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepository;

    @Override
    public RouteResult route(String userMessage, String currentDomain,
                             List<ConversationMessage> recentHistory) {
        try {
            List<DomainConfig> enabledDomains = domainRepository.findAllEnabled();

            // 只有一个域，无需路由
            if (enabledDomains.size() <= 1) {
                return new RouteResult(currentDomain, false);
            }

            String prompt = buildPrompt(userMessage, currentDomain, recentHistory, enabledDomains);
            String response = modelFactory.getRouterModel().chat(prompt).trim();

            // 校验小模型返回值是否为合法域 code（大小写不敏感）
            Optional<String> matchedCode = enabledDomains.stream()
                    .filter(d -> d.code().equalsIgnoreCase(response))
                    .map(DomainConfig::code)
                    .findFirst();

            if (matchedCode.isEmpty()) {
                log.warn("[Router] 小模型返回非法域 code: '{}' currentDomain={}", response, currentDomain);
                return new RouteResult(currentDomain, false);
            }

            String resolvedCode = matchedCode.get();
            boolean shouldSwitch = !resolvedCode.equalsIgnoreCase(currentDomain);
            log.debug("[Router] 路由结果 current={} suggested={} shouldSwitch={}",
                    currentDomain, resolvedCode, shouldSwitch);
            return new RouteResult(resolvedCode, shouldSwitch);

        } catch (Exception e) {
            log.warn("[Router] 域路由失败，降级保持当前域 currentDomain={}", currentDomain, e);
            return new RouteResult(currentDomain, false);
        }
    }

    private String buildPrompt(String userMessage, String currentDomain,
                                List<ConversationMessage> recentHistory,
                                List<DomainConfig> enabledDomains) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个客服对话域路由器，根据用户消息判断应该由哪个服务域处理。\n\n");
        sb.append("可用服务域：\n");
        for (DomainConfig d : enabledDomains) {
            sb.append("- ").append(d.code());
            if (d.description() != null && !d.description().isBlank()) {
                sb.append("：").append(d.description());
            }
            sb.append("\n");
        }
        sb.append("\n当前域：").append(currentDomain).append("\n");

        // 截取最近 ROUTER_HISTORY_WINDOW 条历史
        List<ConversationMessage> window = recentHistory.size() > ROUTER_HISTORY_WINDOW
                ? recentHistory.subList(recentHistory.size() - ROUTER_HISTORY_WINDOW, recentHistory.size())
                : recentHistory;

        if (!window.isEmpty()) {
            sb.append("\n最近对话：\n");
            for (ConversationMessage m : window) {
                sb.append(m.role()).append(": ").append(m.content()).append("\n");
            }
        }

        sb.append("\n用户最新消息：").append(userMessage).append("\n\n");
        sb.append("请只输出一个域 code（如 ecommerce），不要输出任何其他内容。");
        return sb.toString();
    }
}
