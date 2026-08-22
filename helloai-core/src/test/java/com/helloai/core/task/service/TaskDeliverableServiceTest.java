package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.service.impl.TaskDeliverableServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskDeliverableService 单元测试：实时聚合 zip 的结构与取数规则
 * （概览、拓扑序编号、附件优先/同名取最新、context 回退、非 DONE 不收录）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskDeliverableService 交付物实时聚合打包")
class TaskDeliverableServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private AgentService agentService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ArtifactStorage artifactStorage;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> subTaskChain = mock(LambdaQueryChainWrapper.class);

    private TaskDeliverableService service;

    @BeforeEach
    void setUp() {
        service = new TaskDeliverableServiceImpl(taskService, subTaskService, agentService,
                attachmentService, artifactStorage, reviewRecordMapper);
        // lambdaQuery 链式 mock（项目内先例：PlannerAnalysisServiceTest）
        when(subTaskService.lambdaQuery()).thenReturn(subTaskChain);
        when(subTaskChain.eq(any(), any())).thenReturn(subTaskChain);
        when(subTaskChain.orderByAsc(org.mockito.ArgumentMatchers.<SFunction<SubTask, ?>>any())).thenReturn(subTaskChain);
        when(attachmentService.listActive(anyLong())).thenReturn(List.of());
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of());
    }

    private SubTask subTask(long id, String title, SubTaskStatus status, String output) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setTaskId(1L);
        st.setTitle(title);
        st.setStatus(status);
        if (output != null) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("lastExecution", Map.of("output", output));
            st.setContext(ctx);
        }
        return st;
    }

    private Task task() {
        Task task = new Task();
        task.setId(1L);
        task.setTitle("调度分析报告");
        task.setStatus(TaskStatus.DONE);
        return task;
    }

    /** 解 zip 为 有序 entryName → 文本内容。 */
    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    @DisplayName("任务不存在抛 BizException(404)")
    void shouldThrowWhenTaskMissing() {
        when(taskService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.buildZip(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("DONE 子任务回退 context 产出；非 DONE 只进概览表；草案/取消不出现")
    void shouldBuildZipWithFallbackAndOverview() throws IOException {
        when(taskService.getById(1L)).thenReturn(task());
        SubTask done = subTask(11L, "架构梳理", SubTaskStatus.DONE, "# 架构梳理产出");
        SubTask blocked = subTask(12L, "受阻项", SubTaskStatus.BLOCKED, "不应收录");
        SubTask draft = subTask(13L, "草案项", SubTaskStatus.PENDING_PLAN_REVIEW, null);
        when(subTaskChain.list()).thenReturn(List.of(done, blocked, draft));
        ReviewRecord review = new ReviewRecord();
        review.setResult(ReviewResult.APPROVED);
        review.setScore(92);
        review.setRound(2);
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(review));

        TaskDeliverableService.DeliverablePackage pkg = service.buildZip(1L);

        assertThat(pkg.fileName()).isEqualTo("调度分析报告-交付物.zip");
        Map<String, String> entries = unzip(pkg.content());
        assertThat(entries.keySet()).containsExactly("00-任务概览.md", "01-架构梳理.md");
        assertThat(entries.get("01-架构梳理.md")).isEqualTo("# 架构梳理产出");
        String overview = entries.get("00-任务概览.md");
        assertThat(overview).contains("调度分析报告")
                .contains("架构梳理").contains("受阻项")
                .doesNotContain("草案项")
                .contains("APPROVED（92分）")
                .contains("1 个子任务未完成");
    }

    @Test
    @DisplayName("物化附件优先于 context 回退，同名附件取最新一轮（列表首条）")
    void shouldPreferLatestAttachmentOverContext() throws IOException {
        when(taskService.getById(1L)).thenReturn(task());
        SubTask done = subTask(11L, "架构梳理", SubTaskStatus.DONE, "旧的 context 产出");
        when(subTaskChain.list()).thenReturn(List.of(done));

        Attachment newer = new Attachment();
        newer.setId(21L);
        newer.setFileName("架构梳理.md");
        newer.setStorageUrl("local://b/11/new-架构梳理.md");
        Attachment older = new Attachment();
        older.setId(20L);
        older.setFileName("架构梳理.md");
        older.setStorageUrl("local://b/11/old-架构梳理.md");
        // attachmentService.listActive 按创建时间倒序：最新在前
        when(attachmentService.listActive(11L)).thenReturn(List.of(newer, older));
        when(attachmentService.isContentLoadable(any())).thenReturn(true);
        when(artifactStorage.load(newer.getStorageUrl()))
                .thenReturn("最新一轮附件内容".getBytes(StandardCharsets.UTF_8));

        Map<String, String> entries = unzip(service.buildZip(1L).content());

        assertThat(entries.keySet()).containsExactly("00-任务概览.md", "01-架构梳理.md");
        assertThat(entries.get("01-架构梳理.md")).isEqualTo("最新一轮附件内容");
        verify(artifactStorage).load(newer.getStorageUrl());
        verify(artifactStorage, org.mockito.Mockito.never()).load(older.getStorageUrl());
    }

    @Test
    @DisplayName("附件读取失败回退 context 产出，不拖垮整包")
    void shouldFallbackToContextWhenAttachmentLoadFails() throws IOException {
        when(taskService.getById(1L)).thenReturn(task());
        SubTask done = subTask(11L, "架构梳理", SubTaskStatus.DONE, "context 兜底产出");
        when(subTaskChain.list()).thenReturn(List.of(done));
        Attachment broken = new Attachment();
        broken.setId(21L);
        broken.setFileName("架构梳理.md");
        broken.setStorageUrl("local://b/11/gone-架构梳理.md");
        when(attachmentService.listActive(11L)).thenReturn(List.of(broken));
        when(attachmentService.isContentLoadable(any())).thenReturn(true);
        when(artifactStorage.load(any())).thenThrow(new BizException(404, "产物文件不存在"));

        Map<String, String> entries = unzip(service.buildZip(1L).content());

        assertThat(entries.get("01-架构梳理.md")).isEqualTo("context 兜底产出");
    }

    @Test
    @DisplayName("按依赖拓扑序编号：依赖项排在其前置之后")
    void shouldOrderEntriesByDependency() throws IOException {
        when(taskService.getById(1L)).thenReturn(task());
        SubTask later = subTask(12L, "总结报告", SubTaskStatus.DONE, "总结内容");
        later.setDependsOn(List.of(11L));
        SubTask first = subTask(11L, "基础调研", SubTaskStatus.DONE, "调研内容");
        // 创建序把依赖项排在前，拓扑排序应还原为 基础调研 → 总结报告
        when(subTaskChain.list()).thenReturn(List.of(later, first));

        Map<String, String> entries = unzip(service.buildZip(1L).content());

        assertThat(entries.keySet())
                .containsExactly("00-任务概览.md", "01-基础调研.md", "02-总结报告.md");
    }

    @Test
    @DisplayName("已生成整合报告时置顶收录 01-最终整合报告.md，子任务产出顺延从 02- 起")
    void shouldIncludeFinalReportWhenPresent() throws IOException {
        Task task = task();
        task.setFinalReport("# 整合报告\n\n全局结论");
        when(taskService.getById(1L)).thenReturn(task);
        SubTask done = subTask(11L, "架构梳理", SubTaskStatus.DONE, "# 架构梳理产出");
        when(subTaskChain.list()).thenReturn(List.of(done));

        Map<String, String> entries = unzip(service.buildZip(1L).content());

        assertThat(entries.keySet())
                .containsExactly("00-任务概览.md", "01-最终整合报告.md", "02-架构梳理.md");
        assertThat(entries.get("01-最终整合报告.md")).isEqualTo("# 整合报告\n\n全局结论");
    }

    @Test
    @DisplayName("报告为空白时不收录，维持旧编号从 01- 起")
    void shouldSkipBlankFinalReport() throws IOException {
        Task task = task();
        task.setFinalReport("   ");
        when(taskService.getById(1L)).thenReturn(task);
        SubTask done = subTask(11L, "架构梳理", SubTaskStatus.DONE, "# 架构梳理产出");
        when(subTaskChain.list()).thenReturn(List.of(done));

        Map<String, String> entries = unzip(service.buildZip(1L).content());

        assertThat(entries.keySet()).containsExactly("00-任务概览.md", "01-架构梳理.md");
    }
}
