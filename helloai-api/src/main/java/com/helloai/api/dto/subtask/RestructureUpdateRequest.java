package com.helloai.api.dto.subtask;

import lombok.Data;

/**
 * 执行期重构编辑入参（`ADR-008` §12.1）。
 *
 * <p>全部字段 null = 不修改（局部更新语义）。{@code title} 与草案编辑不同，允许修改——
 * 重构的本质是「这个子任务要做的事变了」，标题仍是旧语义时下游读到的就是错的。</p>
 */
@Data
public class RestructureUpdateRequest {
    private String title;
    private String content;
    private String deliverable;
    private String acceptance;
    private String constraints;
    private String acceptanceKind;
}
