package com.helloai.core.task.service.impl;

import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ArtifactReferencePort} 适配实现（§6.146 端口反转配套）：system 域存储对账
 * 巡检所需的「附件元数据全量读取（含逻辑删除）」经本类收口，不暴露 task 域实体。
 *
 * <p><b>为什么端口定义在 system 域、实现在 task 域？</b>沿用本项目既有端口反转约定
 * 「消费方域定义接口、提供方域实现」（参见 {@code task.port.ReviewPort} /
 * {@code task.port.TaskDispatchPort} / {@code task.port.TaskPlannerPickerPort}）：
 * 消费方 system 域零 import 反向域，提供方 task 域以 {@code task → system} 合法
 * 向下依赖实现之。</p>
 *
 * <p>独立于 {@link AttachmentServiceImpl}：端口只需一条只读查询，
 * 挂在业务服务上会让 system 域间接依赖全部附件写路径依赖，职责也更不清晰。</p>
 */
@Service
@RequiredArgsConstructor
public class ArtifactReferencePortAdapter implements ArtifactReferencePort {

    private final AttachmentService attachmentService;

    @Override
    public List<ArtifactReference> listAllIncludingDeleted() {
        List<Attachment> rows = attachmentService.listAllIncludingDeleted();
        List<ArtifactReference> refs = new ArrayList<>(rows.size());
        for (Attachment row : rows) {
            refs.add(new ArtifactReference(row.getStorageUrl(), row.getFileSize()));
        }
        return refs;
    }
}
