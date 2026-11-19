package com.helloai.core.agent.command;

import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code DeliveryEvidenceEvaluator} 单测（`MA-4`，plan §3 `MA4-6`）。
 *
 * <p>覆盖：三取一各分支（parts / 附件 / output 阈值）、豁免判定（noDeliverable /
 * acceptanceKindDeclared ∈ {REVIEWED, ATTESTED}）、非法豁免声明按「未声明」处理（fail-open）、
 * 附件查询异常按「有产物」处理（fail-open，不误报）、阈值可配置性。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryEvidenceEvaluator")
class DeliveryEvidenceEvaluatorTest {

    @Mock
    private AttachmentPort attachmentPort;

    private DeliveryEvidenceEvaluator evaluator(int minOutputLength) {
        return new DeliveryEvidenceEvaluator(attachmentPort, minOutputLength);
    }

    private static final long SUB_TASK_ID = 10001L;

    // ---------- 三取一：各分支独立命中 ----------

    @Test
    @DisplayName("parts 非空即算有产物（声明式判据命中，附件/output 为空也通过）")
    void partsHitAlone() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, List.of(part("text", "hello")), null, null);
        assertThat(detail).isNotNull();
        assertThat(detail.partsHit()).isTrue();
        assertThat(detail.attachmentHit()).isFalse();
        assertThat(detail.outputHit()).isFalse();
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("活跃附件存在即算有产物（事实式判据命中）")
    void attachmentHitAlone() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(
                List.of(new AttachmentRef(1L, "a.md", "md", "text/markdown",
                        10L, com.helloai.common.constant.AttachmentStatus.ACTIVE, true)));
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, "", null);
        assertThat(detail).isNotNull();
        assertThat(detail.attachmentHit()).isTrue();
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("output 长度超阈值即算有产物（兜底判据命中）")
    void outputHitAlone() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, "x".repeat(80), null);
        assertThat(detail).isNotNull();
        assertThat(detail.outputHit()).isTrue();
        assertThat(detail.hasDeliverable()).isTrue();
        assertThat(detail.outputLength()).isEqualTo(80);
    }

    @Test
    @DisplayName("三判据全不命中 → 确认无产物（登记闸应登记 delivery_gate_miss）")
    void allMiss() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, "短", null);
        assertThat(detail).isNotNull();
        assertThat(detail.partsHit()).isFalse();
        assertThat(detail.attachmentHit()).isFalse();
        assertThat(detail.outputHit()).isFalse();
        assertThat(detail.hasDeliverable()).isFalse();
    }

    @Test
    @DisplayName("output 恰好等于阈值 → 不命中（判据为严格大于）")
    void outputEqualsThresholdNotHit() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, "x".repeat(50), null);
        assertThat(detail.outputHit()).isFalse();
        assertThat(detail.hasDeliverable()).isFalse();
    }

    // ---------- 豁免判定 ----------

    @Test
    @DisplayName("context.noDeliverable=true（Boolean）→ 豁免，无产物也通过")
    void exemptByNoDeliverableBoolean() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, null, Map.of("noDeliverable", Boolean.TRUE));
        assertThat(detail.exempted()).isTrue();
        assertThat(detail.exemptReason()).isEqualTo("noDeliverable");
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("context.noDeliverable=\"true\"（字符串）→ 豁免（JSONB 读出形态兼容）")
    void exemptByNoDeliverableString() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, null, Map.of("noDeliverable", "true"));
        assertThat(detail.exempted()).isTrue();
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("noDeliverable=false / 缺键 → 不豁免，走三取一判据")
    void notExemptByNoDeliverableFalse() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, "短", Map.of("noDeliverable", Boolean.FALSE));
        assertThat(detail.exempted()).isFalse();
        assertThat(detail.hasDeliverable()).isFalse();
    }

    @Test
    @DisplayName("acceptanceKindDeclared=REVIEWED → 豁免（分析/方案类）")
    void exemptByReviewd() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, null, Map.of("acceptanceKindDeclared", "REVIEWED"));
        assertThat(detail.exempted()).isTrue();
        assertThat(detail.exemptReason()).isEqualTo("acceptanceKind=REVIEWED");
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("acceptanceKindDeclared=ATTESTED → 豁免（决策/创意类）")
    void exemptByAttested() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, null, Map.of("acceptanceKindDeclared", "ATTESTED"));
        assertThat(detail.exempted()).isTrue();
        assertThat(detail.exemptReason()).isEqualTo("acceptanceKind=ATTESTED");
        assertThat(detail.hasDeliverable()).isTrue();
    }

    @Test
    @DisplayName("acceptanceKindDeclared 非法值 → 不豁免（按未声明走三取一，与列落库回落口径一致）")
    void illegalKindNotExempt() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail = evaluator(50)
                .evaluate(SUB_TASK_ID, null, "短", Map.of("acceptanceKindDeclared", "ANALYSIS"));
        assertThat(detail.exempted()).isFalse();
        assertThat(detail.hasDeliverable()).isFalse();
    }

    @Test
    @DisplayName("context 为 null → 不豁免，正常走三取一（null 安全）")
    void nullContextNotExempt() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, "x".repeat(60), null);
        assertThat(detail.exempted()).isFalse();
        assertThat(detail.hasDeliverable()).isTrue();
    }

    // ---------- fail-open 语义 ----------

    @Test
    @DisplayName("附件查询抛异常 → 评估不可用（返回 null），hasDeliverable=true 不登记（不误报）")
    void attachmentPortErrorFailOpen() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenThrow(new RuntimeException("db down"));
        DeliveryEvidenceEvaluator evaluator = evaluator(50);
        assertThat(evaluator.evaluate(SUB_TASK_ID, null, "短", null)).isNull();
        assertThat(evaluator.hasDeliverable(SUB_TASK_ID, null, "短", null)).isTrue();
    }

    @Test
    @DisplayName("subTaskId 为 null → 附件判据视为未命中，不抛异常")
    void nullSubTaskId() {
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(null, null, "短", null);
        assertThat(detail).isNotNull();
        assertThat(detail.attachmentHit()).isFalse();
    }

    // ---------- 阈值可配置性 ----------

    @Test
    @DisplayName("阈值可配置：同一样本在不同阈值下判据结果随之变化")
    void thresholdConfigurable() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        String output = "x".repeat(100);
        // 阈值 50 → 命中；阈值 150 → 不命中
        assertThat(evaluator(50).evaluate(SUB_TASK_ID, null, output, null).outputHit()).isTrue();
        assertThat(evaluator(150).evaluate(SUB_TASK_ID, null, output, null).outputHit()).isFalse();
    }

    @Test
    @DisplayName("output 为 null → 长度为 0，不命中 output 判据（null 安全）")
    void nullOutput() {
        when(attachmentPort.listActive(SUB_TASK_ID)).thenReturn(List.of());
        DeliveryEvidenceEvaluator.DeliveryEvidenceDetail detail =
                evaluator(50).evaluate(SUB_TASK_ID, null, null, null);
        assertThat(detail.outputLength()).isZero();
        assertThat(detail.outputHit()).isFalse();
        assertThat(detail.hasDeliverable()).isFalse();
    }

    private static ExecutionPart part(String type, String body) {
        ExecutionPart p = new ExecutionPart();
        p.setType(type);
        if ("text".equals(type)) {
            p.setText(body);
        } else {
            p.setData(body);
        }
        return p;
    }
}