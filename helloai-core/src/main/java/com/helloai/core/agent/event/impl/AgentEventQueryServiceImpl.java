package com.helloai.core.agent.event.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.mapper.AgentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;

/**
 * Agent 事件流读侧查询实现（Phase 0 A6 / A7）。
 *
 * <p>纯读服务：{@link #traceBySubTaskId} / {@link #traceByRunId} / {@link #traceByTaskId} /
 * {@link #pageAuditByTaskId} 将 {@code agent_event}（mapper 已按对应时序返回）
 * 投影为不可变 {@link AgentEventTraceItem}，不落库、不写状态。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventQueryServiceImpl implements AgentEventQueryService {

    /**
     * Audit 时间参数支持两种形态（与前端 {@code el-date-picker} 输出对齐）：
     * <ol>
     *   <li>{@code yyyy-MM-dd HH:mm:ss}（前端显式 {@code value-format}，无时区）</li>
     *   <li>{@code yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX]}（前端默认 ISO 形态，带时区偏移）</li>
     * </ol>
     * 前者按系统默认时区（与 MetaObjectHandler 写入 {@code OffsetDateTime.now()} 一致）装配为 OffsetDateTime，
     * 后者直接由 {@link OffsetDateTime#parse} 解析（带显式时区偏移或 ISO_INSTANT 兜底）。
     */
    private static final DateTimeFormatter AUDIT_LOCAL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentEventMapper agentEventMapper;

    @Override
    public List<AgentEventTraceItem> traceBySubTaskId(Long subTaskId) {
        if (subTaskId == null) {
            return Collections.emptyList();
        }
        return agentEventMapper.selectBySubTaskIdOrdered(subTaskId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public List<AgentEventTraceItem> traceByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Collections.emptyList();
        }
        return agentEventMapper.selectByRunIdOrdered(runId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public List<AgentEventTraceItem> traceByTaskId(Long taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        return traceByRunId(AgentEventContextResolver.resolveRunId(taskId));
    }

    @Override
    public IPage<AgentEventTraceItem> pageAuditByTaskId(Long taskId, String eventType,
                                                         String timeStart, String timeEnd,
                                                         long pageNum, long pageSize) {
        if (taskId == null) {
            return new Page<>(pageNum, pageSize);
        }
        OffsetDateTime parsedStart = parseAuditTime(timeStart, "timeStart");
        OffsetDateTime parsedEnd = parseAuditTime(timeEnd, "timeEnd");
        Page<AgentEvent> page = new Page<>(pageNum, pageSize);
        IPage<AgentEvent> result = agentEventMapper.selectPageAuditByTaskId(page, taskId, eventType, parsedStart, parsedEnd);
        return result.convert(this::toItem);
    }

    /**
     * 解析 Audit 时间筛选参数。
     *
     * @return 解析后的 {@link OffsetDateTime}；输入为 {@code null}/空白时返回 {@code null}（不限）；
     *         解析失败时记 warn 并返回 {@code null}（退化不过滤，不阻塞审计查询）。
     */
    private OffsetDateTime parseAuditTime(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return OffsetDateTime.parse(trimmed);
        } catch (DateTimeParseException ignoreIso) {
            // 不是 ISO 形态，尝试「yyyy-MM-dd HH:mm:ss」形态（前端 el-date-picker
            // value-format 形态 / 数据库写入的本地形态）；按系统默认时区装配 OffsetDateTime，
            // 与 MetaObjectHandler 用 OffsetDateTime.now() 写入的时区一致。
            try {
                return OffsetDateTime.of(java.time.LocalDateTime.parse(trimmed, AUDIT_LOCAL_FORMATTER),
                        OffsetDateTime.now().getOffset());
            } catch (DateTimeParseException ex) {
                log.warn("Audit 时间参数 {}={} 无法解析（既非 ISO 也非 yyyy-MM-dd HH:mm:ss），按【不设限】降级", fieldName, trimmed);
                return null;
            }
        }
    }

    private AgentEventTraceItem toItem(AgentEvent e) {
        return AgentEventTraceItem.builder()
                .id(e.getId())
                .eventId(e.getEventId())
                .runId(e.getRunId())
                .taskId(e.getTaskId())
                .subTaskId(e.getSubTaskId())
                .turn(e.getTurn())
                .step(e.getStep())
                .eventType(e.getEventType())
                .agentId(e.getAgentId())
                .payload(e.getPayload())
                .createTime(e.getCreateTime())
                .build();
    }
}