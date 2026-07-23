package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.*;
import com.aria.conversation.infrastructure.persistence.mapper.*;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagAppService {

    private static final int    NOT_FOUND            = 40400;
    private static final int    PARAM_ERROR          = 40001;
    private static final String VISITOR_CACHE_PREFIX = "visitor:tags:";

    private final TagMapper              tagMapper;
    private final VisitorTagMapper       visitorTagMapper;
    private final ConversationTagMapper  conversationTagMapper;
    private final ConversationNoteMapper noteMapper;
    private final ConversationMapper     conversationMapper;
    private final StringRedisTemplate    redisTemplate;

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
        return toVO(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSessionTag(String sessionId, String operatorId, Long tagId) {
        if (conversationTagMapper.existsTag(sessionId, tagId)) {
            conversationTagMapper.deleteBySessionIdAndTagId(sessionId, tagId);
            tagMapper.atomicDecrUsageCount(tagId);
        }
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────────────────────

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
