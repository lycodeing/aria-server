package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.mapper.DomainMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentSlotMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentToolMapper;
import com.aria.conversation.infrastructure.dit.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainRepository")
class DomainRepositoryTest {

    @Mock private RedisCacheHelper cache;
    @Mock private DomainMapper domainMapper;
    @Mock private IntentMapper intentMapper;
    @Mock private IntentSlotMapper slotMapper;
    @Mock private IntentToolMapper intentToolMapper;
    @Mock private ToolMapper toolMapper;

    private DomainRepository repo;

    @BeforeEach
    void setUp() {
        repo = new DomainRepository(cache, new ObjectMapper(),
                domainMapper, intentMapper, slotMapper, intentToolMapper, toolMapper);
    }

    @Test
    @DisplayName("findByCode: Redis HIT，直接返回缓存，不查 DB")
    void findByCode_cacheHit_noDbCall() throws Exception {
        DomainConfig config = new DomainConfig("ecommerce", "电商", null, null, List.of());
        String json = new ObjectMapper().writeValueAsString(config);
        when(cache.get("dit:domain:ecommerce")).thenReturn(json);

        Optional<DomainConfig> result = repo.findByCode("ecommerce");

        assertTrue(result.isPresent());
        assertEquals("ecommerce", result.get().code());
        verify(domainMapper, never()).findByCode(anyString()); // 不查 DB
    }

    @Test
    @DisplayName("findByCode: Redis MISS，DB 也无数据，返回 empty")
    void findByCode_cacheMiss_dbMiss_returnsEmpty() {
        when(cache.get(anyString())).thenReturn(null);
        when(domainMapper.findByCode("unknown")).thenReturn(Optional.empty());

        assertTrue(repo.findByCode("unknown").isEmpty());
    }

    @Test
    @DisplayName("findByCode: Redis MISS，从 DB 加载并写缓存")
    void findByCode_cacheMiss_dbHit_writesCache() {
        when(cache.get(anyString())).thenReturn(null);

        DomainDO domain = new DomainDO();
        domain.setId(1L);
        domain.setCode("ecommerce");
        domain.setName("电商");
        domain.setEnabled(true);

        when(domainMapper.findByCode("ecommerce")).thenReturn(Optional.of(domain));
        when(intentMapper.findByDomainId(1L)).thenReturn(List.of());

        Optional<DomainConfig> result = repo.findByCode("ecommerce");

        assertTrue(result.isPresent());
        assertEquals("电商", result.get().name());
        verify(cache).set(eq("dit:domain:ecommerce"), anyString(), any()); // 写缓存
    }

    @Test
    @DisplayName("findByCode: 缓存反序列化失败，回退 DB 并重建缓存")
    void findByCode_cacheCorrupted_fallsBackToDb() {
        when(cache.get("dit:domain:ecommerce")).thenReturn("corrupted-json{{{");

        DomainDO domain = new DomainDO();
        domain.setId(1L);
        domain.setCode("ecommerce");
        domain.setName("电商");
        domain.setEnabled(true);

        when(domainMapper.findByCode("ecommerce")).thenReturn(Optional.of(domain));
        when(intentMapper.findByDomainId(1L)).thenReturn(List.of());

        Optional<DomainConfig> result = repo.findByCode("ecommerce");

        assertTrue(result.isPresent());
        verify(cache).delete("dit:domain:ecommerce");  // 清掉坏缓存
        verify(cache).set(eq("dit:domain:ecommerce"), anyString(), any()); // 重建缓存
    }

    @Test
    @DisplayName("evict: 调用 cache.delete")
    void evict_deletesCache() {
        repo.evict("ecommerce");
        verify(cache).delete("dit:domain:ecommerce");
    }
}
