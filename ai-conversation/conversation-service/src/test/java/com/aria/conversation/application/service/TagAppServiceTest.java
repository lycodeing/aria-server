package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationTagEntity;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.entity.VisitorTagEntity;
import com.aria.conversation.infrastructure.persistence.mapper.*;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagAppServiceTest {

    @Mock TagMapper              tagMapper;
    @Mock VisitorTagMapper       visitorTagMapper;
    @Mock ConversationTagMapper  conversationTagMapper;
    @Mock ConversationMapper     conversationMapper;
    @Mock StringRedisTemplate    redisTemplate;

    TagAppService service;

    private static final String SESSION_ID  = "sess-001";
    private static final String VISITOR_ID  = "visitor-abc";
    private static final String OPERATOR_ID = "agent-001";

    @BeforeEach
    void setUp() {
        service = new TagAppService(tagMapper, visitorTagMapper, conversationTagMapper,
                                    conversationMapper, redisTemplate);

        ConversationEntity conv = new ConversationEntity();
        conv.setSessionId(SESSION_ID);
        conv.setVisitorId(VISITOR_ID);
        // lenient: addSessionTag does not call requireVisitorId, so this stub is only needed by visitor-tag tests
        lenient().when(conversationMapper.selectBySessionId(SESSION_ID)).thenReturn(conv);
    }

    @Test
    @DisplayName("用已有 tagId 打访客标签 -> 写关联表 + 更新计数 + 失效缓存")
    void addVisitorTag_byId_success() {
        TagEntity tag = TagEntity.builder().id(1L).name("VIP").color("#F59E0B").source("PRESET").build();
        when(tagMapper.selectById(1L)).thenReturn(tag);
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(false);

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, 1L, null);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("VIP");

        // Use argThat with explicit type to disambiguate insert(T) vs insert(Collection<T>)
        ArgumentCaptor<VisitorTagEntity> captor = ArgumentCaptor.forClass(VisitorTagEntity.class);
        verify(visitorTagMapper).insert(argThat((VisitorTagEntity e) -> {
            captor.getAllValues(); // just to reference captor without using it for capture
            return VISITOR_ID.equals(e.getVisitorId()) && OPERATOR_ID.equals(e.getTaggedBy());
        }));

        verify(tagMapper).atomicIncrUsageCount(1L);
        verify(redisTemplate).delete("visitor:tags:" + VISITOR_ID);
    }

    @Test
    @DisplayName("用不存在的 tagName 打标 -> 自动创建 CUSTOM 标签")
    void addVisitorTag_byNewName_createsCustomTag() {
        when(tagMapper.selectByName("新客户")).thenReturn(null);
        // Simulate DB auto-filling id on insert
        doAnswer(inv -> {
            ((TagEntity) inv.getArgument(0)).setId(99L);
            return 1;
        }).when(tagMapper).insert(argThat((TagEntity t) -> t != null));
        when(visitorTagMapper.existsTag(VISITOR_ID, 99L)).thenReturn(false);

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, null, "新客户");

        verify(tagMapper).insert(argThat((TagEntity t) ->
                "CUSTOM".equals(t.getSource()) && "新客户".equals(t.getName())));
        assertThat(result.id()).isEqualTo(99L);
    }

    @Test
    @DisplayName("重复打标签 -> 幂等跳过，不抛异常")
    void addVisitorTag_duplicate_idempotent() {
        TagEntity tag = TagEntity.builder().id(1L).name("VIP").color("#F59E0B").source("PRESET").build();
        when(tagMapper.selectById(1L)).thenReturn(tag);
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(true);

        TagVO result = service.addVisitorTag(SESSION_ID, OPERATOR_ID, 1L, null);

        assertThat(result.id()).isEqualTo(1L);
        verify(visitorTagMapper, never()).insert(argThat((VisitorTagEntity e) -> true));
        verify(tagMapper, never()).atomicIncrUsageCount(1L);
    }

    @Test
    @DisplayName("移除访客标签 -> 删关联 + usage_count 自减 + 失效缓存")
    void removeVisitorTag_success() {
        when(visitorTagMapper.existsTag(VISITOR_ID, 1L)).thenReturn(true);

        service.removeVisitorTag(SESSION_ID, OPERATOR_ID, 1L);

        verify(visitorTagMapper).deleteByVisitorIdAndTagId(VISITOR_ID, 1L);
        verify(tagMapper).atomicDecrUsageCount(1L);
        verify(redisTemplate).delete("visitor:tags:" + VISITOR_ID);
    }

    @Test
    @DisplayName("打会话级标签 -> 写关联表 + 更新计数（不失效访客缓存）")
    void addSessionTag_byId_success() {
        TagEntity tag = TagEntity.builder().id(2L).name("投诉").color("#EF4444").source("PRESET").build();
        when(tagMapper.selectById(2L)).thenReturn(tag);
        when(conversationTagMapper.existsTag(SESSION_ID, 2L)).thenReturn(false);

        TagVO result = service.addSessionTag(SESSION_ID, OPERATOR_ID, 2L, null);

        assertThat(result.id()).isEqualTo(2L);
        assertThat(result.name()).isEqualTo("投诉");

        verify(conversationTagMapper).insert(argThat((ConversationTagEntity e) ->
                SESSION_ID.equals(e.getSessionId())
                && Long.valueOf(2L).equals(e.getTagId())
                && OPERATOR_ID.equals(e.getTaggedBy())));

        verify(tagMapper).atomicIncrUsageCount(2L);
        // 会话级标签不应失效访客缓存
        verify(redisTemplate, never()).delete(anyString());
    }
}
