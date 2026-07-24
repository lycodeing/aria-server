package com.aria.conversation.infrastructure.webhook;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.application.service.IWebhookSendService;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WebhookTestSender implements IWebhookSendService {

    private static final int NOT_FOUND = 40400;

    private final WebhookConfigMapper webhookConfigMapper;
    private final List<WebhookSender>  senderList;

    @Override
    public void sendTest(Long webhookId) {
        WebhookConfigEntity config = webhookConfigMapper.selectById(webhookId);
        if (config == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + webhookId);

        Map<String, WebhookSender> senderMap = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        WebhookSender sender = senderMap.get(config.getType());
        if (sender == null) throw new BusinessException(40001, "不支持的 Webhook 类型: " + config.getType());

        SlaBreachEntity mockBreach = SlaBreachEntity.builder()
                .sessionId("test-session").breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185).build();
        SlaBreachContext mockCtx = new SlaBreachContext(
                "test-session", "测试访客", "测试策略", List.of(mockBreach));
        try {
            sender.send(config, mockCtx);
        } catch (Exception e) {
            throw new BusinessException(500, "Webhook 测试发送失败: " + e.getMessage());
        }
    }
}
