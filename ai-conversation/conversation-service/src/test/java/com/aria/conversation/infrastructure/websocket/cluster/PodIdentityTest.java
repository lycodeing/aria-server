package com.aria.conversation.infrastructure.websocket.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PodIdentity")
class PodIdentityTest {

    @Mock
    RabbitAdmin rabbitAdmin;

    @Test
    @DisplayName("afterPropertiesSet 后 get() 返回队列名")
    void get_returns_queue_name() throws Exception {
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenReturn("spring.gen-test123");
        PodIdentity podIdentity = new PodIdentity(rabbitAdmin);
        podIdentity.afterPropertiesSet();
        assertThat(podIdentity.get()).isEqualTo("spring.gen-test123");
    }

    @Test
    @DisplayName("isLocal 对自身 podId 返回 true，对其他返回 false")
    void isLocal_returns_correct_result() throws Exception {
        when(rabbitAdmin.declareQueue(any(Queue.class))).thenReturn("spring.gen-test123");
        PodIdentity podIdentity = new PodIdentity(rabbitAdmin);
        podIdentity.afterPropertiesSet();
        assertThat(podIdentity.isLocal("spring.gen-test123")).isTrue();
        assertThat(podIdentity.isLocal("spring.gen-other")).isFalse();
    }
}
