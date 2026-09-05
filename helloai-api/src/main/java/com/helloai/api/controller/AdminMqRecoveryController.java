package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.mq.DeadLetterArchiveResponse;
import com.helloai.api.dto.mq.OutboxFailedResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import com.helloai.core.agent.service.AgentCommandOutboxService;
import com.helloai.core.dlx.DeadLetterRecoveryService;
import com.helloai.core.dlx.MqDeadLetterArchiveRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * A4：MQ 业务治理人工恢复入口（N-010「人工恢复」子项）。
 *
 * <p>两个恢复入口共 4 端点：
 * <ul>
 *   <li>outbox FAILED 窗口分页列表 + 按 id 单条重入（重入为 PENDING，Relay 自然拾回）；</li>
 *   <li>死信台账窗口分页列表（含 replayed 过滤）+ 按 id 单条重放（去重重置 → 原坐标重发 → replayed_at CAS）。</li>
 * </ul>
 *
 * <p>本 Controller 只做参数接收 + DTO 装配 + {@code R} 封装（§6.3 红线）：
 * CAS 重入、去重重置、重发与回写编排全部在 core 服务内，控制器不出现编排逻辑；
 * 服务层 fail-close 抛出的 {@link com.helloai.common.base.BizException} 由全局异常处理转
 * {@code R.fail}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/mq-recovery")
@RequiredArgsConstructor
public class AdminMqRecoveryController {

    private final AgentCommandOutboxService outboxService;
    private final DeadLetterRecoveryService deadLetterRecoveryService;

    /**
     * outbox FAILED 行窗口分页列表（供运维挑选重入）。
     */
    @GetMapping("/outbox/failed")
    public R<PageResult<OutboxFailedResponse>> listFailedOutbox(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        IPage<AgentCommandOutboxEvent> result = outboxService.listFailed(from, to, page, size);
        return R.ok(PageResult.of(result, this::toResponse));
    }

    /**
     * 把指定 FAILED outbox 行重入 PENDING（CAS；非 FAILED 行由服务层 fail-close 报错）。
     */
    @PostMapping("/outbox/requeue/{id}")
    public R<Void> requeueOutbox(@PathVariable("id") Long id) {
        outboxService.requeueFailed(id);
        return R.ok();
    }

    /**
     * 死信台账窗口分页列表（默认仅未重放行；重放前可核对原始投递坐标与消息体）。
     */
    @GetMapping("/dead-letter")
    public R<PageResult<DeadLetterArchiveResponse>> listDeadLetters(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(value = "includeReplayed", defaultValue = "false") boolean includeReplayed,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        IPage<MqDeadLetterArchiveRow> result =
                deadLetterRecoveryService.listArchive(from, to, includeReplayed, page, size);
        return R.ok(PageResult.of(result, this::toResponse));
    }

    /**
     * 按台账 id 重放一条死信（已重放 / 无消息标识 / 发送失败由服务层 fail-close 报错）。
     */
    @PostMapping("/dead-letter/replay/{id}")
    public R<Void> replayDeadLetter(@PathVariable("id") long id) {
        deadLetterRecoveryService.replay(id);
        return R.ok();
    }

    private OutboxFailedResponse toResponse(AgentCommandOutboxEvent event) {
        OutboxFailedResponse resp = new OutboxFailedResponse();
        resp.setId(event.getId());
        resp.setEventId(event.getEventId());
        resp.setAggregateId(event.getAggregateId());
        resp.setRetryCount(event.getRetryCount());
        resp.setErrorMsg(event.getErrorMsg());
        resp.setCreateTime(event.getCreateTime());
        return resp;
    }

    private DeadLetterArchiveResponse toResponse(MqDeadLetterArchiveRow row) {
        DeadLetterArchiveResponse resp = new DeadLetterArchiveResponse();
        resp.setId(row.getId());
        resp.setOriginalExchange(row.getOriginalExchange());
        resp.setOriginalRoutingKey(row.getOriginalRoutingKey());
        resp.setFirstDeathReason(row.getFirstDeathReason());
        resp.setBody(row.getBody());
        resp.setCreatedAt(row.getCreatedAt());
        resp.setReplayedAt(row.getReplayedAt());
        return resp;
    }
}