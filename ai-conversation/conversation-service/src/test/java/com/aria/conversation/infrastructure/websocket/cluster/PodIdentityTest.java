package com.aria.conversation.infrastructure.websocket.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PodIdentity")
class PodIdentityTest {

    @Test
    @DisplayName("get() 返回非空的 UUID 字符串")
    void get_returns_non_blank_id() {
        PodIdentity podIdentity = new PodIdentity();
        assertThat(podIdentity.get()).isNotBlank();
        assertThat(podIdentity.get()).hasSize(16);
    }

    @Test
    @DisplayName("同一实例多次调用 get() 返回相同值")
    void get_returns_stable_id() {
        PodIdentity podIdentity = new PodIdentity();
        assertThat(podIdentity.get()).isEqualTo(podIdentity.get());
    }

    @Test
    @DisplayName("isLocal 对自身 podId 返回 true，对其他返回 false")
    void isLocal_returns_correct_result() {
        PodIdentity podIdentity = new PodIdentity();
        assertThat(podIdentity.isLocal(podIdentity.get())).isTrue();
        assertThat(podIdentity.isLocal("other-pod-id-123456")).isFalse();
    }
}
