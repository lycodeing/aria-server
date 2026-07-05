package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionChatMemoryStoreTest {

    @Mock private ConversationHistoryRepository historyRepo;
    private SessionChatMemoryStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = new SessionChatMemoryStore(historyRepo);
    }

    @Test
    void getMessages_convertsToLangChain4jTypes() {
        when(historyRepo.findAll("s1")).thenReturn(List.of(
            ConversationMessage.of("user", "hello", 1L),
            ConversationMessage.of("assistant", "hi", 2L)
        ));
        var messages = store.getMessages("s1");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void updateMessages_callsSaveAll() {
        store.updateMessages("s1", List.of(UserMessage.from("hello"), AiMessage.from("hi")));
        verify(historyRepo).saveAll(eq("s1"), anyList());
    }

    @Test
    void deleteMessages_callsDelete() {
        store.deleteMessages("s1");
        verify(historyRepo).delete("s1");
    }
}
