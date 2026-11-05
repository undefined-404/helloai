package com.helloai.core.planner.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AssistantWorkBriefFormatter} 单测（`PA-15` 概览渲染与预算截断）。
 */
@DisplayName("AssistantWorkBriefFormatter（工作概览）")
class AssistantWorkBriefFormatterTest {

    private static AssistantWorkBriefFormatter.SubTaskBrief sub(String title, String status, String acceptance) {
        return new AssistantWorkBriefFormatter.SubTaskBrief(title, status, acceptance);
    }

    @Test
    @DisplayName("无任务：返回空串（调用方据此不注入）")
    void emptyWhenNoTasks() {
        assertThat(AssistantWorkBriefFormatter.render(List.of(), 3000)).isEmpty();
        assertThat(AssistantWorkBriefFormatter.render(null, 3000)).isEmpty();
    }

    @Test
    @DisplayName("渲染任务 + 子任务：含状态、标题与验收摘要")
    void rendersTasksAndSubTasks() {
        List<AssistantWorkBriefFormatter.TaskBrief> tasks = List.of(
                new AssistantWorkBriefFormatter.TaskBrief("全量测试-正常通过路径", "IN_PROGRESS", List.of(
                        sub("撰写正文", "DONE", "正文包含「双域」\n且不超过 120 字"),
                        sub("自检回执", "REVIEW", null))));

        String brief = AssistantWorkBriefFormatter.render(tasks, 3000);

        assertThat(brief).contains("【最近任务与子任务】")
                .contains("[IN_PROGRESS] 全量测试-正常通过路径（2 个子任务）")
                .contains("[DONE] 撰写正文")
                .contains("验收：正文包含「双域」 且不超过 120 字")
                .contains("[REVIEW] 自检回执")
                .doesNotContain("（已截断）");
    }

    @Test
    @DisplayName("超预算：停止追加并标注「（已截断）」")
    void marksTruncatedWhenOverBudget() {
        AssistantWorkBriefFormatter.TaskBrief task = new AssistantWorkBriefFormatter.TaskBrief(
                "任务A", "IN_PROGRESS", List.of(
                        sub("子任务一", "DONE", "验收一"),
                        sub("子任务二", "DONE", "验收二"),
                        sub("子任务三", "DONE", "验收三")));

        String brief = AssistantWorkBriefFormatter.render(List.of(task), 80);

        assertThat(brief).contains("（已截断）");
        assertThat(brief.length()).isLessThan(200);
    }

    @Test
    @DisplayName("验收摘要：空白压平并限长 80 字")
    void acceptanceSnippetIsFlattenedAndCapped() {
        String longAcceptance = "要求一\n要求二\t要求三 " + "字".repeat(100);

        String snippet = AssistantWorkBriefFormatter.acceptanceSnippet(longAcceptance);

        assertThat(snippet).doesNotContain("\n").doesNotContain("\t");
        assertThat(snippet).endsWith("…");
        assertThat(snippet.length()).isLessThanOrEqualTo(81);
    }
}