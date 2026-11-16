package com.helloai.core.system.notification.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.core.shared.event.NotificationCreatedEvent;
import com.helloai.core.system.entity.UserNotification;
import com.helloai.core.system.mapper.UserNotificationMapper;
import com.helloai.core.system.notification.NotificationEvent;
import com.helloai.core.system.notification.NotificationType;
import com.helloai.core.system.notification.Severity;
import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link NotificationServiceImpl} 单一出口单测（MSG-1，设计 §3.2/§3.3/§3.10）。
 *
 * <p>锁定确定性口径：开关 OFF 停发、白名单未登记不发、SILENT 不发、幂等撞键不二次响铃、
 * BUCKET 折叠累加、事件 id 与落库 id 同源、dedupe_key 主/例外通道格式、读取 OFF 返回空。
 * COALESCE NULL 广播的 DB 层去重由 {@code UserNotificationDedupeIT}（真实 PG）锁定。</p>
 */
@DisplayName("MSG-1 NotificationService 单一出口")
class NotificationServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), UserNotification.class);
    }

    private SysConfigService sysConfigService;
    private ApplicationEventPublisher eventPublisher;
    private UserNotificationMapper mapper;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        sysConfigService = mock(SysConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        mapper = mock(UserNotificationMapper.class);
        service = spy(new NotificationServiceImpl(sysConfigService, eventPublisher));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(sysConfigService.isNotificationEnabled()).thenReturn(true);
    }

    private NotificationEvent exceptionEvent(NotificationType type) {
        return NotificationEvent.builder()
                .type(type)
                .sourceType("TASK")
                .sourceId(100L)
                .instanceKey("attempt-1")
                .receiverId(9L)
                .payload(Map.of("k", "v"))
                .build();
    }

    @Test
    @DisplayName("开关 OFF：publish 首行早退，不写库、不发事件")
    void publishEarlyReturnsWhenDisabled() {
        when(sysConfigService.isNotificationEnabled()).thenReturn(false);

        service.publish(exceptionEvent(NotificationType.PLAN_DRAFT_READY));

        verifyNoInteractions(mapper);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("主通道未登记事件码：白名单默认不发")
    void unregisteredEventCodeNotPublished() {
        NotificationEvent event = NotificationEvent.builder()
                .timelineEventCode("some_unregistered_code")
                .instanceKey("1")
                .receiverId(9L)
                .build();

        service.publish(event);

        verifyNoInteractions(mapper);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("主通道 SILENT 事件码：显式登记不发")
    void silentEventCodeNotPublished() {
        NotificationEvent event = NotificationEvent.builder()
                .timelineEventCode("task_plan_llm_call_start")
                .instanceKey("1")
                .receiverId(9L)
                .build();

        service.publish(event);

        verifyNoInteractions(mapper);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("例外通道落库成功：字段装配完整 + 事件 id 与落库 id 同源")
    void publishAssemblesFieldsAndEmitsMatchingId() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.publish(exceptionEvent(NotificationType.PLAN_DRAFT_READY));

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sevCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertIgnoreDuplicate(idCaptor.capture(), eq(9L), typeCaptor.capture(),
                sevCaptor.capture(), any(), any(), keyCaptor.capture(), eq("TASK"), eq(100L), any());
        assertThat(typeCaptor.getValue()).isEqualTo("PLAN_DRAFT_READY");
        assertThat(sevCaptor.getValue()).isEqualTo("NORMAL");
        // 例外通道键：{type}:{sourceType}:{sourceId}:{instanceKey}
        assertThat(keyCaptor.getValue()).isEqualTo("PLAN_DRAFT_READY:TASK:100:attempt-1");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        NotificationCreatedEvent emitted = (NotificationCreatedEvent) eventCaptor.getValue();
        assertThat(emitted.getNotificationId()).isEqualTo(idCaptor.getValue());
        assertThat(emitted.getReceiverId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("主通道 refType 跟随事件 sourceType：task 级技能无承接事件 → TASK（审查修复 A1）")
    void mainChannelRefTypeFollowsSourceType() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        NotificationEvent event = NotificationEvent.builder()
                .timelineEventCode("task_plan_skill_unmatched")
                .instanceKey("777")
                .sourceType("TASK").sourceId(10L).receiverId(9L)
                .build();

        service.publish(event);

        // 类型仍映射 SUBTASK_NO_CANDIDATE，但深链归属取 sourceType=TASK，不再把 taskId 当子任务 id
        verify(mapper).insertIgnoreDuplicate(any(), eq(9L), eq("SUBTASK_NO_CANDIDATE"), eq("HIGH"),
                any(), any(), any(), eq("TASK"), eq(10L), any());
    }

    @Test
    @DisplayName("例外通道未给 sourceType：refType 兜底取类型注册表（SUB_TASK）")
    void exceptionChannelRefTypeFallsBackToRegistry() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        NotificationEvent event = NotificationEvent.builder()
                .type(NotificationType.SUBTASK_DEAD_LETTER)
                .sourceId(1L).instanceKey("i").receiverId(9L)
                .build();

        service.publish(event);

        verify(mapper).insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(),
                eq("SUB_TASK"), any(), any());
    }

    @Test
    @DisplayName("ONCE 幂等撞键（rows=0）：不二次响铃、不累加")
    void onceDuplicateDoesNotEmit() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        service.publish(exceptionEvent(NotificationType.PLAN_DRAFT_READY));

        verify(eventPublisher, never()).publishEvent(any());
        verify(mapper, never()).incrementBucketCount(any(), any());
    }

    @Test
    @DisplayName("BUCKET 撞键后累加异常：降级吞掉不穿过事务边界、不二次响铃（审查修复 B2）")
    void bucketIncrementFailureSwallowed() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        doThrow(new RuntimeException("db down")).when(mapper).incrementBucketCount(any(), any());
        NotificationEvent event = NotificationEvent.builder()
                .timelineEventCode("sub_task_no_candidate")
                .instanceKey("777")
                .sourceType("SUB_TASK").sourceId(20L).receiverId(9L)
                .build();

        assertThatCode(() -> service.publish(event)).doesNotThrowAnyException();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("事件严重度覆盖类型默认值")
    void severityOverrideWins() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        NotificationEvent event = NotificationEvent.builder()
                .type(NotificationType.PLAN_DRAFT_READY)
                .sourceType("TASK").sourceId(1L).instanceKey("i").receiverId(9L)
                .severity(Severity.URGENT)
                .build();

        service.publish(event);

        verify(mapper).insertIgnoreDuplicate(any(), any(), any(), eq("URGENT"), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("写库异常 NORMAL：降级吞掉，不抛出、不发事件（设计 §3.6 不改变业务事务结果）")
    void writeFailureNormalSwallowed() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.publish(exceptionEvent(NotificationType.PLAN_DRAFT_READY)))
                .doesNotThrowAnyException();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("写库异常 URGENT：error 留痕后同样吞掉，不阻断业务")
    void writeFailureUrgentSwallowed() {
        when(mapper.insertIgnoreDuplicate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        NotificationEvent event = NotificationEvent.builder()
                .type(NotificationType.SUBTASK_DEAD_LETTER)
                .sourceType("SUB_TASK").sourceId(1L).instanceKey("i").receiverId(9L)
                .severity(Severity.URGENT)
                .build();

        assertThatCode(() -> service.publish(event)).doesNotThrowAnyException();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("读取 OFF：unreadCount=0、pageByReceiver 空页，不查库")
    void readsReturnEmptyWhenDisabled() {
        when(sysConfigService.isNotificationEnabled()).thenReturn(false);

        assertThat(service.unreadCount(9L)).isZero();
        assertThat(service.pageByReceiver(9L, null, 1, 20).getRecords()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("markRead 归属不符：幂等跳过，不更新")
    void markReadRejectsNonOwner() {
        UserNotification n = new UserNotification();
        n.setId(5L);
        n.setReceiverId(8L);
        doReturn(n).when(service).getById(5L);

        service.markRead(9L, 5L);

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("markRead 归属未识别（receiverId=null）：fail-close 不查库、不更新")
    void markReadFailsCloseWhenReceiverNull() {
        service.markRead(null, 5L);

        verify(service, never()).getById(any());
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("archive 归属未识别（receiverId=null）：fail-close 不查库、不更新")
    void archiveFailsCloseWhenReceiverNull() {
        service.archive(null, 5L);

        verify(service, never()).getById(any());
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("纯函数 bucketWindow：同窗口同键、跨窗口异键")
    void bucketWindowFoldsByHour() {
        long base = 3_600_000L * 100;
        assertThat(NotificationServiceImpl.bucketWindow(base, 1))
                .isEqualTo(NotificationServiceImpl.bucketWindow(base + 1_800_000L, 1));
        assertThat(NotificationServiceImpl.bucketWindow(base, 1))
                .isNotEqualTo(NotificationServiceImpl.bucketWindow(base + 3_600_000L, 1));
    }

    @Test
    @DisplayName("纯函数 renderSummary：占位替换 / 无 payload 原样返回")
    void renderSummarySubstitutesPlaceholders() {
        assertThat(NotificationServiceImpl.renderSummary("累计 {count} 次", Map.of("count", 3)))
                .isEqualTo("累计 3 次");
        assertThat(NotificationServiceImpl.renderSummary("固定文案", null)).isEqualTo("固定文案");
        assertThat(NotificationServiceImpl.renderSummary("固定文案", Map.of())).isEqualTo("固定文案");
    }
}