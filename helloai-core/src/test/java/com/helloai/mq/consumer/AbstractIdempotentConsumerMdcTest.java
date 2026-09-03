package com.helloai.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.mq.service.MessageDeduplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0 C4：{@link AbstractIdempotentConsumer} 携带 MDC 上下文重载的单元测试。
 *
 * <p>覆盖：消费执行期间 MDC 业务键可见、退出后清理（正常 / 异常路径）、context 为 null 时
 * 行为与旧签名一致。basic 与 enhanced 两种幂等路径各验证一次。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractIdempotentConsumer(MDC 上下文)")
class AbstractIdempotentConsumerMdcTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MessageDeduplicationService deduplicationService;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** basic 幂等路径（不注入去重服务）。 */
    private TestConsumer newBasicConsumer() {
        return new TestConsumer(jdbcTemplate, null);
    }

    /** enhanced 幂等路径（Redis 去重）。 */
    private TestConsumer newEnhancedConsumer() {
        return new TestConsumer(jdbcTemplate, deduplicationService);
    }

    private static Map<String, String> mdcOf(Long subTaskId, Long taskId) {
        Map<String, String> mdc = new HashMap<>();
        if (subTaskId != null) {
            mdc.put(AbstractIdempotentConsumer.MDC_SUB_TASK_ID, String.valueOf(subTaskId));
        }
        if (taskId != null) {
            mdc.put(AbstractIdempotentConsumer.MDC_TASK_ID, String.valueOf(taskId));
        }
        return mdc;
    }

    @Test
    @DisplayName("basic 路径：执行期间 MDC 可见 sub_task_id/task_id，退出后清理")
    void shouldPutAndClearMdcOnBasicPath() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        TestConsumer consumer = newBasicConsumer();
        AtomicReference<String> subTaskInContext = new AtomicReference<>();
        AtomicReference<String> taskInContext = new AtomicReference<>();

        boolean processed = consumer.tryConsume("msg-1", "TestConsumer", mdcOf(11L, 22L), () -> {
            subTaskInContext.set(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID));
            taskInContext.set(MDC.get(AbstractIdempotentConsumer.MDC_TASK_ID));
        });

        assertThat(processed).isTrue();
        assertThat(subTaskInContext.get()).isEqualTo("11");
        assertThat(taskInContext.get()).isEqualTo("22");
        assertThat(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID)).isNull();
        assertThat(MDC.get(AbstractIdempotentConsumer.MDC_TASK_ID)).isNull();
    }

    @Test
    @DisplayName("enhanced 路径：去重服务路径同样写入并在退出后清理")
    void shouldPutAndClearMdcOnEnhancedPath() {
        when(deduplicationService.isDuplicate("msg-2")).thenReturn(false);
        TestConsumer consumer = newEnhancedConsumer();
        AtomicReference<String> subTaskInContext = new AtomicReference<>();

        boolean processed = consumer.tryConsume("msg-2", "TestConsumer", mdcOf(11L, null), () ->
                subTaskInContext.set(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID)));

        assertThat(processed).isTrue();
        assertThat(subTaskInContext.get()).isEqualTo("11");
        verify(deduplicationService).markConsumed("msg-2", "TestConsumer");
        assertThat(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID)).isNull();
    }

    @Test
    @DisplayName("异常路径：consumerLogic 抛错时 MDC 仍清理（finally 兜底）")
    void shouldClearMdcWhenConsumerLogicThrows() {
        when(deduplicationService.isDuplicate("msg-3")).thenReturn(false);
        TestConsumer consumer = newEnhancedConsumer();

        assertThatThrownBy(() -> consumer.tryConsume("msg-3", "TestConsumer", mdcOf(11L, null),
                () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID)).isNull();
        verify(deduplicationService).markFailed("msg-3");
    }

    @Test
    @DisplayName("mdcContext 为 null：行为与旧签名一致，不写入 MDC")
    void shouldWorkWithoutMdcContext() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        TestConsumer consumer = newBasicConsumer();

        boolean processed = consumer.tryConsume("msg-4", "TestConsumer", null,
                () -> {
                });

        assertThat(processed).isTrue();
        assertThat(MDC.get(AbstractIdempotentConsumer.MDC_SUB_TASK_ID)).isNull();
    }

    /** 可实例化的测试子类（abstract 基类不能直接 new）。 */
    private static class TestConsumer extends AbstractIdempotentConsumer {
        TestConsumer(JdbcTemplate jdbcTemplate, MessageDeduplicationService deduplicationService) {
            super(jdbcTemplate, new ObjectMapper(), deduplicationService);
        }
    }
}