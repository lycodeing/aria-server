package com.aria.conversation.infrastructure.knowledge;

import com.aria.common.sdk.ClientConfig;
import com.aria.sdk.knowledge.KnowledgeClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServiceClientTest {

    @Test
    void constructor_usesSingleAttemptWithinConfiguredDeadline() {
        KnowledgeServiceClient service = new KnowledgeServiceClient(
                "http://localhost:8084", "test-secret", 5);

        KnowledgeClient client = (KnowledgeClient) ReflectionTestUtils.getField(service, "knowledgeClient");
        ClientConfig config = (ClientConfig) ReflectionTestUtils.getField(client, "config");

        Assertions.assertNotNull(config);
        assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getMaxRetries()).isZero();
    }
}
