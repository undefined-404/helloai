package com.helloai.core.task.spec;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task Running Spec——任务运行态结构化文档（Phase A 不可变领域模型）。
 *
 * <p>封装了 Task 执行过程中累积的结构化知识，替代当前原始产出注入。
 * 包含：Planner 写入的 Baseline、各 executor 回填的 ExecutionRecord、
 * 系统自动编译的 ContextSummary。</p>
 *
 * <p>通过 {@link #toMap()} / {@link #fromMap(Map)} 实现 JSONB 序列化边界。
 * 无状态——每次更新都产生新的不可变实例，由 Service 层负责回写 DB。</p>
 */
public final class TaskRunningSpec {

    private static final int CURRENT_VERSION = 1;

    private final int version;
    private final TaskBaseline baseline;
    private final List<ExecutionRecord> executionRecords;
    private final String contextSummary;
    private final String lastUpdatedAt;

    private TaskRunningSpec(Builder builder) {
        this.version = builder.version > 0 ? builder.version : CURRENT_VERSION;
        this.baseline = builder.baseline;
        this.executionRecords = Collections.unmodifiableList(
                new ArrayList<>(builder.executionRecords != null ? builder.executionRecords : List.of()));
        this.contextSummary = builder.contextSummary;
        this.lastUpdatedAt = builder.lastUpdatedAt;
    }

    /** 空实例——新任务尚未初始化时的默认状态。 */
    public static final TaskRunningSpec EMPTY = new Builder().build();

    // ──────────────── accessors ────────────────

    public int version() { return version; }
    public TaskBaseline baseline() { return baseline; }
    public List<ExecutionRecord> executionRecords() { return executionRecords; }
    public String contextSummary() { return contextSummary; }
    public String lastUpdatedAt() { return lastUpdatedAt; }

    /** 是否有任何内容（用于判断是否需要注入上下文段）。 */
    public boolean isEmpty() {
        return baseline == null && executionRecords.isEmpty()
                && (contextSummary == null || contextSummary.isBlank());
    }

    // ──────────────── JSONB 序列化边界 ────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        if (baseline != null) {
            m.put("baseline", baseline.toMap());
        }
        if (!executionRecords.isEmpty()) {
            List<Map<String, Object>> records = new ArrayList<>(executionRecords.size());
            for (ExecutionRecord rec : executionRecords) {
                records.add(rec.toMap());
            }
            m.put("executionRecords", records);
        }
        if (contextSummary != null && !contextSummary.isBlank()) {
            m.put("contextSummary", contextSummary);
        }
        if (lastUpdatedAt != null) {
            m.put("lastUpdatedAt", lastUpdatedAt);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TaskRunningSpec fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return EMPTY;
        }
        Builder b = new Builder();
        Object v = map.get("version");
        if (v instanceof Number n) b.version(n.intValue());
        Object bl = map.get("baseline");
        if (bl instanceof Map<?, ?> baselineMap) {
            b.baseline(TaskBaseline.fromMap((Map<String, Object>) baselineMap));
        }
        Object er = map.get("executionRecords");
        if (er instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> recMap) {
                    ExecutionRecord rec = ExecutionRecord.fromMap((Map<String, Object>) recMap);
                    if (rec != null) {
                        b.addExecutionRecord(rec);
                    }
                }
            }
        }
        Object cs = map.get("contextSummary");
        if (cs instanceof String s) b.contextSummary(s);
        Object lua = map.get("lastUpdatedAt");
        if (lua instanceof String s) b.lastUpdatedAt(s);
        return b.build();
    }

    // ──────────────── builder（用于增量更新） ────────────────

    public static Builder builder() {
        return new Builder();
    }

    /** 以当前实例为模板创建 Builder（用于追加记录、更新摘要等增量操作）。 */
    public Builder toBuilder() {
        Builder b = new Builder()
                .version(this.version)
                .baseline(this.baseline)
                .contextSummary(this.contextSummary)
                .lastUpdatedAt(OffsetDateTime.now().toString());
        for (ExecutionRecord rec : this.executionRecords) {
            b.addExecutionRecord(rec);
        }
        return b;
    }

    public static final class Builder {
        private int version = CURRENT_VERSION;
        private TaskBaseline baseline;
        private List<ExecutionRecord> executionRecords = new ArrayList<>();
        private String contextSummary;
        private String lastUpdatedAt;

        public Builder version(int v) { this.version = v; return this; }
        public Builder baseline(TaskBaseline v) { this.baseline = v; return this; }
        public Builder addExecutionRecord(ExecutionRecord v) { this.executionRecords.add(v); return this; }
        public Builder contextSummary(String v) { this.contextSummary = v; return this; }
        public Builder lastUpdatedAt(String v) { this.lastUpdatedAt = v; return this; }

        public TaskRunningSpec build() {
            if (lastUpdatedAt == null || lastUpdatedAt.isBlank()) {
                lastUpdatedAt = OffsetDateTime.now().toString();
            }
            return new TaskRunningSpec(this);
        }
    }
}
