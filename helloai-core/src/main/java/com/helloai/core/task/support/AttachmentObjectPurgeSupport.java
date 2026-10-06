package com.helloai.core.task.support;

import com.helloai.core.system.storage.ArtifactStorage;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件对象存储回收支持（P3-3 级联删除 / P2 删除通道，2026-10-07）。
 *
 * <p><b>职责</b>：把「删除附件 DB 行」与「回收对象存储」解耦为<b>事务后置</b>动作——
 * 在事务<b>提交后</b>（{@code afterCommit}）才对平台可读对象调
 * {@link ArtifactStorage#removeObject(String, String)}，避免「对象已删但 DB 回滚」的引用断裂。</p>
 *
 * <p><b>为什么独立成类（不放进 AttachmentServiceImpl / TaskServiceImpl）</b>：
 * ①{@code AttachmentServiceImpl} 无法注入 {@code TaskService}，{@code TaskServiceImpl} 亦无法注入
 * {@code AttachmentService}（二者已存在构造器回边，见 {@code AttachmentServiceImpl} 字段注释），
 * 故「删对象」这一被 ①②两处（单条删除 / 任务级联删除）共用的逻辑必须有独立、无环的承载点；
 * ②本类只依赖 {@code AttachmentMapper}(task 域) + {@code ArtifactStorage}(system 域)，
 * 是 {@code task → system} 的<b>顺向</b>依赖（§6/§7.2），零新增反向依赖。</p>
 *
 * <p><b>fail-safe</b>：对象回收失败仅 {@code log.error}，绝不抛出——删除主流程（DB 行）
 * 已提交，不得因对象存储抖动而回滚/500；残留由存储对账（{@code orphan-cleanup}）兜底。</p>
 *
 * <p><b>安全护栏</b>：回收某 {@code objectKey} 前，若仍存在<b>其他活跃（{@code deleted=0}）附件行</b>
 * 指向同一 {@code objectKey}，则跳过（避免误删被他人仍引用的对象）。护栏判定走
 * {@link AttachmentMapper#selectActiveObjectKeysIn(java.util.Collection)} <b>窄查询</b>（按候选 key 批量查），
 * 不作全表载入（P2/P3-3 性能收敛，2026-10-07）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentObjectPurgeSupport {

    private final AttachmentMapper attachmentMapper;
    private final ArtifactStorage artifactStorage;

    /**
     * 事务提交后（无活动事务则立即）best-effort 回收这些附件对应的平台可读对象。
     *
     * @param attachments 待回收对象对应的附件行（可为 null / 空）；外部 {@code https://} 地址自动跳过
     */
    public void purgeAfterCommit(List<Attachment> attachments) {
        List<Attachment> candidates = attachments == null ? List.of()
                : attachments.stream().filter(this::isPurgeable).toList();
        if (candidates.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    purgeObjects(candidates);
                }
            });
        } else {
            // 无事务上下文（如直调）时立即执行，语义与 afterCommit 一致
            purgeObjects(candidates);
        }
    }

    /** 仅平台可读（local://、minio:// 等）且携带 objectKey 的行参与回收；外部地址跳过。 */
    private boolean isPurgeable(Attachment att) {
        return att != null
                && artifactStorage.supports(att.getStorageUrl())
                && att.getObjectKey() != null && !att.getObjectKey().isBlank();
    }

    private void purgeObjects(List<Attachment> candidates) {
        // 护栏：仅按「候选 key 集合」批量查仍被活跃行引用者（窄查询，1 次；不再全表载入）。
        // ① 单条删除后本行 deleted=1、④ 任务级联后本 task 行已物理删 ⇒ 候选自身不入结果集，
        //    故集合命中即「仍被其他活跃行引用」，无需再按 id 排除。
        Set<String> candidateKeys = candidates.stream()
                .map(Attachment::getObjectKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 去重（同一 key 多条活跃行会让 IN 查询返回重复 key）
        Set<String> stillReferencedKeys = candidateKeys.isEmpty()
                ? Set.of()
                : new HashSet<>(attachmentMapper.selectActiveObjectKeysIn(candidateKeys));
        for (Attachment att : candidates) {
            String objectKey = att.getObjectKey();
            try {
                if (stillReferencedKeys.contains(objectKey)) {
                    log.info("对象仍被其他活跃附件引用，跳过回收: objectKey={}", objectKey);
                    continue;
                }
                artifactStorage.removeObject(att.getBucketName(), objectKey);
                log.info("附件对象已回收: attachmentId={}, objectKey={}", att.getId(), objectKey);
            } catch (Exception e) {
                // fail-safe：对象删除失败不影响主流程（DB 已删 / 已软删），留对账兜底
                log.error("附件对象回收失败（best-effort，忽略）: attachmentId={}, objectKey={}",
                        att.getId(), objectKey, e);
            }
        }
    }
}
