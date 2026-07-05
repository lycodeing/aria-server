package com.aria.conversation.infrastructure.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.mock.StreamingChatModelMock;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FluxStreamingSpike {

    interface StreamAssistant {
        Flux<String> chat(@UserMessage String message);
    }

    @Test
    void aiServices_fluxStreaming_works() {
        StreamingChatModel mockModel = StreamingChatModelMock.thatAlwaysStreams("Hello", " ", "World");

        StreamAssistant assistant = AiServices.builder(StreamAssistant.class)
                .streamingChatModel(mockModel)
                .build();

        StepVerifier.create(assistant.chat("test"))
                .expectNextMatches(token -> !token.isEmpty())
                .thenConsumeWhile(token -> true)
                .verifyComplete();
    }
}
