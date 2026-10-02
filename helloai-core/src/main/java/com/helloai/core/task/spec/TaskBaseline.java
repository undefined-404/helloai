package com.helloai.core.task.spec;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Task Running Spec 的 Baseline 部分——Planner 确认拆解后写入的任务全局规格。
 *
 * <p>内容：任务目标、平台约束、子任务 DAG 结构摘要。创建后不可变
 * （version 以外字段不在运行时更新），仅供 executor 执行前了解全局上下文。</p>
 *
 * <p>Phase A 通过 {@link #toMap()} / {@link #fromMap(Map)} 做 JSONB 序列化边界；
 * Phase B 将直接映射到独立表列。</p>
 */
public final class TaskBaseline {

    private final String goal;
    private final String constraints;
    private final String raw;
    private final Long createdBy;
    private final String createdAt;

    private TaskBaseline(Builder builder) {
        this.goal = builder.goal;
        this.constraints = builder.constraints;
        this.raw = builder.raw;
        this.createdBy = builder.createdBy;
        this.createdAt = builder.createdAt;
    }

    // ──────────────── accessors ────────────────

    public String goal() { return goal; }
    public String constraints() { return constraints; }
    public String raw() { return raw; }
    public Long createdBy() { return createdBy; }
    public String createdAt() { return createdAt; }

    // ──────────────── JSONB 序列化边界 ────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (goal != null) m.put("goal", goal);
        if (constraints != null) m.put("constraints", constraints);
        if (raw != null) m.put("raw", raw);
        if (createdBy != null) m.put("createdBy", createdBy);
        if (createdAt != null) m.put("createdAt", createdAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TaskBaseline fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Builder b = new Builder();
        Object g = map.get("goal");
        if (g instanceof String s) b.goal(s);
        Object c = map.get("constraints");
        if (c instanceof String s) b.constraints(s);
        Object r = map.get("raw");
        if (r instanceof String s) b.raw(s);
        Object cb = map.get("createdBy");
        if (cb instanceof Number n) b.createdBy(n.longValue());
        Object ca = map.get("createdAt");
        if (ca instanceof String s) b.createdAt(s);
        return b.build();
    }

    // ──────────────── builder ────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String goal;
        private String constraints;
        private String raw;
        private Long createdBy;
        private String createdAt;

        public Builder goal(String v) { this.goal = v; return this; }
        public Builder constraints(String v) { this.constraints = v; return this; }
        public Builder raw(String v) { this.raw = v; return this; }
        public Builder createdBy(Long v) { this.createdBy = v; return this; }
        public Builder createdAt(String v) { this.createdAt = v; return this; }

        public TaskBaseline build() {
            Objects.requireNonNull(goal, "goal");
            if (createdAt == null || createdAt.isBlank()) {
                createdAt = OffsetDateTime.now().toString();
            }
            return new TaskBaseline(this);
        }
    }
}
