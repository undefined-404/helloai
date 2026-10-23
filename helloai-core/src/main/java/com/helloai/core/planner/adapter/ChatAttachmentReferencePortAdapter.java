package com.helloai.core.planner.adapter;

import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ChatAttachmentReferencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatAttachmentReferencePort} 适配实现（§6.146 端口反转配套）：system 域存储对账
 * 所需的「会话附件元数据全量读取（含逻辑删除）」经本类收口，不暴露 planner 域实体。
 *
 * <p><b>为什么实现落在 planner 域？</b>端口由消费方 system 域定义，实现必须放在
 * 提供方所在的域：{@code chat_attachment} 归 planner 域，而 {@code planner → system}
 * 是 §6 允许的向下依赖。反过来若挂在 task 域的
 * {@code ArtifactReferencePortAdapter} 上（那里已有 attachment 的读取），
 * 就变成 {@code task → planner}，属<b>反向依赖</b>，会被
 * {@code check-arch-freeze.sh} 拦截。</p>
 *
 * <p>不把方法直接加到 {@code ChatAttachmentServiceImpl} 上：对账只需一条只读投影查询，
 * 挂在业务服务上会让 system 域间接带上全部会话附件写路径的依赖。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatAttachmentReferencePortAdapter implements ChatAttachmentReferencePort {

    private final ChatAttachmentService chatAttachmentService;

    @Override
    public List<ArtifactReference> listAllIncludingDeleted() {
        List<ChatAttachment> rows = chatAttachmentService.listStorageRefsIncludingDeleted();
        List<ArtifactReference> refs = new ArrayList<>(rows.size());
        for (ChatAttachment row : rows) {
            refs.add(new ArtifactReference(row.getStorageUrl(), row.getFileSize()));
        }
        return refs;
    }
}
