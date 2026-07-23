package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.infrastructure.persistence.entity.*;
import com.aria.conversation.infrastructure.persistence.mapper.*;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TagAppService {

    private static final int    NOT_FOUND            = 40400;
    private static final int    PARAM_ERROR          = 40001;
    private static final String VISITOR_CACHE_PREFIX = "visitor:tags:";

    private final TagMapper              tagMapper;
    private final VisitorTagMapper       visitorTagMapper;
    private final ConversationTagMapper  conversationTagMapper;
    private final ConversationMapper     conversationMapper;
    private final StringRedisTemplate    redisTemplate;
    private final RabbitTemplate         eventsRabbitTemplate;
    private final String                 eventsExchange;

    public TagAppService(
            TagMapper tagMapper,
            VisitorTagMapper visitorTagMapper,
            ConversationTagMapper conversationTagMapper,
            ConversationMapper conversationMapper,
            StringRedisTemplate redisTemplate,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate eventsRabbitTemplate,
            @Value("${conversation.events.exchange}") String eventsExchange) {
        this.tagMapper             = tagMapper;
        this.visitorTagMapper      = visitorTagMapper;
        this.conversationTagMapper = conversationTagMapper;
        this.conversationMapper    = conversationMapper;
        this.redisTemplate         = redisTemplate;
        this.eventsRabbitTemplate  = eventsRabbitTemplate;
        this.eventsExchange        = eventsExchange;
    }

    // ── 访客持久标签 ─────────────────────────────────────────────────────────────

    public List<TagVO> listVisitorTags(String sessionId) {
        String visitorId = requireVisitorId(sessionId);
        return visitorTagMapper.selectTagsByVisitorId(visitorId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public TagVO addVisitorTag(String sessionId, String operatorId,
                               Long tagId, String tagName) {
        String visitorId = requireVisitorId(sessionId);
        TagEntity tag = resolveOrCreateTag(tagId, tagName, operatorId);

        if (!visitorTagMapper.existsTag(visitorId, tag.getId())) {
            visitorTagMapper.insert(VisitorTagEntity.builder()
                    .visitorId(visitorId)
                    .tagId(tag.getId())
                    .taggedBy(operatorId)
                    .build());
            tagMapper.atomicIncrUsageCount(tag.getId());
        }
        evictVisitorCache(visitorId);
        publishTagUpdatedEvent(sessionId);
        return toVO(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeVisitorTag(String sessionId, String operatorId, Long tagId) {
        String visitorId = requireVisitorId(sessionId);
        if (visitorTagMapper.existsTag(visitorId, tagId)) {
            visitorTagMapper.deleteByVisitorIdAndTagId(visitorId, tagId);
            tagMapper.atomicDecrUsageCount(tagId);
        }
        evictVisitorCache(visitorId);
        publishTagUpdatedEvent(sessionId);
    }

    // ── 会话级标签 ───────────────────────────────────────────────────────────────

    public List<TagVO> listSessionTags(String sessionId) {
        return conversationTagMapper.selectTagsBySessionId(sessionId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public TagVO addSessionTag(String sessionId, String operatorId,
                               Long tagId, String tagName) {
        TagEntity tag = resolveOrCreateTag(tagId, tagName, operatorId);
        if (!conversationTagMapper.existsTag(sessionId, tag.getId())) {
            conversationTagMapper.insert(ConversationTagEntity.builder()
                    .sessionId(sessionId)
                    .tagId(tag.getId())
                    .taggedBy(operatorId)
                    .build());
            tagMapper.atomicIncrUsageCount(tag.getId());
        }
        publishTagUpdatedEvent(sessionId);
        return toVO(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSessionTag(String sessionId, String operatorId, Long tagId) {
        if (conversationTagMapper.existsTag(sessionId, tagId)) {
            conversationTagMapper.deleteBySessionIdAndTagId(sessionId, tagId);
            tagMapper.atomicDecrUsageCount(tagId);
        }
        publishTagUpdatedEvent(sessionId);
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────────────────────

    /**
     * 标签变更后，向 Fanout Exchange 发布 TAG_UPDATED 事件，通知所有在线坐席 SSE 客户端实时刷新。
     * 失败只记录 WARN，不阻断主流程。
     */
    private void publishTagUpdatedEvent(String sessionId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", SessionEventType.TAG_UPDATED.name());
            event.put("sessionId", sessionId);
            event.put("visitorTags", listVisitorTags(sessionId));
            event.put("sessionTags", listSessionTags(sessionId));
            // NOTE: Event is published within the @Transactional boundary, before the transaction commits.
            // In the unlikely case of a subsequent rollback, SSE consumers may receive a phantom notification.
            // To eliminate this risk, use TransactionSynchronizationManager.registerSynchronization with
            // an afterCommit callback, or switch to @TransactionalEventListener(AFTER_COMMIT).
            // Accepted tradeoff for simplicity given the low-impact nature of tag change notifications.
            eventsRabbitTemplate.convertAndSend(eventsExchange, "", event);
        } catch (Exception e) {
            log.warn("[TagAppService] TAG_UPDATED 事件发布失败 sessionId={}", sessionId, e);
        }
    }

    private String requireVisitorId(String sessionId) {
        ConversationEntity conv = conversationMapper.selectBySessionId(sessionId);
        if (conv == null || conv.getVisitorId() == null) {
            throw new BusinessException(NOT_FOUND, "会话不存在或访客 ID 为空: " + sessionId);
        }
        return conv.getVisitorId();
    }

    private TagEntity resolveOrCreateTag(Long tagId, String tagName, String createdBy) {
        if (tagId != null) {
            TagEntity tag = tagMapper.selectById(tagId);
            if (tag == null) {
                throw new BusinessException(NOT_FOUND, "标签不存在: " + tagId);
            }
            return tag;
        }
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(PARAM_ERROR, "tagId 和 tagName 不能同时为空");
        }
        TagEntity existing = tagMapper.selectByName(tagName);
        if (existing != null) {
            return existing;
        }
        TagEntity newTag = TagEntity.builder()
                .name(tagName)
                .color("#6B7280")
                .source("CUSTOM")
                .createdBy(createdBy)
                .build();
        tagMapper.insert(newTag);
        return newTag;
    }

    private void evictVisitorCache(String visitorId) {
        redisTemplate.delete(VISITOR_CACHE_PREFIX + visitorId);
    }

    private TagVO toVO(TagEntity e) {
        return new TagVO(e.getId(), e.getName(), e.getColor(), e.getSource());
    }
}
