package com.helloai.core.agent.service;

import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.event.InboxMessageCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

/**
 * {@link AgentInboxService} 门铃响铃接线单元测试（AgentHub V3 门铃响铃 PR-2）。
 *
 * <p>只聚焦 PR-2 新增行为：收件箱首次落库成功后发布 {@link InboxMessageCreatedEvent}，
 * 幂等重复投递（{@link DuplicateKeyException}）不发事件。用 spy 桩掉
 * {@code ServiceImpl.save()}，隔离 MyBatis-Plus / 数据库。</p>
 */
@DisplayName("AgentInboxService 门铃响铃接线")
class AgentInboxServiceTest {

    private ApplicationEventPublisher eventPublisher;
    private AgentInboxService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = spy(new AgentInboxService(eventPublisher));
    }

    @Test
    @DisplayName("首次落库成功后发布 InboxMessageCreatedEvent 且字段透传")
    void shouldPublishEventWhenSaveSucceeds() {
        doReturn(true).when(service).save(any(AgentInbox.class));

        service.send(7L, "evt-1", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "HIGH");

        ArgumentCaptor<InboxMessageCreatedEvent> captor =
                ArgumentCaptor.forClass(InboxMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InboxMessageCreatedEvent event = captor.getValue();
        assertThat(event.getAgentId()).isEqualTo(7L);
        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getEventType()).isEqualTo("sub_task.assigned");
        assertThat(event.getRefType()).isEqualTo("sub_task");
        assertThat(event.getRefId()).isEqualTo(66L);
    }

    @Test
    @DisplayName("幂等重复投递（DuplicateKey）不发事件")
    void shouldNotPublishEventOnDuplicateKey() {
        doThrow(new DuplicateKeyException("dup")).when(service).save(any(AgentInbox.class));

        service.send(7L, "evt-dup", "sub_task.assigned",
                "新任务已分配", "交付物: xxx", "sub_task", 66L, "NORMAL");

        verify(eventPublisher, never()).publishEvent(any());
    }
}
