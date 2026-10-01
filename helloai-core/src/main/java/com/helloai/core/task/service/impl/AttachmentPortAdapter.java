package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link AttachmentPort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：转发到 {@link AttachmentService#register}，<b>不改动任何校验、去活逻辑、
 * 参数顺序与事务边界</b>；唯一附加动作是把返回实体收敛为附件主键。实现侧依赖
 * {@code task → agent.port} 属顺向合法。</p>
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
}
