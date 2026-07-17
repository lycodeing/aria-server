package com.aria.conversation.infrastructure.config;

import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsAgentConfigProviderTest {

    @Mock AuthClient authClient;
    ObjectMapper objectMapper = new ObjectMapper();
    CsAgentConfigProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CsAgentConfigProvider(authClient, objectMapper);
    }

    @Test
    void getMaxSessions_returnsRemoteValue() throws Exception {
        when(authClient.getSystemConfigValue("cs.agent.config"))
                .thenReturn("{\"maxSessionsPerAgent\":3}");

        assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(3);
    }

    @Test
    void getMaxSessions_usesCache_onSecondCall() throws Exception {
        when(authClient.getSystemConfigValue("cs.agent.config"))
                .thenReturn("{\"maxSessionsPerAgent\":3}");

        provider.getMaxSessionsPerAgent();
        provider.getMaxSessionsPerAgent();

        verify(authClient, times(1)).getSystemConfigValue(any());
    }

    @Test
    void getMaxSessions_returnsDefault_whenAuthClientReturnsNull() {
        when(authClient.getSystemConfigValue("cs.agent.config")).thenReturn(null);

        assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(5);
    }

    @Test
    void getMaxSessions_returnsDefault_whenAuthClientThrows() {
        when(authClient.getSystemConfigValue("cs.agent.config"))
                .thenThrow(new RuntimeException("network error"));

        assertThat(provider.getMaxSessionsPerAgent()).isEqualTo(5);
    }
}
