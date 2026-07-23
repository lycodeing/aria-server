package com.aria.conversation.application.service;

import com.aria.conversation.application.exception.ServiceOfflineException;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionQueueService.enqueue() — 业务时间前置校验")
class SessionQueueEnqueueOfflineTest {

    @Mock SessionQueueRepository       queueRepository;
    @Mock BusinessHoursService         businessHoursService;
    @Mock RabbitTemplate               rabbitTemplate;
    @Mock ConversationMessagePublisher publisher;

    private SessionQueueService service;

    @BeforeEach
    void setUp() {
        // 构造器参数顺序：queueRepository, agentRegistry, publisher, rabbitTemplate,
        //   eventsExchange, persistRepository, csatService, visitorNotifier, businessHoursService
        service = new SessionQueueService(
                queueRepository, null, publisher, rabbitTemplate,
                "cs.conversation.events", null, null, null,
                businessHoursService);
    }

    @Test
    @DisplayName("非服务时间 enqueue 抛出 ServiceOfflineException")
    void enqueue_outsideBusinessHours_throws() {
        when(businessHoursService.isOpen(any(ZonedDateTime.class))).thenReturn(false);
        when(businessHoursService.nextOpenTime(any(ZonedDateTime.class)))
                .thenReturn("2026-07-24 09:00");

        assertThatThrownBy(() -> service.enqueue("sess-001", "Alice", "咨询", "tag1"))
                .isInstanceOf(ServiceOfflineException.class)
                .hasMessageContaining("当前不在服务时间")
                .satisfies(ex -> {
                    ServiceOfflineException soe = (ServiceOfflineException) ex;
                    org.assertj.core.api.Assertions.assertThat(soe.getNextOpenTime())
                            .isEqualTo("2026-07-24 09:00");
                });
    }

    @Test
    @DisplayName("服务时间内 enqueue 正常入队，不抛出异常")
    void enqueue_withinBusinessHours_noException() {
        when(businessHoursService.isOpen(any(ZonedDateTime.class))).thenReturn(true);
        // queueRepository.save() 默认 Mock — no-op (void method, Mockito does nothing by default)
        // rabbitTemplate.convertAndSend() — no-op by default
        // publisher.publishSessionStart() — no-op by default

        assertThatCode(() -> service.enqueue("sess-002", "Bob", "咨询", "tag2"))
                .doesNotThrowAnyException();
    }
}
