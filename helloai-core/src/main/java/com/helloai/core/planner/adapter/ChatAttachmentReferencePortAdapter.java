package com.helloai.core.planner.adapter;

import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.mapper.ChatAttachmentMapper;
import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ChatAttachmentReferencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
 * <p><b>为什么只依赖 {@link ChatAttachmentMapper}、不经 {@code ChatAttachmentService}</b>（G-047 修订）：
 * 加入 {@link #listActiveObjectKeysIn} 后若经 Service，会形成 Spring 构造器环——
 * {@code AttachmentObjectPurgeSupport}（task）需查本表 → 本类 → {@code ChatAttachmentServiceImpl}
 * → {@code ArtifactReferencePort}（查 attachment 表）；而 task 侧
 * {@code AttachmentServiceImpl} 又依赖 {@code AttachmentObjectPurgeSupport}。本类只做两条
 * 只读查询，直接走同域 Mapper 即可闭环。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatAttachmentReferencePortAdapter implements ChatAttachmentReferencePort {

    private final ChatAttachmentMapper chatAttachmentMapper;

    @Override
    public List<ArtifactReference> listAllIncludingDeleted() {
        List<ChatAttachment> rows = chatAttachmentMapper.selectStorageRefAllIncludingDeleted();
        List<ArtifactReference> refs = new ArrayList<>(rows.size());
        for (ChatAttachment row : rows) {
            refs.add(new ArtifactReference(row.getStorageUrl(), row.getFileSize()));
        }
        return refs;
    }

    @Override
    public Set<String> listActiveObjectKeysIn(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        // Mapper 可能回重复（同一 key 多条活跃行），去重后交给调用方做 contains 判定
        return Set.copyOf(chatAttachmentMapper.selectActiveObjectKeysIn(keys));
    }
}