package com.helloai.core.dlx.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.dlx.MqDeadLetterArchiveRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DeadLetterRecoveryServiceImpl} A4 S2 单元测试：死信台账重放与窗口分页。
 *
 * <p>聚焦新增行为：
 * <ul>
 *   <li>{@code replay}：成功路径「去重重置 → 原 exchange/routingKey 重发 → replayed_at CAS」；
 *       已重放冲突不重发；eventId 缺失 fail-close；发送失败不标 replayed_at；
 *       CAS 冲突报并发重放；台账不存在报错；</li>
 *   <li>{@code listArchive}：replayed 过滤 SQL 分支、count+limit/offset 手工分页、
 *       total=0 空转、页码尺寸钳制。</li>
 * </ul>
 *
 * <p>纯 Mockito + mock JdbcTemplate（执行方案 §5：S2 不依赖 MyBatis 容器）。</p>
 */
@DisplayName("DeadLetterRecoveryService 死信台账重放（A4 S2）")
class DeadLetterRecoveryServiceImplTest {

    private static final String EVENT_ID = "evt-9000";
    private static final String EXCHANGE = "helloai.execution.command";
    private static final String ROUTING_KEY = "execution.command.rk";
    private static final String BODY = "{\"eventId\":\"" + EVENT_ID + "\",\"subTaskId\":66,\"agentId\":7}";

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private RabbitTemplate rabbitTemplate;
    private DeadLetterRecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        redisTemplate = mock(StringRedisTemplate.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new DeadLetterRecoveryServiceImpl(jdbcTemplate, objectMapper, redisTemplate, rabbitTemplate);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubFindById(MqDeadLetterArchiveRow row) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(row == null ? List.of() : List.of(row));
    }

    private MqDeadLetterArchiveRow archiveRow() {
        MqDeadLetterArchiveRow row = new MqDeadLetterArchiveRow();
        row.setId(7L);
        row.setOriginalExchange(EXCHANGE);
        row.setOriginalRoutingKey(ROUTING_KEY);
        row.setBody(BODY);
        row.setCreatedAt(OffsetDateTime.now().minusHours(1));
        return row;
    }

    // ══════════════════════════════════════════════════════════════
    //  replay：重放成功路径
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("replay 成功：去重重置 → 原 exchange/routingKey 重发 → replayed_at CAS 写回")
    void shouldReplaySuccessfully() {
        stubFindById(archiveRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.replay(7L);

        // 1) 重置去重台账：DB 行 + Redis 键（两腿同删）
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq("DELETE FROM event_consumption_log WHERE message_id = ?"),
                argsCaptor.capture());
        assertThat(argsCaptor.getValue()[0]).isEqualTo(EVENT_ID);
        verify(redisTemplate).delete("mq:dedup:" + EVENT_ID);

        // 2) 按原 exchange / routingKey + 原始字节体重发
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(EXCHANGE), eq(ROUTING_KEY), messageCaptor.capture());
        assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(BODY);

        // 3) CAS 写 replayed_at（共两次 update：DELETE + UPDATE）
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getAllValues().get(1))
                .startsWith("UPDATE mq_dead_letter_archive SET replayed_at = now()")
                .contains("replayed_at IS NULL");
    }

    @Test
    @DisplayName("replay：headers 快照有 contentType → 重发消息回放该 content-type")
    void shouldReplayWithSnapshottedContentType() {
        MqDeadLetterArchiveRow row = archiveRow();
        row.setHeaders("{\"contentType\":\"text/plain\"}");
        stubFindById(row);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.replay(7L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getContentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("replay：headers 缺失 → content-type 回落默认 JSON")
    void shouldFallbackToJsonContentType() {
        stubFindById(archiveRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.replay(7L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    }

    // ══════════════════════════════════════════════════════════════
    //  replay：fail-close 分支
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("replay：台账不存在 → BizException（fail-close）")
    void shouldThrowWhenArchiveMissing() {
        stubFindById(null);

        assertThatThrownBy(() -> service.replay(99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verifyNoInteractions(redisTemplate, rabbitTemplate);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("replay：已重放（replayed_at 非空）→ BizException 冲突，不重发")
    void shouldRejectAlreadyReplayed() {
        MqDeadLetterArchiveRow row = archiveRow();
        row.setReplayedAt(OffsetDateTime.now().minusMinutes(5));
        stubFindById(row);

        assertThatThrownBy(() -> service.replay(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已重放");

        verifyNoInteractions(redisTemplate, rabbitTemplate);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("replay：body 取不到 eventId → BizException 禁止盲发")
    void shouldFailCloseWhenMessageIdMissing() {
        MqDeadLetterArchiveRow row = archiveRow();
        row.setBody("{\"subTaskId\":66}");
        stubFindById(row);

        assertThatThrownBy(() -> service.replay(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法识别消息标识");

        verifyNoInteractions(redisTemplate, rabbitTemplate);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("replay：发送失败 → BizException，不写 replayed_at（仅 DELETE 一次 update）")
    void shouldNotMarkReplayedWhenSendFails() {
        stubFindById(archiveRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        assertThatThrownBy(() -> service.replay(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发送失败");

        // 仅 DELETE（重置先于重发）；replayed_at UPDATE 未发生 → 可整体重试
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getValue()).startsWith("DELETE FROM event_consumption_log");
    }

    @Test
    @DisplayName("replay：replayed_at CAS 冲突（影响 0 行）→ BizException 并发重放")
    void shouldReportConcurrentReplay() {
        stubFindById(archiveRow());
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).startsWith("DELETE") ? 1 : 0);

        assertThatThrownBy(() -> service.replay(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("并发重放");
    }

    // ══════════════════════════════════════════════════════════════
    //  listArchive：窗口分页 + replayed 过滤
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listArchive：仅未重放时 SQL 带 replayed_at IS NULL，count+limit/offset 手工分页")
    void shouldListUnreplayedOnlyWithPaging() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(2);
        OffsetDateTime to = OffsetDateTime.now();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        MqDeadLetterArchiveRow r1 = archiveRow();
        MqDeadLetterArchiveRow r2 = archiveRow();
        r2.setId(8L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(r1, r2));

        IPage<MqDeadLetterArchiveRow> result = service.listArchive(from, to, false, 2, 5);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("replayed_at IS NULL");
        // 参数序：from, to, size, offset
        assertThat(argsCaptor.getValue()[2]).isEqualTo(5L);
        assertThat(argsCaptor.getValue()[3]).isEqualTo(5L);
    }

    @Test
    @DisplayName("listArchive：includeReplayed=true → SQL 不过滤 replayed_at")
    void shouldListAllIncludingReplayed() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(2);
        OffsetDateTime to = OffsetDateTime.now();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(archiveRow()));

        service.listArchive(from, to, true, 1, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue()).doesNotContain("replayed_at IS NULL");
    }

    @Test
    @DisplayName("listArchive：total=0 → 空页直返，不触行查询")
    void shouldReturnEmptyPageWithoutRowQuery() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        IPage<MqDeadLetterArchiveRow> result =
                service.listArchive(OffsetDateTime.now().minusHours(1), OffsetDateTime.now(), false, 1, 10);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("listArchive：非法页码/页尺寸钳制到 1")
    void shouldClampInvalidPageParams() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(2);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(archiveRow()));

        IPage<MqDeadLetterArchiveRow> result = service.listArchive(from, from, false, 0, -5);

        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), argsCaptor.capture());
        // 钳制后 size=1、offset=0
        assertThat(argsCaptor.getValue()[2]).isEqualTo(1L);
        assertThat(argsCaptor.getValue()[3]).isEqualTo(0L);
    }
}