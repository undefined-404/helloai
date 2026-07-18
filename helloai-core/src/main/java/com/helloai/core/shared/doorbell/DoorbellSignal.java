package com.helloai.core.shared.doorbell;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 门铃信号（AgentHub V3 门铃内核 PR-1）。
 *
 * <p>门铃只送"有事了"的轻量唤醒信号，<b>不含任务正文</b>：Agent 收到后自行走
 * MCP {@code pullTasks} 获取内容。因此即便门铃信号丢失，也不丢信息——这与
 * {@code doc/HelloAI_门铃通知通道设计.md} §8 的契约一致。</p>
 *
 * <p>字段刻意最小化：{@code type} 决定客户端动作，{@code eventType/refType/refId}
 * 仅供日志与去抖，客户端约定"收到 {@code type=inbox} 即调用 pullTasks"。</p>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DoorbellSignal {

    /** 信号类型：{@code connected}（握手）/ {@code inbox}（有新收件箱消息）/ {@code keepalive}（保活）。 */
    private final String type;

    /** 事件类型，如 {@code sub_task.assigned}；握手/保活时为 null。 */
    private final String eventType;

    /** 关联实体类型，如 {@code sub_task}；握手/保活时为 null。 */
    private final String refType;

    /** 关联实体 ID；握手/保活时为 null。 */
    private final Long refId;

    /** 服务端时间戳（ISO-8601），便于客户端排序与诊断。 */
    private final String serverTime;

    private DoorbellSignal(String type, String eventType, String refType, Long refId) {
        this.type = type;
        this.eventType = eventType;
        this.refType = refType;
        this.refId = refId;
        this.serverTime = OffsetDateTime.now().toString();
    }

    /** 建连握手信号，客户端收到即确认门铃可用。 */
    public static DoorbellSignal connected() {
        return new DoorbellSignal("connected", null, null, null);
    }

    /** 保活信号（PR-1 暂不使用，预留）。 */
    public static DoorbellSignal keepalive() {
        return new DoorbellSignal("keepalive", null, null, null);
    }

    /** 收件箱唤醒信号：提示 Agent 有新消息，应立即 pullTasks。 */
    public static DoorbellSignal inbox(String eventType, String refType, Long refId) {
        return new DoorbellSignal("inbox", eventType, refType, refId);
    }
}
