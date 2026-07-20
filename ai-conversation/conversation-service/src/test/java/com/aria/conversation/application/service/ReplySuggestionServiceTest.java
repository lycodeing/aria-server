package com.aria.conversation.application.service;

import com.aria.conversation.application.dto.ReplySuggestionDTO;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplySuggestionServiceTest {

    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOperations;
    @Mock
    ConversationHistoryRepository historyRepository;
    @Mock
    KnowledgeServiceClient knowledgeServiceClient;
    @Mock
    DynamicModelFactory modelFactory;

    private ReplySuggestionService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownExecutor();
        }
    }

    @Test
    void getSuggestions_withCollidingMessages_usesSeparateCacheEntries() {
        Map<String, String> cache = new HashMap<>();
        setUpCache(cache);
        when(historyRepository.findAll("session-1")).thenReturn(List.of());
        when(knowledgeServiceClient.search("Aa")).thenReturn(List.of(hit("first result")));
        when(knowledgeServiceClient.search("BB")).thenReturn(List.of(hit("second result")));
        service = new ReplySuggestionService(redisTemplate, historyRepository, knowledgeServiceClient, modelFactory);

        List<ReplySuggestionDTO> first = service.getSuggestions("session-1", "Aa");
        List<ReplySuggestionDTO> second = service.getSuggestions("session-1", "BB");

        assertThat(first).extracting(ReplySuggestionDTO::content).containsExactly("first result");
        assertThat(second).extracting(ReplySuggestionDTO::content).containsExactly("second result");
    }

    @Test
    void getSuggestions_withCachedMessage_doesNotReadHistory() {
        Map<String, String> cache = new HashMap<>();
        setUpCache(cache);
        when(historyRepository.findAll("session-1")).thenReturn(List.of());
        when(knowledgeServiceClient.search("hello")).thenReturn(List.of(hit("cached result")));
        service = new ReplySuggestionService(redisTemplate, historyRepository, knowledgeServiceClient, modelFactory);
        service.getSuggestions("session-1", "hello");

        verify(historyRepository).findAll("session-1");
        verify(historyRepository, never()).findAll("session-2");
        service.getSuggestions("session-1", "hello");

        verify(historyRepository).findAll("session-1");
        verify(knowledgeServiceClient).search("hello");
    }

    @Test
    void getSuggestions_whenHistoryReadFails_keepsKnowledgeBaseSuggestions() {
        setUpCache(new HashMap<>());
        when(historyRepository.findAll("session-1")).thenThrow(new IllegalStateException("redis unavailable"));
        when(knowledgeServiceClient.search("hello")).thenReturn(List.of(hit("KB result")));
        service = new ReplySuggestionService(redisTemplate, historyRepository, knowledgeServiceClient, modelFactory);

        List<ReplySuggestionDTO> result = service.getSuggestions("session-1", "hello");

        assertThat(result).extracting(ReplySuggestionDTO::content).containsExactly("KB result");
    }

    private void setUpCache(Map<String, String> cache) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> cache.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            cache.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    private KnowledgeSearchResult.Hit hit(String content) {
        return KnowledgeSearchResult.Hit.builder().content(content).score(0.8).build();
    }
}
