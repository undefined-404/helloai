package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * {@link ReviewEvidenceAssembler} 的轮次提示单测（`V157` / `VU-3` 第 4 条）。
 *
 * <p><b>这一条提示要防的事</b>：返工后核验若把上一轮遗留的 ACTIVE 附件当成「本轮已改好」的证据，
 * reviewer 会给出假放行。提示必须做到三件事：</p>
 * <ol>
 *   <li>登记轮次早于当前轮 ⇒ 出现「[轮次提示]」句子，点名文件与轮次；</li>
 *   <li>登记轮次等于当前轮 ⇒ 不出现（否则每轮都在喊狼来了，模型会学会忽略）；</li>
 *   <li>{@code round} 为 {@code null}（历史数据/兼容构造）⇒ 不出现，绝不按 0 猜。</li>
 * </ol>
 *
 * <p><b>另守一条结构判据</b>：提示是<b>追加在台账文本之后</b>的普通句子，
 * 台账行格式（{@code entry id=… status=…}）一字不改——它被 `RV-6` 冻结进
 * {@code review_record}，改行结构会让历史快照解析错位。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewEvidenceAssembler（附件轮次提示）")
class ReviewEvidenceAssemblerRoundNoteTest {

    private static final Long SUB_TASK_ID = 11L;
    private static final Long TASK_ID = 22L;

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AgentDispatchProperties dispatchProperties;

    private ReviewEvidenceAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ReviewEvidenceAssembler(attachmentService, dispatchProperties);
        lenient().when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(true);
        lenient().when(attachmentService.loadContent(anyLong()))
                .thenReturn("【正文】".getBytes(StandardCharsets.UTF_8));
        // G-054：当前轮改由提供方给（round_base + rework_count + 1），本域不再自己换算。
        // 默认第 2 轮，与下方 subTask() 的 reworkCount=1 语义一致。
        lenient().when(attachmentService.currentRound(SUB_TASK_ID)).thenReturn(2);
    }

    /** 子任务视图：{@code reworkCount=1} ⇒ 当前第 2 轮。 */
    private SubTaskView subTask() {
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 1,
                "截图核对", "核对界面截图", "截图.png", "界面与文案一致", null,
                Map.of("lastExecution", Map.of("output", "已生成截图")), List.of());
    }

    @Test
    @DisplayName("G-054：轮次由提供方给，视图里的 reworkCount 不参与换算")
    void roundShouldComeFromProviderNotView() {
        // 视图仍说 reworkCount=1（若本域还自己算，就是第 2 轮）；
        // 提供方说第 5 轮（人工驳回/死信重派把 round_base 抬上去了）。
        lenient().when(attachmentService.currentRound(SUB_TASK_ID)).thenReturn(5);
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID))
                .thenReturn(List.of(attachment(2L, "this-round.md", 5)));

        String list = assembler.buildAttachmentList(subTask());

        // 以提供方为准：不出现「当前为第 2 轮」
        assertThat(list).doesNotContain("当前为第 2 轮");
        assertThat(list).contains("第 5 轮");
        // 本轮登记 ⇒ 无需提示
        assertThat(list).doesNotContain("[轮次提示]");
    }

    private static AttachmentView attachment(long id, String name, Integer round) {
        return new AttachmentView(id, name, "markdown", "text/markdown", 64L, true, round);
    }

    @Test
    @DisplayName("旧轮登记的 ACTIVE 附件 ⇒ 追加轮次提示并点名文件")
    void staleRoundShouldWarn() {
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID))
                .thenReturn(List.of(attachment(1L, "old-round.md", 1), attachment(2L, "this-round.md", 2)));

        String list = assembler.buildAttachmentList(subTask());

        assertThat(list).contains("[轮次提示]");
        assertThat(list).contains("当前为第 2 轮");
        assertThat(list).contains("old-round.md（第 1 轮登记");
        // 本轮登记的附件不该被点名
        assertThat(list).doesNotContain("this-round.md（第");
    }

    @Test
    @DisplayName("全部本轮登记 ⇒ 不出现轮次提示")
    void sameRoundShouldStaySilent() {
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID))
                .thenReturn(List.of(attachment(2L, "this-round.md", 2)));

        assertThat(assembler.buildAttachmentList(subTask())).doesNotContain("[轮次提示]");
    }

    @Test
    @DisplayName("round 为 null（历史数据） ⇒ 不提示、不按 0 猜")
    void nullRoundShouldStaySilent() {
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID))
                .thenReturn(List.of(attachment(1L, "legacy.md", null)));

        assertThat(assembler.buildAttachmentList(subTask())).doesNotContain("[轮次提示]");
    }

    @Test
    @DisplayName("提示只进可读清单，台账保持机器格式（entry id=… status=…）")
    void ledgerFormatShouldRemainMachineReadable() {
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID))
                .thenReturn(List.of(attachment(1L, "old-round.md", 1)));

        String list = assembler.buildAttachmentList(subTask());
        String ledger = assembler.buildEvidenceContent(subTask(), false).evidenceLedger();

        // 清单里逐条标轮次，且带提示句
        assertThat(list).contains("第 1 轮");
        assertThat(list).contains("[轮次提示]");
        // 台账保持机器格式原样：有 entry 行、绝不混入提示句
        assertThat(ledger).contains("entry id=1");
        assertThat(ledger).doesNotContain("[轮次提示]");
    }
}
