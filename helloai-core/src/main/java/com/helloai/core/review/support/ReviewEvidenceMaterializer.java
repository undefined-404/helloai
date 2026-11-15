package com.helloai.core.review.support;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.StagedPathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核证据自动物化器（`RV-15`）：审核 turn 开始、LLM 调用前，平台把受审附件
 * （{@link AttachmentPort#listActive}，RV-8 有界证据集的附件侧）从对象存储物化进 reviewer 沙箱
 * {@code /tmp/staged/}，模型零操作拿到可执行证据。
 *
 * <p>取代 RV-14 模型命令式 stage（真机证明「工具可用 ≠ 模型会用」），把物化从模型技能升为平台承诺。
 * 只物化<b>当前子任务</b>有效附件（归属硬校验同 RV-14）；前置/上游产物本期不支持（`G-029`）。
 * 冻结复判路径（{@code executeWithEvidence}）<b>不调用本组件</b>——避免「Prompt 看冻结文本、沙箱跑当前文件」矛盾（F3）。</p>
 *
 * <p>fail-open：单条读取/写入失败记 {@code FAILED} 继续下一条，整体异常也不阻断核验。
 * 护栏：数量/单文件/总量三闸（{@link ReviewProperties.EvidenceMaterialization}）触顶即停，剩余转 {@code deferredIds} 由模型定向补拉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEvidenceMaterializer {

    /** 单条物化结果状态。 */
    public enum Status {
        /** 已写入 reviewer 沙箱（sha256 可对账）。 */
        MATERIALIZED,
        /** 超过单文件上限，未下载（按台账快照大小判定）。 */
        TOO_LARGE,
        /** 平台不可直读（非受控存储地址），无物化通道。 */
        NOT_LOADABLE,
        /** 读取 / 写入失败（单条降级，不阻断其余）。 */
        FAILED,
        /** 数量或总量触顶，未尝试（由模型 stage 补拉）。 */
        DEFERRED
    }

    /** 单条物化结果。 */
    public record MaterializedAttachment(Long attachmentId, String fileName, String path,
                                         long size, String sha256, Status status, String reason) {
    }

    /** 一次物化的汇总结果（renderPromptNote 渲染「证据就位清单」段）。 */
    public record MaterializationResult(boolean enabled, String disabledReason,
                                        List<MaterializedAttachment> attachments,
                                        List<Long> deferredIds,
                                        int materializedCount, long totalBytes) {

        public MaterializationResult {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            deferredIds = deferredIds == null ? List.of() : List.copyOf(deferredIds);
        }

        /** 未启用 / 不可用的空结果（attachments 恒空）。 */
        public static MaterializationResult disabled(String reason) {
            return new MaterializationResult(false, reason, List.of(), List.of(), 0, 0);
        }

        /**
         * 渲染「证据就位清单」prompt 段（`RV-15`）：模型据此知道哪些文件已就位、哪些要补拉。
         *
         * <p><b>为什么是独立段而非改五段契约</b>：物化结果是<b>本次执行现场</b>的事实，冻结复判
         * 与本次物化无关（F3）——不并入 {@link ReviewEvidenceView}，避免污染 RV-6 五段契约。</p>
         *
         * <p>只渲染必要信息：未启用给退路提示；已就位给路径清单（按台账序）；未就位给
         * id + 原因（护栏截断提示 + stage 补拉指引）。</p>
         */
        public String renderPromptNote() {
            StringBuilder sb = new StringBuilder("---\n**证据物化清单（平台自动注入，MinIO 原件，sha256 可对账）**\n");
            if (!enabled) {
                sb.append("- 自动物化不可用：").append(disabledReason == null ? "未知" : disabledReason)
                        .append("。需要实测脚本时请用 internal_stage_attachment_to_sandbox 注入；")
                        .append("无法执行时改用 internal_read_attachment_range 分段核对附件内容。");
                return sb.toString();
            }
            if (attachments.isEmpty()) {
                sb.append("- 无已物化证据（该子任务无有效附件，或全部未就位）：")
                        .append("需要实测时请用 internal_stage_attachment_to_sandbox 按需注入。");
                return sb.toString();
            }
            sb.append("- 已就位 ").append(materializedCount).append(" 个（共 ")
                    .append(attachments.size()).append(" 条，合计 ").append(totalBytes).append(" 字节）：\n");
            for (MaterializedAttachment a : attachments) {
                sb.append("  - ").append(a.path());
                if (a.status() == Status.MATERIALIZED && a.size() > 0) {
                    sb.append(" (").append(a.size()).append("B, sha256=").append(a.sha256()).append(")");
                } else if (a.status() != Status.MATERIALIZED) {
                    sb.append(" [").append(a.status().name()).append("] ")
                            .append(a.reason() == null ? "" : a.reason());
                }
                sb.append('\n');
            }
            if (!deferredIds.isEmpty()) {
                sb.append("- 未物化 ").append(deferredIds.size()).append(" 个（数量/总量触顶或不可直读）：")
                        .append("id=").append(deferredIds).append("。需要时请用 ")
                        .append("internal_stage_attachment_to_sandbox 按需补拉；无法执行时退回 ")
                        .append("internal_read_attachment_range 分段核对。\n");
            }
            sb.append("- 需要实测时：先用 cp 把 /tmp/staged/ 下文件摆到 /workspace 同目录，再调 internal_run_in_sandbox 执行。");
            return sb.toString();
        }
    }

    private final AttachmentPort attachmentPort;
    private final SandboxExecutionService sandboxExecutionService;
    private final OpenSandboxProperties sandboxProperties;
    private final ReviewProperties reviewProperties;

    /**
     * 执行一次证据物化（幂等：写入是覆盖语义，物化一次够首轮 + 不可解析重试轮共用）。
     *
     * @param taskId    任务 ID（定位 reviewer 只读槽；{@code null} ⇒ 视为不可用）
     * @param subTaskId 子任务 ID（附件台账 + 观测归属；{@code null} ⇒ 视为不可用）
     * @return 物化结果（fail-open：单项失败只记该条，不抛异常）
     */
    public MaterializationResult materialize(Long taskId, Long subTaskId) {
        if (taskId == null || subTaskId == null) {
            return MaterializationResult.disabled("缺少 taskId/subTaskId，无法定位审核沙箱");
        }
        // 沙箱未启用 ⇒ 与贡献者摘除 run_in_sandbox/stage/list 同一开关同源：不物化、不承诺
        if (!sandboxProperties.isEnabled()) {
            return MaterializationResult.disabled("OpenSandbox 未启用");
        }
        ReviewProperties.EvidenceMaterialization cfg = reviewProperties.getEvidenceMaterialization();
        if (cfg == null || !cfg.isEnabled()) {
            return MaterializationResult.disabled("评审证据自动物化开关处于关闭状态");
        }
        // F1 修正口径：复用关闭的一次性语义下没有可持久容器可写，判「不支持」而非假装能写
        if (!sandboxProperties.isReuseEnabled()) {
            return MaterializationResult.disabled("沙箱复用已关闭（reuse-enabled=false），无持久容器可物化");
        }
        List<AttachmentRef> refs;
        try {
            refs = attachmentPort.listActive(subTaskId);
        } catch (Exception e) {
            log.warn("证据物化读取附件台账失败（核验继续，按不可用处理）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return MaterializationResult.disabled("附件台账读取失败: " + e.getMessage());
        }
        if (refs == null || refs.isEmpty()) {
            return new MaterializationResult(true, null, List.of(), List.of(), 0, 0);
        }

        List<MaterializedAttachment> attachments = new ArrayList<>();
        List<Long> deferredIds = new ArrayList<>();
        int materialized = 0;
        long totalBytes = 0;
        for (AttachmentRef ref : refs) {
            Long id = ref.id();
            long size = ref.fileSize() == null ? 0 : ref.fileSize();
            // 闸 1：数量触顶 ⇒ 剩余全部 deferred（含本条）
            if (attachments.size() >= cfg.getMaxFiles()) {
                deferredIds.add(id);
                continue;
            }
            // 闸 2：单文件超限 ⇒ 按台账快照大小跳过，不产生实际读取
            if (size > cfg.getMaxBytesPerFile()) {
                attachments.add(new MaterializedAttachment(id, ref.fileName(), null, size, null,
                        Status.TOO_LARGE, "超过单文件上限 " + cfg.getMaxBytesPerFile() + "B"));
                continue;
            }
            // 闸 3：总量触顶 ⇒ 剩余全部 deferred
            if (totalBytes + size > cfg.getMaxTotalBytes()) {
                deferredIds.add(id);
                continue;
            }
            if (!ref.contentLoadable()) {
                attachments.add(new MaterializedAttachment(id, ref.fileName(), null, size, null,
                        Status.NOT_LOADABLE, "平台不可直读，无物化通道"));
                continue;
            }
            byte[] bytes;
            try {
                bytes = attachmentPort.loadContent(id);
            } catch (Exception e) {
                attachments.add(new MaterializedAttachment(id, ref.fileName(), null, size, null,
                        Status.FAILED, "读取失败: " + e.getMessage()));
                continue;
            }
            String path = StagedPathUtil.pathOf(id, ref.fileName());
            String sha256 = StagedPathUtil.sha256Hex(bytes);
            boolean ok;
            try {
                ok = sandboxExecutionService.writeReviewerArtifact(taskId, subTaskId, path, bytes);
            } catch (Exception e) {
                ok = false;
            }
            if (ok) {
                materialized++;
                totalBytes += bytes.length;
                attachments.add(new MaterializedAttachment(id, ref.fileName(), path, bytes.length, sha256,
                        Status.MATERIALIZED, null));
            } else {
                attachments.add(new MaterializedAttachment(id, ref.fileName(), path, bytes.length, sha256,
                        Status.FAILED, "沙箱写入失败（未启用/句柄不可用）"));
            }
        }
        if (materialized > 0) {
            log.info("审核证据自动物化完成: subTaskId={}, materialized={}, total={}",
                    subTaskId, materialized, totalBytes);
        }
        return new MaterializationResult(true, null, attachments, deferredIds, materialized, totalBytes);
    }
}