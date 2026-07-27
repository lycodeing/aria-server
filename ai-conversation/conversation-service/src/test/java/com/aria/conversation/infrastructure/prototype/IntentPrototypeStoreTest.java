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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

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
        // lenient: rebuild() 仅在 protoMap 非空时调用 getMap()，
        // 无 examples 的测试不调用此方法，strict stubbing 会报 UnnecessaryStubbingException
        lenient().when(redissonClient.<String, String>getMap(CustomerServiceCacheConstant.INTENT_PROTOTYPES))
                .thenReturn(rMap);
        store = new IntentPrototypeStore(redissonClient, embeddingService,
                domainRepository, objectMapper);
    }

    private IntentConfig buildIntent(String code, List<String> examples) {
        return new IntentConfig(code, code, null, examples, false, false, null,
                List.of(), List.of(), List.of(), List.of(), 1);
    }

    @Test
    @DisplayName("rebuild: __system__ 域不存在时不写 Redis")
    void rebuild_noSystemDomain_doesNotWriteRedis() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        store.rebuild();

        verify(rMap, never()).putAll(any());
    }

    @Test
    @DisplayName("rebuild: 有 exampleQueries 的意图被写入 Redis")
    void rebuild_withExamples_writesPrototypesToRedis() throws Exception {
        var intent = buildIntent("FAQ_QUERY", List.of("查订单", "看物流"));
        var domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system",
                null, null, null, List.of(intent));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(domain));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild();

        verify(rMap).putAll(argThat(map -> map.containsKey("FAQ_QUERY")));
    }

    @Test
    @DisplayName("rebuild: 无 exampleQueries 的意图不写入 Redis")
    void rebuild_noExamples_skipsIntent() throws Exception {
        var intent = buildIntent("COMPLAINT", List.of()); // 无 examples
        var domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system",
                null, null, null, List.of(intent));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(domain));

        store.rebuild();

        verify(rMap, never()).putAll(any());
    }

    @Test
    @DisplayName("rebuild: JsonProcessingException 时跳过该意图，不中断整体")
    void rebuild_jsonException_skipsIntentContinues() throws Exception {
        var i1 = buildIntent("FAQ_QUERY", List.of("查订单"));
        var i2 = buildIntent("COMPLAINT", List.of("投诉"));
        var domain = new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system",
                null, null, null, List.of(i1, i2));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(domain));
        when(embeddingService.encode(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("mock") {})
                .thenReturn("{\"vector\":[1.0,0.0]}");

        store.rebuild(); // 不应抛异常，COMPLAINT 仍然写入

        verify(rMap).putAll(argThat(map -> map.containsKey("COMPLAINT") && !map.containsKey("FAQ_QUERY")));
    }
}
