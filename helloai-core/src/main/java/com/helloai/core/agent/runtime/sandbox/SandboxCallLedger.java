package com.helloai.core.agent.runtime.sandbox;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 沙箱调用台账（`SR-5`）：按子任务记录最近的命令执行事实。
 *
 * <p><b>为什么需要</b>：沙箱实现此前只有 {@code log.info/debug}，
 * 没有任何可回读记录——自审围栏（`SR-2`/`SR-3`）要求「证据以平台侧记录为准」，
 * 没有台账就只能退回解析模型自填，等于没改。</p>
 *
 * <p><b>存什么</b>：{@link Entry} 只存命令前缀（{@value #MAX_COMMAND_CHARS} 字符）、
 * {@code exitCode}、{@code truncated}、{@code digest}、时间，以及 `SL-4` 追加的
 * {@code callId} / {@code origin} / {@code round}；<b>不存完整输出</b>
 * （正文仍在 {@link ExecuteResponse} 与对话流里）。</p>
 *
 * <p><b>容量</b>：每子任务保留最近 {@value #MAX_ENTRIES_PER_SUBTASK} 条（ring buffer），
 * 最多跟踪 {@value #MAX_TRACKED_SUBTASKS} 个子任务（LRU 淘汰）。纯内存、进程级——
 * 重启即清空，与「沙箱本身也是进程级资源」同生命周期；需要跨重启追溯时读 timeline 事件
 * （{@code sandbox_command_executed}，与台账同源写入）。</p>
 *
 * <p><b>`SL-4` 为什么要 200 条</b>：150 次调用后早轮条目仍要在账（计划 §3 验收），旧的 50 条
 * 上限会让「上一轮证据」在长任务里被静默淘汰——证据被淘汰与被伪造同样致命（都会判 mismatch）。</p>
 *
 * <p><b>并发</b>：一个子任务的命令在槽内<b>串行</b>执行（ADR-005），台账仍按子任务加锁，
 * 避免不同子任务并发写坏 map。</p>
 */
@Component
public class SandboxCallLedger {

    /** 每子任务保留的最近条数（`SL-4`：50 → 200，覆盖长任务的多轮证据）。 */
    static final int MAX_ENTRIES_PER_SUBTASK = 200;

    /** 最多跟踪的子任务数（超出按 LRU 淘汰最久未写入的子任务）。 */
    static final int MAX_TRACKED_SUBTASKS = 512;

    /** 命令前缀保留字符数（与 timeline payload 一致）。 */
    static final int MAX_COMMAND_CHARS = 200;

    /** 台账条目 ID 的进程级序号（{@code c<seq>}，单调递增且进程内唯一）。 */
    private final AtomicLong callSeq = new AtomicLong();

    /**
     * 单条调用记录。
     *
     * @param command   命令前缀（超长截断并加省略号）
     * @param exitCode  退出码；124 = 超时，-1 = SDK 异常
     * @param truncated 输出是否被截断（{@code true} 时 {@code digest} 只覆盖截断后的正文）
     * @param digest    输出摘要（{@code sha256:<hex>}，见 {@link ExecuteResponse#digest()}）
     * @param at        记录时刻（服务端时钟）
     * @param callId    条目 ID（{@code c<seq>}；`SL-4`）——证据引用的唯一坐标
     * @param origin    调用来源（`SL-4`；{@link SandboxCallOrigin#code()}）
     * @param round     轮次（`SL-4`；子任务执行轮次，无上下文记 0）
     */
    public record Entry(String command, int exitCode, boolean truncated, String digest, OffsetDateTime at,
                        String callId, String origin, int round) {
    }

    private final Map<Long, Deque<Entry>> bySubTask = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Deque<Entry>> eldest) {
            return size() > MAX_TRACKED_SUBTASKS;
        }
    };

    /**
     * 记一条调用事实（来源缺省执行者、轮次 0）。
     *
     * @return 新条目；{@code subTaskId}/{@code response} 为 null 时返回 {@code null}（无上下文不登记）
     */
    public Entry record(Long subTaskId, String command, ExecuteResponse response) {
        return record(subTaskId, command, response, SandboxCallOrigin.EXECUTOR, 0);
    }

    /**
     * 记一条调用事实。
     *
     * @param subTaskId 子任务 ID（台账归属；null ⇒ 不登记）
     * @param command   原始命令（内部按 {@link #commandPreview} 截断）
     * @param response  执行结果（null ⇒ 不登记）
     * @param origin    调用来源；null ⇒ 按执行者记
     * @param round     轮次（子任务执行轮次；{@code <=0} 原样保留，用于核验侧轮次优先）
     * @return 新条目；无上下文 / 空结果时返回 {@code null}
     */
    public Entry record(Long subTaskId, String command, ExecuteResponse response,
                        SandboxCallOrigin origin, int round) {
        if (subTaskId == null || response == null) {
            return null;
        }
        Entry entry = new Entry(commandPreview(command), response.exitCode(), response.truncated(),
                response.digest(), OffsetDateTime.now(), nextCallId(),
                (origin == null ? SandboxCallOrigin.EXECUTOR : origin).code(), Math.max(round, 0));
        synchronized (bySubTask) {
            Deque<Entry> entries = bySubTask.computeIfAbsent(subTaskId, key -> new ArrayDeque<>());
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES_PER_SUBTASK) {
                entries.removeFirst();
            }
        }
        return entry;
    }

    /** 生成条目 ID（{@code c<seq>}；进程内唯一、单调递增）。 */
    private String nextCallId() {
        return "c" + callSeq.incrementAndGet();
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
