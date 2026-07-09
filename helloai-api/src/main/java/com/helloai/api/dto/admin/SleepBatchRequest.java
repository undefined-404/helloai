package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.List;

/**
 * 批量暂停 Agent 请求体（v2.4 §4.3 批次 3）。
 *
 * <p>允许部分成功/失败：响应中按 succeeded / failed 维度返回明细，
 * 不抛 BizException 整体中断。
 */
@Data
public class SleepBatchRequest {
    /** 待暂停的 Agent ID 列表（不可为空） */
    private List<Long> agentIds;
    /** 批量操作原因（可选，会写入每条 task_timeline 审计 payload） */
    private String reason;
}