package com.helloai.core.agent.runtime.sandbox;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱调用台账（`SR-5`）：按子任务记录最近的命令执行事实。
 *
 * <p><b>为什么需要</b>：`OpenSandboxExecutionService` 此前只有 {@code log.info/debug}，
 * 没有任何可回读记录——自审围栏（`SR-2`/`SR-3`）要求「证据以平台侧记录为准」，
 * 没有台账就只能退回解析模型自填，等于没改。</p>
 *
 * <p><b>存什么</b>：{@link Entry} 只存命令前缀（{@value #MAX_COMMAND_CHARS} 字符）、
 * {@code exitCode}、{@code truncated}、{@code digest}、时间；<b>不存完整输出</b>
 * （正文仍在 {@link ExecuteResponse} 与对话流里）。</p>
 *
 * <p><b>容量</b>：每子任务保留最近 {@value #MAX_ENTRIES_PER_SUBTASK} 条（ring buffer），
 * 最多跟踪 {@value #MAX_TRACKED_SUBTASKS} 个子任务（LRU 淘汰）。纯内存、进程级——
 * 重启即清空，与「沙箱本身也是进程级资源」同生命周期；需要跨重启追溯时读 timeline 事件
 * （{@code sandbox_command_executed}，与台账同源写入）。</p>
 *
 * <p><b>并发</b>：一个子任务的命令在槽内<b>串行</b>执行（ADR-005），台账仍按子任务加锁，
 * 避免不同子任务并发写坏 map。</p>
 */
@Component
public class SandboxCallLedger {

    /** 每子任务保留的最近条数。 */
    static final int MAX_ENTRIES_PER_SUBTASK = 50;

    /** 最多跟踪的子任务数（超出按 LRU 淘汰最久未写入的子任务）。 */
    static final int MAX_TRACKED_SUBTASKS = 512;

    /** 命令前缀保留字符数（与 timeline payload 一致）。 */
    static final int MAX_COMMAND_CHARS = 200;

    /**
     * 单条调用记录。
     *
     * @param command   命令前缀（超长截断并加省略号）
     * @param exitCode  退出码；124 = 超时，-1 = SDK 异常
     * @param truncated 输出是否被截断（{@code true} 时 {@code digest} 只覆盖截断后的正文）
     * @param digest    输出摘要（{@code sha256:<hex>}，见 {@link ExecuteResponse#digest()}）
     * @param at        记录时刻（服务端时钟）
     */
    public record Entry(String command, int exitCode, boolean truncated, String digest, OffsetDateTime at) {
    }

    private final Map<Long, Deque<Entry>> bySubTask = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Deque<Entry>> eldest) {
            return size() > MAX_TRACKED_SUBTASKS;
        }
    };

    /** 记一条调用事实；{@code subTaskId}/{@code response} 为 null 时静默忽略（无上下文不登记）。 */
    public void record(Long subTaskId, String command, ExecuteResponse response) {
        if (subTaskId == null || response == null) {
            return;
        }
        Entry entry = new Entry(commandPreview(command), response.exitCode(), response.truncated(),
                response.digest(), OffsetDateTime.now());
        synchronized (bySubTask) {
            Deque<Entry> entries = bySubTask.computeIfAbsent(subTaskId, key -> new ArrayDeque<>());
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES_PER_SUBTASK) {
                entries.removeFirst();
            }
        }
    }

    /** 该子任务最近的调用记录（按时间升序；最多 {@value #MAX_ENTRIES_PER_SUBTASK} 条）。 */
    public List<Entry> recent(Long subTaskId) {
        return recent(subTaskId, MAX_ENTRIES_PER_SUBTASK);
    }

    /**
     * 该子任务最近 {@code limit} 条调用记录（按时间升序，取尾部）。
     *
     * @param subTaskId 子任务 ID；null ⇒ 空清单
     * @param limit     条数上限；{@code <= 0} ⇒ 空清单
     */
    public List<Entry> recent(Long subTaskId, int limit) {
        if (subTaskId == null || limit <= 0) {
            return List.of();
        }
        List<Entry> snapshot;
        synchronized (bySubTask) {
            Deque<Entry> entries = bySubTask.get(subTaskId);
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            snapshot = new ArrayList<>(entries);
        }
        return List.copyOf(snapshot.subList(Math.max(0, snapshot.size() - limit), snapshot.size()));
    }

    /** 命令前缀（供台账与 timeline payload 共用同一截断口径）。 */
    public static String commandPreview(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        return trimmed.length() <= MAX_COMMAND_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_COMMAND_CHARS) + "...";
    }
}
