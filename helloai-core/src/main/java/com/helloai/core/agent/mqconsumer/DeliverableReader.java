package com.helloai.core.agent.mqconsumer;

import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.WorkspaceEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 工作区交付物回读（`SL-9`，2026-11-13）：从复用工作区里挑出「本轮正在写的那一份正文」。
 *
 * <p><b>为什么在消费侧</b>：这是「对话正文不可交付时用什么兜底」的业务策略，与沙箱能力无关。
 * `SL-9` 之前它以 {@code find ... -printf} 命令的形式待在运行时里，导致一次纯读文件的操作
 * 也要占用执行通道、并把 platform 来源的调用写进台账与用户可见的时间线。现在运行时只提供
 * 「列目录 / 按路径读字节」两个机制动词，挑哪一份在这里判。</p>
 *
 * <p><b>挑选判据（`SL-3` 起按 mtime）</b>：`SL-1` 起工作区是<b>任务级共享</b>，同一任务上游子任务
 * 写下的交付稿也在 {@code /workspace} 里，而且往往比本轮草稿更大、更完整——按大小取会把
 * <b>上游旧稿</b>当成本轮交付物捞回来。修改时间才是「本轮正在写哪一份」的直接信号；
 * 没有 {@code .md} 时退回「最大的非空文件」（覆盖模型把正文写成 {@code .txt} 的情况）。</p>
 *
 * <p><b>失败一律降级为 {@code null}</b>：回读是锦上添花——拿不到时调用方照原样提交对话正文，
 * 不得因回读失败影响执行结果。</p>
 */
@Slf4j
@Component
public class DeliverableReader {

    /** 单次回读上限（字节）：中文正文按 UTF-8 3 字节/字估算 ≈ 2 万字，覆盖文档类验收绰绰有余。 */
    private static final int READ_MAX_BYTES = 60_000;

    /** 体积兜底的最小候选（字节）：过滤日志碎片 / 空文件，对齐原 {@code find -size +200c}。 */
    private static final long FALLBACK_MIN_BYTES = 200L;

    /** 交付物扩展名（原 {@code find -name '*.md'} 同口径，大小写敏感）。 */
    private static final String MARKDOWN_SUFFIX = ".md";

    /** 列目录深度：{@code /workspace/x.md} 与 {@code /workspace/out/x.md} 都要看见（对齐原 maxdepth 2）。 */
    private static final int LIST_DEPTH = 2;

    private final SandboxExecutionService sandboxExecutionService;

    public DeliverableReader(SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionService = sandboxExecutionService;
    }

    /**
     * 回读工作区里最近修改的一份 {@code .md} 正文（无 {@code .md} 时按体积兜底）。
     *
     * @param subTaskId 子任务 ID（观测归属 + 作用域解析）
     * @return 正文；无工作区 / 无候选 / 读取失败 ⇒ {@code null}
     */
    public String readLatestMarkdown(Long subTaskId) {
        if (subTaskId == null) {
            return null;
        }
        try {
            List<WorkspaceEntry> entries = sandboxExecutionService.listWorkspaceFiles(
                    subTaskId, SandboxRuntime.WORKSPACE_DIR);
            WorkspaceEntry picked = pick(entries);
            if (picked == null) {
                log.debug("DeliverableReader: 工作区无候选交付物，跳过回读 subTaskId={}", subTaskId);
                return null;
            }
            SandboxArtifact artifact = sandboxExecutionService.readArtifact(
                    subTaskId, picked.path(), READ_MAX_BYTES);
            if (artifact == null || artifact.bytes() == null) {
                return null;
            }
            String text = artifact.text();
            if (text.isBlank()) {
                return null;
            }
            log.info("DeliverableReader: 工作区正文回读成功 subTaskId={}, file={}, size={}, chars={}, "
                    + "truncated={}", subTaskId, picked.path(), picked.size(), text.length(),
                    artifact.truncated());
            return text;
        } catch (Exception e) {
            log.warn("DeliverableReader: 工作区正文回读异常 subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /**
     * 挑选判据（纯函数，便于单测）：最新修改的 {@code .md} 优先，否则最大的非空文件。
     *
     * <p>目录项一律跳过；修改时间未知（{@code null}）按最早处理，不与已知时间竞争。</p>
     *
     * @param entries 目录条目（可 null）
     * @return 选中的条目；无候选 ⇒ {@code null}
     */
    static WorkspaceEntry pick(List<WorkspaceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        WorkspaceEntry latestMarkdown = null;
        WorkspaceEntry largest = null;
        for (WorkspaceEntry entry : entries) {
            if (entry == null || entry.path() == null || entry.directory()) {
                continue;
            }
            if (entry.path().endsWith(MARKDOWN_SUFFIX)
                    && isLater(entry.modifiedAt(), latestMarkdown)) {
                latestMarkdown = entry;
            }
            if (entry.size() > FALLBACK_MIN_BYTES
                    && (largest == null || entry.size() > largest.size())) {
                largest = entry;
            }
        }
        return latestMarkdown != null ? latestMarkdown : largest;
    }

    private static boolean isLater(OffsetDateTime candidate, WorkspaceEntry current) {
        if (current == null) {
            return true;
        }
        return candidate != null && current.modifiedAt() != null
                && candidate.isAfter(current.modifiedAt());
    }
}
