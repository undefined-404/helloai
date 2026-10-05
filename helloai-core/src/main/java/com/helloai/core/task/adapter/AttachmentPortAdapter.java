package com.helloai.core.task.adapter;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AttachmentPort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：转发到 {@link AttachmentService}，<b>不改动任何校验、去活逻辑、
 * 参数顺序与事务边界</b>；附加动作只有两个——把返回实体收敛为附件主键（写），
 * 以及把实体映射为 {@link AttachmentRef} 快照（读）。实现侧依赖
 * {@code task → agent.port} 属顺向合法。</p>
 *
 * <p><b>为什么 {@code contentLoadable} 在映射时算好</b>：该布尔取自提供方
 * {@link AttachmentService#isContentLoadable}，判定依据是存储地址是否被后端支持。
 * 在映射时顺带计算可避免消费方为每个附件再发一次「是否可读」查询（N+1），
 * 判定责任仍留在提供方。</p>
 */
@Service
@RequiredArgsConstructor
public class AttachmentPortAdapter implements AttachmentPort {

    private final AttachmentService attachmentService;

    @Override
    public Long register(Long agentId, Long subTaskId, String fileName, String mimeType,
                         Long fileSize, String storageUrl) {
        Attachment attachment = attachmentService.register(agentId, subTaskId, fileName, mimeType,
                fileSize, storageUrl);
        return attachment.getId();
    }

    @Override
    public List<AttachmentRef> listActive(Long subTaskId) {
        List<Attachment> attachments = attachmentService.listActive(subTaskId);
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<AttachmentRef> refs = new ArrayList<>(attachments.size());
        for (Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            refs.add(toRef(attachment));
        }
        return refs;
    }

    @Override
    public byte[] loadContent(Long attachmentId) {
        return attachmentService.loadContent(attachmentId);
    }

    /** 实体 → 快照：字段原样投影；{@code contentLoadable} 由本域判定后透传。 */
    private AttachmentRef toRef(Attachment attachment) {
        return new AttachmentRef(attachment.getId(), attachment.getFileName(), attachment.getFileType(),
                attachment.getMimeType(), attachment.getFileSize(), attachment.getStatus(),
                attachmentService.isContentLoadable(attachment));
    }
}
