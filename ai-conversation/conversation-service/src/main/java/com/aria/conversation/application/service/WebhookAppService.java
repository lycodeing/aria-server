package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookAppService {

    private static final int NOT_FOUND = 40400;
    private static final int CONFLICT  = 40900;
    private static final int INVALID_PARAM = 40000;

    private final WebhookConfigMapper webhookConfigMapper;
    private final IWebhookSendService webhookSendService;

    public List<WebhookConfigEntity> listWebhooks() {
        return webhookConfigMapper.selectList(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WebhookConfigEntity createWebhook(WebhookConfigEntity entity) {
        validateScopes(entity.getScopes());
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
        validateScopes(update.getScopes());
        WebhookConfigEntity existing = webhookConfigMapper.selectById(id);
        if (existing == null) throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);
        update.setId(id);
        webhookConfigMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWebhook(Long id) {
        if (webhookConfigMapper.selectById(id) == null) {
            throw new BusinessException(NOT_FOUND, "Webhook 不存在: " + id);
        }
        webhookConfigMapper.deleteById(id);
    }

    /**
     * 发送测试消息，验证 Webhook 配置可达。
     * 发送一条模拟违规消息，成功则返回，失败抛出 BusinessException。
     */
    public void testWebhook(Long id) {
        webhookSendService.sendTest(id);
    }

    private void validateScopes(List<String> scopes) {
        if (scopes == null) {
            return; // 未传：DB 默认 ["SLA_BREACH"]
        }
        // 重复校验
        if (new HashSet<>(scopes).size() != scopes.size()) {
            throw new BusinessException(INVALID_PARAM, "webhook 范围存在重复值");
        }
        for (String s : scopes) {
            try {
                WebhookScope.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(INVALID_PARAM, "非法的 webhook 范围: " + s);
            }
        }
    }
}
