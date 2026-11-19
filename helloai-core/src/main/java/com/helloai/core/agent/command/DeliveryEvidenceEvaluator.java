package com.helloai.core.agent.command;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 交付物存在性判据组件（`MA-4` 第一段：**只观察、不改行为**）。
 *
 * <p><b>三取一判据</b>（满足任一即「本次完成有产物证据」）：</p>
 * <ol>
 *     <li>{@code parts} 非空 —— 声明式：模型在上报时自报了结构化分片（ADR-009 §8.6）；</li>
 *     <li>该子任务存在活跃 {@code Attachment} —— 事实式：产物真的落库了（经
 *         {@link AttachmentPort#listActive} 读取，语义 = {@code deleted=0 AND status='ACTIVE'}）；</li>
 *     <li>{@code output} 长度（trim 后）&gt; 阈值 —— 兜底：纯文本交付（不依赖协议形态）。
 *         阈值默认为保守值，观察期打真实分布后再定（蓝图 §4 `MA-4` ⑦「阈值怎么定」）。</li>
 * </ol>
 *
 * <p><b>豁免判定</b>（命中任一即「无产物也合规」，跳过三取一判据）：</p>
 * <ol>
 *     <li>{@code context.noDeliverable} 显式为真 —— 模型自报「本次无产物、无需闸门校验」
 *         （分析 / 决策类任务用，{@link SubTaskContextKeys#NO_DELIVERABLE}）；</li>
 *     <li>{@code context.acceptanceKindDeclared} ∈ {@code {REVIEWED, ATTESTED}} —— 方案/设计/分析类
 *         （{@code REVIEWED}，机器判）与决策/创意类（{@code ATTESTED}，人判）「结论即交付物」，
 *         {@code AcceptanceKind} 枚举无 {@code ANALYSIS} 值，由 {@code REVIEWED} 承载（蓝图口径订正）。</li>
 * </ol>
 *
 * <p><b>fail-open 语义（判据 9）</b>：本闸第一段只做「登记」，登记错（误报）会污染观察期
 * 误报率，故判据倾向「拿不准 = 认为有产物」——{@code context} 为 {@code null} / 豁免声明非法
 * 按「未声明」处理（不豁免，走三取一）；附件查询异常按「有产物」处理（不登记，不误报）。
 * 代码见 {@link #hasDeliverable(Long, List, String, Map)}。</p>
 *
 * <p><b>为什么是独立组件而不是写死在 {@code ExecutionResultHandler}</b>：判据必须可单测
 * （plan §4.2 高危漏项 #1）——{@code handleReport} 只做「采集输入 → 调本组件 → 按结论登记」的接线。</p>
 *
 * <p><b>落点</b>：{@code core/agent/command}（消费方 agent 域；附件经 {@code agent.port} 端口读取，
 * 不直捅 task 域 Mapper —— CODE_STYLE §6/§7.2）。</p>
 */
@Slf4j
@Component
public class DeliveryEvidenceEvaluator {

    private final AttachmentPort attachmentPort;
    private final int minOutputLength;

    /**
     * @param attachmentPort  产物附件端口（提供方 task 域实现；判据②读取）
     * @param minOutputLength output 判据阈值（配置 {@code helloai.delivery.min-output-length}，
     *                        默认 50 —— 保守值，观察期打分布后再定；小于等于该长度视为「无产物」）
     */
    public DeliveryEvidenceEvaluator(AttachmentPort attachmentPort,
                                     @Value("${helloai.delivery.min-output-length:50}") int minOutputLength) {
        this.attachmentPort = attachmentPort;
        this.minOutputLength = minOutputLength;
    }

    /**
     * 判定本次成功提交是否「有产物证据」（供 {@code handleReport} 在
     * {@code source=EXTERNAL && success=true} 路径调用；只读、无副作用、不抛异常）。
     *
     * @return true = 有产物证据（或不可判定按有产物处理）→ 不登记；
     *         false = 确认无产物证据 → 调用方应登记 {@code delivery_gate_miss}
     */
    public boolean hasDeliverable(Long subTaskId, List<ExecutionPart> parts, String output,
                                  Map<String, Object> context) {
        DeliveryEvidenceDetail detail = evaluate(subTaskId, parts, output, context);
        // 评估不可用（附件查询失败）⇒ fail-open 按「有产物」处理，不登记
        return detail == null || detail.hasDeliverable();
    }

    /**
     * 判据明细（供 {@code handleReport} 写时间线事件 payload / 日志；与
     * {@link #hasDeliverable} 同源）。
     *
     * <p>返回 {@code null} 表示「评估不可用」（附件端口异常）——调用方此时<b>不应登记</b>，
     * 与「确认无产物」刻意区分（缺判据 = 不误报，见类注释 fail-open 语义）。</p>
     */
    public DeliveryEvidenceDetail evaluate(Long subTaskId, List<ExecutionPart> parts, String output,
                                           Map<String, Object> context) {
        boolean exempted;
        String exemptReason;
        try {
            exemptReason = resolveExemptReason(context);
            exempted = exemptReason != null;
        } catch (Exception e) {
            // context 结构异常：按「未豁免 + 有产物」处理（fail-open，不误报）
            log.warn("交付物豁免判定异常（按未豁免、有产物处理）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return null;
        }
        boolean partsHit = parts != null && !parts.isEmpty();
        Boolean attachmentHit = hasActiveAttachment(subTaskId);
        if (attachmentHit == null) {
            return null;
        }
        int outputLength = output == null ? 0 : output.trim().length();
        boolean outputHit = outputLength > minOutputLength;
        return new DeliveryEvidenceDetail(exempted, exemptReason, partsHit, attachmentHit, outputHit, outputLength);
    }

    /**
     * 豁免判定：显式 {@code noDeliverable} 或 {@code acceptanceKindDeclared ∈ {REVIEWED, ATTESTED}}。
     *
     * @return 命中时返回豁免原因（人话，进时间线 payload）；未命中返回 {@code null}
     */
    private String resolveExemptReason(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object noDeliverable = context.get(SubTaskContextKeys.NO_DELIVERABLE);
        if (isTrue(noDeliverable)) {
            return "noDeliverable";
        }
        Object declared = context.get(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED);
        if (declared instanceof String kindText) {
            AcceptanceKind kind = AcceptanceKind.parse(kindText);
            if (kind == AcceptanceKind.REVIEWED) {
                return "acceptanceKind=REVIEWED";
            }
            if (kind == AcceptanceKind.ATTESTED) {
                return "acceptanceKind=ATTESTED";
            }
        }
        return null;
    }

    /** JSONB 读出 {@code noDeliverable} 的真值判定：Boolean true 或字符串 "true"（容大小写）。 */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return false;
    }

    /**
     * 活跃附件判定。
     *
     * @return true / false = 有无活跃附件；{@code null} = 查询异常（fail-open，按有产物处理）
     */
    private Boolean hasActiveAttachment(Long subTaskId) {
        if (subTaskId == null) {
            return false;
        }
        try {
            List<AttachmentRef> refs = attachmentPort.listActive(subTaskId);
            return refs != null && !refs.isEmpty();
        } catch (Exception e) {
            log.warn("交付物判据附件查询异常（按不可判定处理）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /** 判据明细 record —— 豁免原因 + 三判据命中情况 + output 长度（时间线 payload / 日志共用）。 */
    public record DeliveryEvidenceDetail(boolean exempted, String exemptReason,
                                         boolean partsHit, boolean attachmentHit,
                                         boolean outputHit, int outputLength) {

        /** 有产物 = 豁免 或 三取一任一命中。 */
        public boolean hasDeliverable() {
            return exempted || partsHit || attachmentHit || outputHit;
        }
    }
}