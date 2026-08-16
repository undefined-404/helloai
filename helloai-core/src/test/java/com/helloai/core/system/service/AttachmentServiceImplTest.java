package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.impl.AttachmentServiceImpl;
import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentServiceImplTest {

    private SubTaskService subTaskService;
    private TaskService taskService;
    private ArtifactStorage artifactStorage;

    private AttachmentServiceImpl service;
    private LambdaQueryChainWrapper<Attachment> chain;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        subTaskService = mock(SubTaskService.class);
        taskService = mock(TaskService.class);
        artifactStorage = mock(ArtifactStorage.class);
        service = spy(new AttachmentServiceImpl(subTaskService, taskService, artifactStorage));
        chain = mock(LambdaQueryChainWrapper.class);
        doReturn(chain).when(service).lambdaQuery();
        when(chain.eq(anyBoolean(), any(), any())).thenReturn(chain);
        when(chain.orderByDesc(org.mockito.ArgumentMatchers.<SFunction<Attachment, ?>>any())).thenReturn(chain);
    }

    private Attachment attachment(Long id, Long subTaskId, String fileName) {
        Attachment att = new Attachment();
        att.setId(id);
        att.setSubTaskId(subTaskId);
        att.setFileName(fileName);
        att.setStorageUrl("minio://helloai-artifacts/tester/2026/08/10/" + subTaskId + "/x-" + fileName);
        att.setObjectKey("tester/2026/08/10/" + subTaskId + "/x-" + fileName);
        return att;
    }

    private SubTask subTask(Long id, Long taskId, String title) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setTaskId(taskId);
        st.setTitle(title);
        return st;
    }

    @Test
    @DisplayName("list：无附件时不查子任务/任务，直接返回空列表")
    void shouldReturnEmptyWithoutLookup() {
        when(chain.list()).thenReturn(List.of());

        assertThat(service.list((Long) null)).isEmpty();
        verify(subTaskService, never()).listByIds(any());
        verify(taskService, never()).listByIds(any());
    }

    @Test
    @DisplayName("list：回填 taskId/taskTitle/subTaskTitle，附件本身字段不变")
    void shouldBackfillTaskAndSubTaskTitles() {
        Attachment att1 = attachment(1L, 100L, "报告1.md");
        Attachment att2 = attachment(2L, 101L, "报告2.md");
        when(chain.list()).thenReturn(List.of(att1, att2));
        when(subTaskService.listByIds(any()))
                .thenReturn(List.of(subTask(100L, 10L, "子任务A"), subTask(101L, 10L, "子任务B")));
        Task task = new Task();
        task.setId(10L);
        task.setTitle("主任务T");
        when(taskService.listByIds(any())).thenReturn(List.of(task));

        List<Attachment> result = service.list((Long) null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTaskId()).isEqualTo(10L);
        assertThat(result.get(0).getTaskTitle()).isEqualTo("主任务T");
        assertThat(result.get(0).getSubTaskTitle()).isEqualTo("子任务A");
        assertThat(result.get(1).getSubTaskTitle()).isEqualTo("子任务B");
        // 原字段保持
        assertThat(result.get(0).getFileName()).isEqualTo("报告1.md");
        assertThat(result.get(0).getStorageUrl()).startsWith("minio://");
    }

    @Test
    @DisplayName("list：子任务或任务已被删除时标题留空，不抛异常")
    void shouldTolerateMissingSubTaskOrTask() {
        Attachment att = attachment(1L, 999L, "孤儿.md");
        when(chain.list()).thenReturn(List.of(att));
        when(subTaskService.listByIds(List.of(999L))).thenReturn(List.of());

        List<Attachment> result = service.list((Long) null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTaskId()).isNull();
        assertThat(result.get(0).getTaskTitle()).isNull();
        assertThat(result.get(0).getSubTaskTitle()).isNull();
    }

    @Test
    @DisplayName("resolveContentType: .txt/.log 应返回 text/plain;charset=UTF-8")
    void resolveContentType_textLog_shouldReturnTextPlain() {
        Attachment att = attachment(1L, 100L, "error.log");

        assertThat(service.resolveContentType(att))
                .isEqualTo(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
    }

    @Test
    @DisplayName("resolveContentType: 未知后缀应回退 attachment.mimeType")
    void resolveContentType_unknownExt_shouldFallbackToMimeType() {
        Attachment att = attachment(1L, 100L, "blob.unknown");
        att.setMimeType("application/x-custom");

        assertThat(service.resolveContentType(att)).isEqualTo("application/x-custom");
    }

    @Test
    @DisplayName("resolveContentType: 未知后缀且 mimeType 为空应回退 octet-stream")
    void resolveContentType_extAndMimeBlank_shouldFallbackOctetStream() {
        Attachment att = attachment(1L, 100L, "blob.unknown");
        att.setMimeType(null);

        assertThat(service.resolveContentType(att))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    @Test
    @DisplayName("resolveContentType: JS 家族后缀应返回 text/javascript;charset=UTF-8")
    void resolveContentType_jsFamily_shouldReturnTextJavascript() {
        for (String name : List.of("app.js", "module.mjs", "legacy.cjs", "Component.jsx")) {
            Attachment att = attachment(1L, 100L, name);
            assertThat(service.resolveContentType(att))
                    .as("fileName=%s", name)
                    .isEqualTo("text/javascript;charset=UTF-8");
        }
    }

    @Test
    @DisplayName("resolveContentType: TS 家族后缀应返回 text/typescript;charset=UTF-8")
    void resolveContentType_tsFamily_shouldReturnTextTypescript() {
        for (String name : List.of("types.d.ts", "Component.tsx", "service.ts")) {
            Attachment att = attachment(1L, 100L, name);
            assertThat(service.resolveContentType(att))
                    .as("fileName=%s", name)
                    .isEqualTo("text/typescript;charset=UTF-8");
        }
    }

    @Test
    @DisplayName("isPreviewable: 超过 5MB 阈值的附件应返回 false")
    void isPreviewable_oversize_shouldReturnFalse() {
        Attachment att = attachment(1L, 100L, "big.log");
        att.setFileSize(6L * 1024 * 1024);
        when(artifactStorage.supports(anyString())).thenReturn(true);

        assertThat(service.isPreviewable(att)).isFalse();
    }

    @Test
    @DisplayName("isPreviewable: text/plain 且大小在阈值内应返回 true")
    void isPreviewable_textWithinSize_shouldReturnTrue() {
        Attachment att = attachment(1L, 100L, "small.log");
        att.setFileSize(1024L);
        when(artifactStorage.supports(anyString())).thenReturn(true);

        assertThat(service.isPreviewable(att)).isTrue();
    }

    @Test
    @DisplayName("isPreviewable: 平台不可读的附件应返回 false（与 zip 无关）")
    void isPreviewable_notContentLoadable_shouldReturnFalse() {
        Attachment att = attachment(1L, 100L, "small.log");
        att.setFileSize(1024L);
        when(artifactStorage.supports(anyString())).thenReturn(false);

        assertThat(service.isPreviewable(att)).isFalse();
    }

    @Test
    @DisplayName("isPreviewable: zip 附件应返回 false（非预览白名单）")
    void isPreviewable_zipNotInWhitelist_shouldReturnFalse() {
        Attachment att = attachment(1L, 100L, "archive.zip");
        att.setFileSize(1024L);
        when(artifactStorage.supports(anyString())).thenReturn(true);

        assertThat(service.isPreviewable(att)).isFalse();
    }
}
