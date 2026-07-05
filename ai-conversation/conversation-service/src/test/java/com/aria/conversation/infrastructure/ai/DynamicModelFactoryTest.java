package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class DynamicModelFactoryTest {

    @Mock private AiModelConfigProvider configProvider;
    @Mock private LlmModelBuilder openAiBuilder;
    @Mock private LlmModelBuilder anthropicBuilder;
    @Mock private ChatModel mockChatModel;
    @Mock private ChatModel mockAnthropicModel;
    @Mock private StreamingChatModel mockStreamingModel;

    private DynamicModelFactory factory;

    private AiModelConfig openAiCfg() {
        return new AiModelConfig(1L, "test", "OpenAI", AiProtocol.OPENAI,
                "https://api.openai.com/v1", "sk-test", "gpt-4o-mini",
                0.7, 2048, 30);
    }

    private AiModelConfig anthropicCfg() {
        return new AiModelConfig(2L, "claude", "Anthropic", AiProtocol.ANTHROPIC,
                "https://api.anthropic.com", "sk-ant", "claude-3-5-sonnet-20241022",
                0.7, 2048, 30);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(openAiBuilder.supports(AiProtocol.OPENAI)).thenReturn(true);
        when(openAiBuilder.supports(AiProtocol.ANTHROPIC)).thenReturn(false);
        when(anthropicBuilder.supports(AiProtocol.ANTHROPIC)).thenReturn(true);
        when(anthropicBuilder.supports(AiProtocol.OPENAI)).thenReturn(false);
        when(openAiBuilder.buildChatModel(openAiCfg())).thenReturn(mockChatModel);
        when(openAiBuilder.buildStreamingModel(openAiCfg())).thenReturn(mockStreamingModel);
        when(anthropicBuilder.buildChatModel(anthropicCfg())).thenReturn(mockAnthropicModel);
        factory = new DynamicModelFactory(configProvider, List.of(openAiBuilder, anthropicBuilder));
    }

    @Test
    void getChatModel_sameConfig_returnsCachedInstance() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        ChatModel m1 = factory.getChatModel();
        ChatModel m2 = factory.getChatModel();
        assertThat(m1).isSameAs(m2);
    }

    @Test
    void getChatModel_configChanged_returnsNewInstance() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        ChatModel m1 = factory.getChatModel();
        when(configProvider.getActive()).thenReturn(anthropicCfg());
        ChatModel m2 = factory.getChatModel();
        assertThat(m1).isNotSameAs(m2);
    }

    @Test
    void getChatModel_routesAnthropicToAnthropicBuilder() {
        when(configProvider.getActive()).thenReturn(anthropicCfg());
        ChatModel model = factory.getChatModel();
        assertThat(model).isSameAs(mockAnthropicModel);
    }

    @Test
    void currentConfigHash_returnsNonEmpty() {
        when(configProvider.getActive()).thenReturn(openAiCfg());
        assertThat(factory.currentConfigHash()).isNotBlank();
    }
}
