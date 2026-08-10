package com.helloai.core.task.spec;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条 executor 回填的执行记录——子任务完成后写入 Task Running Spec 的结构化摘要。
 *
 * <p>与 {@code context.lastExecution.output}（原始 LLM 产出）不同，本记录是经过收口的、
 * 面向下游 executor 的结构化信息，不包含执行过程的噪声。</p>
 *
 * <p>Phase A 通过 {@link #toMap()} / {@link #fromMap(Map)} 做 JSONB 序列化边界；
 * Phase B 将变成 {@code task_execution_record} 表行。</p>
 */
public final class ExecutionRecord {

    private final Long subTaskId;
    private final String title;
    private final Long agentId;
    private final String summary;
    private final List<String> keyDecisions;
    private final List<String> downstreamNotes;
    private final List<String> deliverables;
    /** 验证证据原文（VERIFICATION 段，围栏协议）；缺失时为空串，仅检测不拦截。 */
    private final String verification;
    private final String completedAt;

    private ExecutionRecord(Builder builder) {
        this.subTaskId = builder.subTaskId;
        this.title = builder.title;
        this.agentId = builder.agentId;
        this.summary = builder.summary;
        this.keyDecisions = Collections.unmodifiableList(
                new ArrayList<>(builder.keyDecisions != null ? builder.keyDecisions : List.of()));
        this.downstreamNotes = Collections.unmodifiableList(
                new ArrayList<>(builder.downstreamNotes != null ? builder.downstreamNotes : List.of()));
        this.deliverables = Collections.unmodifiableList(
                new ArrayList<>(builder.deliverables != null ? builder.deliverables : List.of()));
        this.verification = builder.verification != null ? builder.verification : "";
        this.completedAt = builder.completedAt;
    }

    // ──────────────── accessors ────────────────

    public Long subTaskId() { return subTaskId; }
    public String title() { return title; }
    public Long agentId() { return agentId; }
    public String summary() { return summary; }
    public List<String> keyDecisions() { return keyDecisions; }
    public List<String> downstreamNotes() { return downstreamNotes; }
    public List<String> deliverables() { return deliverables; }
    public String verification() { return verification; }
    /** 提交是否携带验证证据（VERIFICATION 段非空）。 */
    public boolean hasVerification() { return verification != null && !verification.isBlank(); }
    public String completedAt() { return completedAt; }

    // ──────────────── JSONB 序列化边界 ────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (subTaskId != null) m.put("subTaskId", subTaskId);
        if (title != null) m.put("title", title);
        if (agentId != null) m.put("agentId", agentId);
        if (summary != null) m.put("summary", summary);
        if (!keyDecisions.isEmpty()) m.put("keyDecisions", keyDecisions);
        if (!downstreamNotes.isEmpty()) m.put("downstreamNotes", downstreamNotes);
        if (!deliverables.isEmpty()) m.put("deliverables", deliverables);
        if (!verification.isBlank()) m.put("verification", verification);
        if (completedAt != null) m.put("completedAt", completedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ExecutionRecord fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Builder b = new Builder();
        Object sid = map.get("subTaskId");
        if (sid instanceof Number n) b.subTaskId(n.longValue());
        Object t = map.get("title");
        if (t instanceof String s) b.title(s);
        Object aid = map.get("agentId");
        if (aid instanceof Number n) b.agentId(n.longValue());
        Object s = map.get("summary");
        if (s instanceof String str) b.summary(str);
        Object kd = map.get("keyDecisions");
        if (kd instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String str) b.addKeyDecision(str);
            }
        }
        Object dn = map.get("downstreamNotes");
        if (dn instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String str) b.addDownstreamNote(str);
            }
        }
        Object dl = map.get("deliverables");
        if (dl instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String str) b.addDeliverable(str);
            }
        }
        Object vf = map.get("verification");
        if (vf instanceof String str) b.verification(str);
        Object ca = map.get("completedAt");
        if (ca instanceof String str) b.completedAt(str);
        return b.build();
    }

    // ──────────────── builder ────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long subTaskId;
        private String title;
        private Long agentId;
        private String summary;
        private List<String> keyDecisions = new ArrayList<>();
        private List<String> downstreamNotes = new ArrayList<>();
        private List<String> deliverables = new ArrayList<>();
        private String verification;
        private String completedAt;

        public Builder subTaskId(Long v) { this.subTaskId = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder agentId(Long v) { this.agentId = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder addKeyDecision(String v) { this.keyDecisions.add(v); return this; }
        public Builder addDownstreamNote(String v) { this.downstreamNotes.add(v); return this; }
        public Builder addDeliverable(String v) { this.deliverables.add(v); return this; }
        public Builder verification(String v) { this.verification = v; return this; }
        public Builder completedAt(String v) { this.completedAt = v; return this; }

        public ExecutionRecord build() {
            if (subTaskId == null) {
                throw new IllegalArgumentException("subTaskId is required");
            }
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary is required");
            }
            if (completedAt == null || completedAt.isBlank()) {
                completedAt = OffsetDateTime.now().toString();
            }
            return new ExecutionRecord(this);
        }
    }
}
