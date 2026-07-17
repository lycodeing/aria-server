package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.application.service.VisitorSessionService;
import com.aria.conversation.domain.SessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionControllerTest {

    @Mock  VisitorSessionService visitorSessionService;
    @InjectMocks ChatSessionController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void init_validAnonymousId_returnsSessionId() throws Exception {
        when(visitorSessionService.getOrCreate(eq("test-anon-id-1234"), any(), any(), any()))
                .thenReturn(new InitSessionResult("guest-abc123", SessionStatus.AI_CHAT, true));

        mockMvc.perform(post("/api/v1/chat/session/init")
                        .header("X-Anonymous-Id", "test-anon-id-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("guest-abc123"))
                .andExpect(jsonPath("$.data.status").value("AI_CHAT"))
                .andExpect(jsonPath("$.data.isNew").value(true));
    }

    @Test
    void init_missingAnonymousIdHeader_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/chat/session/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void init_noRequestBody_still_works() throws Exception {
        when(visitorSessionService.getOrCreate(eq("test-anon-id-5678"), any(), any(), any()))
                .thenReturn(new InitSessionResult("guest-xyz789", SessionStatus.AI_CHAT, true));

        mockMvc.perform(post("/api/v1/chat/session/init")
                        .header("X-Anonymous-Id", "test-anon-id-5678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("guest-xyz789"));
    }
}
