package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import com.aria.conversation.infrastructure.webhook.SlaBreachContext;
import com.aria.conversation.infrastructure.webhook.WebhookSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookAppService {

    private static final int NOT_FOUND = 40400;
    private static final int CONFLICT  = 40900;

    private final WebhookConfigMapper        webhookConfigMapper;
    private final List<WebhookSender>        senderList;

    public List<WebhookConfigEntity> listWebhooks() {
        return webhookConfigMapper.selectList(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WebhookConfigEntity createWebhook(WebhookConfigEntity entity) {
        if (webhookConfigMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<WebhookConfigEntity>lambdaQuery()
                        .eq(WebhookConfigEntity::getName, entity.getName())) != null) {
            throw new BusinessException(CONFLICT, "Webhook 名称已存在: " + entity.getName());
        }
        webhookConfigMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateWebhook(Long id, WebhookConfigEntity update) {
        WebhookConfigEntity existing = webhookConfigMapper.selectById(id);
        if (existing == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);
        update.setId(id);
        webhookConfigMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWebhook(Long id) {
        webhookConfigMapper.deleteById(id);
    }

    /**
     * 发送测试消息，验证 Webhook 配置可达。
     * 发送一条模拟违规消息，成功则返回，失败抛出 BusinessException。
     */
    public void testWebhook(Long id) {
        WebhookConfigEntity config = webhookConfigMapper.selectById(id);
        if (config == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);

        Map<String, WebhookSender> senderMap = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        WebhookSender sender = senderMap.get(config.getType());
        if (sender == null) {
            throw new BusinessException(40001, "不支持的 Webhook 类型: " + config.getType());
        }

        // 构造模拟违规上下文
        SlaBreachEntity mockBreach = SlaBreachEntity.builder()
                .sessionId("test-session")
                .breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185)
                .build();
        SlaBreachContext mockCtx = new SlaBreachContext(
                "test-session", "测试访客", "测试策略", List.of(mockBreach));
        try {
            sender.send(config, mockCtx);
        } catch (Exception e) {
            throw new BusinessException(500, "Webhook 测试发送失败: " + e.getMessage());
        }
    }
}
