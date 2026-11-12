package com.helloai.core.task.service.impl;

import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.DerivedReportAttachmentService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 派生报告回挂实现（`ADR-008` §15，方案 C）。
 *
 * <p>触发点：{@code TaskFinalReportGeneratedEvent}（报告写回后 AFTER_COMMIT）。不用
 * 「任务收尾时同步挂」是因为收尾发生在报告生成<b>之前</b>，那时 `final_report` 还是空的；
 * 报告 rework 后再次生成时会走同一事件，同名旧版由 {@code registerSystem} 自动去活，
 * 因此始终保留最新一版。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedReportAttachmentServiceImpl implements DerivedReportAttachmentService {

    private static final String FILE_PREFIX = "派生报告-";
    private static final String MIME_MARKDOWN = "text/markdown; charset=utf-8";

    private final TaskMapper taskMapper;
    private final SubTaskService subTaskService;
    private final AttachmentService attachmentService;

    @Override
    public boolean attachIfDue(Long derivedTaskId) {
        if (derivedTaskId == null) {
            return false;
        }
        Task derived = taskMapper.selectById(derivedTaskId);
        if (derived == null || derived.getParentSubTaskId() == null) {
            return false;
        }
        if (derived.getStatus() != TaskStatus.DONE) {
            return false;
        }
        String report = derived.getFinalReport();
        if (report == null || report.isBlank()) {
            return false;
        }
        SubTask parent = subTaskService.getById(derived.getParentSubTaskId());
        if (parent == null) {
            return false;
        }
        String fileName = FILE_PREFIX + derivedTaskId + ".md";
        boolean exists = attachmentService.listActive(parent.getId()).stream()
                .anyMatch(a -> fileName.equals(a.getFileName()));
        if (exists) {
            return false;
        }
        byte[] merged = renderMerged(parent, derived, report).getBytes(StandardCharsets.UTF_8);
        attachmentService.registerSystem(parent.getId(), fileName, MIME_MARKDOWN, merged, "system");
        log.info("派生报告已回挂: derivedTaskId={}, parentSubTaskId={}, bytes={}",
                derivedTaskId, parent.getId(), merged.length);
        return true;
    }

    /**
     * 合并渲染：首段声明来源与优先级（§15.3「不写清就会出现上下游看到的内容凭空换了一套」）。
     */
    private static String renderMerged(SubTask parent, Task derived, String report) {
        String original = SubTaskOutputExtractor.extractExecutionOutput(parent.getContext());
        StringBuilder sb = new StringBuilder(512 + report.length());
        sb.append("# 派生任务报告回挂\n\n")
                .append("> 本子任务（").append(parent.getTitle()).append("）已派生为独立任务（id=")
                .append(derived.getId()).append("）。\n")
                .append("> 本附件是「原产出 + 派生任务报告」的合并视图：下游读取本附件即读取合并结果；\n")
                .append("> 其中派生报告为最新结论，原产出仅作历史上下文。\n\n")
                .append("## 一、原产出（派生前）\n\n");
        if (original == null || original.isBlank()) {
            sb.append("（无：本子任务未产生独立产出）");
        } else {
            sb.append(original.strip());
        }
        sb.append("\n\n## 二、派生任务报告\n\n").append(report.strip()).append('\n');
        return sb.toString();
    }
}
