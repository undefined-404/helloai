package com.helloai.core.agent.runtime.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxCallLedger} 单测（`SR-5`）。
 *
 * <p>覆盖：字段齐全、按子任务隔离、ring buffer 上限、最近 N 条查询、命令前缀截断、
 * 无上下文不入账。</p>
 */
@DisplayName("SandboxCallLedger")
class SandboxCallLedgerTest {

    private final SandboxCallLedger ledger = new SandboxCallLedger();

    @Test
    @DisplayName("单条记录：command / exitCode / truncated / digest / at 齐全")
    void recordShouldCarryAllFields() {
        ExecuteResponse response = new ExecuteResponse("out", 0, true);

        ledger.record(1L, "echo out", response);

        List<SandboxCallLedger.Entry> entries = ledger.recent(1L);
        assertThat(entries).hasSize(1);
        SandboxCallLedger.Entry entry = entries.get(0);
        assertThat(entry.command()).isEqualTo("echo out");
        assertThat(entry.exitCode()).isZero();
        assertThat(entry.truncated()).isTrue();
        assertThat(entry.digest()).isEqualTo(response.digest());
        assertThat(entry.at()).isNotNull();
    }

    @Test
    @DisplayName("按子任务隔离：各子任务只看到自己的记录")
    void shouldIsolateBySubTask() {
        ledger.record(1L, "a", new ExecuteResponse("a", 0, false));
        ledger.record(2L, "b", new ExecuteResponse("b", 1, false));

        assertThat(ledger.recent(1L)).extracting(SandboxCallLedger.Entry::command).containsExactly("a");
        assertThat(ledger.recent(2L)).extracting(SandboxCallLedger.Entry::command).containsExactly("b");
        assertThat(ledger.recent(3L)).isEmpty();
    }

    @Test
    @DisplayName("ring buffer：超过上限后只保留最近 N 条（最旧的被淘汰）")
    void shouldKeepOnlyLatestEntries() {
        int total = SandboxCallLedger.MAX_ENTRIES_PER_SUBTASK + 7;
        for (int i = 0; i < total; i++) {
            ledger.record(1L, "cmd-" + i, new ExecuteResponse("out-" + i, 0, false));
        }

        List<SandboxCallLedger.Entry> entries = ledger.recent(1L);
        assertThat(entries).hasSize(SandboxCallLedger.MAX_ENTRIES_PER_SUBTASK);
        // 时间升序：首条是第 8 条，末条是最后一条
        assertThat(entries.get(0).command()).isEqualTo("cmd-" + (total - SandboxCallLedger.MAX_ENTRIES_PER_SUBTASK));
        assertThat(entries.get(entries.size() - 1).command()).isEqualTo("cmd-" + (total - 1));
    }

    @Test
    @DisplayName("recent(n)：取最近 n 条，仍按时间升序")
    void recentShouldReturnTailInAscendingOrder() {
        ledger.record(1L, "a", new ExecuteResponse("a", 0, false));
        ledger.record(1L, "b", new ExecuteResponse("b", 0, false));
        ledger.record(1L, "c", new ExecuteResponse("c", 0, false));

        assertThat(ledger.recent(1L, 2)).extracting(SandboxCallLedger.Entry::command).containsExactly("b", "c");
        assertThat(ledger.recent(1L, 0)).isEmpty();
        assertThat(ledger.recent(null, 2)).isEmpty();
    }

    @Test
    @DisplayName("无上下文 / 空结果：不入账（不登记无法定位的记录）")
    void shouldIgnoreMissingContext() {
        ledger.record(null, "a", new ExecuteResponse("a", 0, false));
        ledger.record(1L, "a", null);

        assertThat(ledger.recent(1L)).isEmpty();
    }

    @Test
    @DisplayName("commandPreview：去首尾空白；超 200 字符截断并加省略号")
    void commandPreviewShouldTrimAndTruncate() {
        assertThat(SandboxCallLedger.commandPreview(null)).isEmpty();
        assertThat(SandboxCallLedger.commandPreview("  echo hi  ")).isEqualTo("echo hi");

        String longCommand = "x".repeat(SandboxCallLedger.MAX_COMMAND_CHARS + 20);
        String preview = SandboxCallLedger.commandPreview(longCommand);
        assertThat(preview).hasSize(SandboxCallLedger.MAX_COMMAND_CHARS + 3).endsWith("...");
        assertThat(preview).startsWith("x".repeat(SandboxCallLedger.MAX_COMMAND_CHARS));
    }
}
