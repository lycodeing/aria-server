package com.aria.conversation.infrastructure.prototype;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntentPrototypeStore 原型向量存储")
class IntentPrototypeStoreTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RMap<String, String> rMap;
    @Mock private EmbeddingService embeddingService;
    @Mock private DomainRepository domainRepository;
    @Mock private ObjectMapper objectMapper;

    private IntentPrototypeStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.<String, String>getMap(CustomerServiceCacheConstant.INTENT_PROTOTYPES))
                .thenReturn(rMap);
        store = new IntentPrototypeStore(redissonClient, embeddingService, domainRepository, objectMapper);
    }

    private IntentConfig buildIntent(String code, List<String> examples) {
        return new IntentConfig(code, code, null, examples, false, false, null,
                List.of(), List.of(), List.of(), List.of(), 1);
    }

    private DomainConfig buildDomain(String code, List<IntentConfig> intents) {
        return new DomainConfig(code, code, null, null, null, intents);
    }

    @Test
    @DisplayName("rebuild: 无可用域时不写 Redis（I1修复：改为 findAllEnabled）")
    void rebuild_noDomains_doesNotWriteRedis() {
        when(domainRepository.findAllEnabled()).thenReturn(List.of());

        store.rebuild();

        verify(rMap, never()).putAll(any());
    }

    @Test
    @DisplayName("rebuild: 有 exampleQueries 的意图被写入 Redis")
    void rebuild_withExamples_writesPrototypesToRedis() throws Exception {
        var intent = buildIntent("FAQ_QUERY", List.of("查订单", "看物流"));
        when(domainRepository.findAllEnabled()).thenReturn(
                List.of(buildDomain(DomainCodes.SYSTEM_DOMAIN, List.of(intent))));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();

        verify(rMap).putAll(argThat(map -> map.containsKey("FAQ_QUERY")));
    }

    @Test
    @DisplayName("rebuild: 无 exampleQueries 的意图不写入 Redis")
    void rebuild_noExamples_skipsIntent() {
        var intent = buildIntent("COMPLAINT", List.of());
        when(domainRepository.findAllEnabled()).thenReturn(
                List.of(buildDomain(DomainCodes.SYSTEM_DOMAIN, List.of(intent))));

        store.rebuild();

        verify(rMap, never()).putAll(any());
    }

    @Test
    @DisplayName("rebuild: 跨多个域的意图都被构建（I1修复验证）")
    void rebuild_multiDomain_buildsAllIntents() throws Exception {
        var systemIntent = buildIntent("FAQ_QUERY", List.of("查订单"));
        var domainIntent = buildIntent("query_logistics", List.of("查物流"));
        when(domainRepository.findAllEnabled()).thenReturn(List.of(
                buildDomain(DomainCodes.SYSTEM_DOMAIN, List.of(systemIntent)),
                buildDomain("ecommerce", List.of(domainIntent))));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();

        verify(rMap).putAll(argThat(map ->
                map.containsKey("FAQ_QUERY") && map.containsKey("query_logistics")));
    }

    @Test
    @DisplayName("rebuild: JsonProcessingException 时跳过该意图，不中断整体")
    void rebuild_jsonException_skipsIntentContinues() throws Exception {
        var i1 = buildIntent("FAQ_QUERY", List.of("查订单"));
        var i2 = buildIntent("COMPLAINT", List.of("投诉"));
        when(domainRepository.findAllEnabled()).thenReturn(
                List.of(buildDomain(DomainCodes.SYSTEM_DOMAIN, List.of(i1, i2))));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("mock") {})
                .thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();

        verify(rMap).putAll(argThat(map -> map.containsKey("COMPLAINT") && !map.containsKey("FAQ_QUERY")));
    }
}
