package com.helloai.core.task.adapter;

import com.helloai.core.system.port.ArtifactReference;
import com.helloai.core.system.port.ArtifactReferencePort;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@link ArtifactReferencePort} 适配实现（§6.146 端口反转配套）：system 域存储对账
 * 巡检所需的「附件元数据全量读取（含逻辑删除）」经本类收口，不暴露 task 域实体。
 *
 * <p><b>为什么端口定义在 system 域、实现在 task 域？</b>沿用本项目既有端口反转约定
 * 「消费方域定义接口、提供方域实现」（参见 {@code task.port.ReviewPort} /
 * {@code agent.port.TaskDispatchPort} / {@code task.port.TaskPlannerPickerPort}）：
 * 消费方 system 域零 import 反向域，提供方 task 域以 {@code task → system} 合法
 * 向下依赖实现之。</p>
 *
 * <p><b>为什么只依赖 {@link AttachmentMapper}、不经 {@code AttachmentService}</b>（G-047 修订）：
 * 加入 {@link #listActiveObjectKeysIn} 后若经 Service，会形成 Spring 构造器环——
 * {@code ChatAttachmentServiceImpl}（planner）需查本表 → 本类 → {@code AttachmentServiceImpl}
 * → {@code AttachmentObjectPurgeSupport} → {@code ChatAttachmentReferencePort} →
 * {@code ChatAttachmentReferencePortAdapter} → {@code ChatAttachmentServiceImpl}。
 * 本类只做两条只读查询，直接走同域 Mapper 即可闭环，也符合原注释「端口只需一条只读查询，
 * 挂在业务服务上会让 system 域间接依赖全部附件写路径依赖」的意图。</p>
 */
@Service
@RequiredArgsConstructor
public class ArtifactReferencePortAdapter implements ArtifactReferencePort {

    private final AttachmentMapper attachmentMapper;

    @Override
    public List<ArtifactReference> listAllIncludingDeleted() {
        List<Attachment> rows = attachmentMapper.selectAllIncludingDeleted();
        List<ArtifactReference> refs = new ArrayList<>(rows.size());
        for (Attachment row : rows) {
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
        return Set.copyOf(attachmentMapper.selectActiveObjectKeysIn(keys));
    }
}