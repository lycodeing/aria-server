package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class LangChain4jEmbeddingServiceTest {

    @Mock private AiModelConfigProvider configProvider;
    @Mock private EmbeddingProperties props;
    private LangChain4jEmbeddingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(props.batchSize()).thenReturn(2);
        when(props.timeoutSeconds()).thenReturn(30);
        // AiModelConfig: id, name, provider, apiProtocol, baseUrl, apiKey, modelName,
        //                temperature, maxTokens, timeoutSec
        AiModelConfig cfg = new AiModelConfig(1L, "emb", "OpenAI", "openai",
                "https://api.openai.com/v1", "sk-test",
                "text-embedding-3-small", 0.0, 0, 30);
        when(configProvider.getActiveEmbedding()).thenReturn(cfg);
        service = new LangChain4jEmbeddingService(configProvider, props);
    }

    @Test
    void embed_setsVectorsOnChunks() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(mockModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(vec))));
        service.overrideModelForTest(mockModel);

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .content("test text")
                .build();
        service.embed(List.of(chunk));

        assertThat(chunk.getVector()).isEqualTo(vec);
    }

    @Test
    void encode_returnsVector() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        float[] vec = {0.4f, 0.5f};
        when(mockModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(vec)));
        service.overrideModelForTest(mockModel);

        float[] result = service.encode("hello");

        assertThat(result).isEqualTo(vec);
    }
}
