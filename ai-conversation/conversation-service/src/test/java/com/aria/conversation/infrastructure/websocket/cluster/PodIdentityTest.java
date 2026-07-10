package com.aria.conversation.infrastructure.websocket.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PodIdentity")
class PodIdentityTest {

    @Test
    @DisplayName("get() 返回 AnonymousQueue 的队列名")
    void get_returns_queue_name() {
        AnonymousQueue queue = new AnonymousQueue();
        PodIdentity podIdentity = new PodIdentity(queue);
        assertThat(podIdentity.get()).isEqualTo(queue.getName());
        assertThat(podIdentity.get()).isNotBlank();
    }

    @Test
    @DisplayName("isLocal 对自身 podId 返回 true，对其他返回 false")
    void isLocal_returns_correct_result() {
        AnonymousQueue queue = new AnonymousQueue();
        PodIdentity podIdentity = new PodIdentity(queue);
        assertThat(podIdentity.isLocal(queue.getName())).isTrue();
        assertThat(podIdentity.isLocal("spring.gen-other-pod")).isFalse();
    }
}
