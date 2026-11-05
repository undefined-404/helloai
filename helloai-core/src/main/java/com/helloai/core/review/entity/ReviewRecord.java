package com.helloai.core.review.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "review_record", autoResultMap = true)
public class ReviewRecord extends BaseEntity {

    private Long subTaskId;
    private Long reviewerAgentId;
    private ReviewResult result;
    private Integer score;
    private String issues;
    private String comment;
    private Integer round;

    /**
     * 验收点覆盖清单（`RV-3`）：元素形如
     * {@code {criterionRef, status, method, evidenceRef, note}}（键名与
     * {@code prompts/subtask-review.md} 的 coverage 契约逐字一致）。
     *
     * <p>落库的是 {@link com.helloai.core.review.support.ReviewCoverageParser} 归一后的清单，
     * 不是模型原文——畸形条目在解析阶段已丢弃，列里不会出现半条证据。
     * {@code NULL} = 该轮没有覆盖清单（历史行，或模型未输出 {@code coverage}）；
     * 覆盖批评器对 {@code NULL} 不回溯，故历史数据行为不变。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<Map<String, String>> coverage;

    /**
     * 判定快照（`RV-5`）：判定当时的可读附件集合与执行产出摘要，键形如
     * {@code {schemaVersion, attachmentCount, attachmentsTruncated, attachments:[{id,fileName,fileSize}],
     * output:{chars,sha256}}}。
     *
     * <p>{@code schemaVersion=2}（`RV-6`）：驳回记录另含可选 {@code frozenEvidence}
     * （`executionOutput / attachmentList / attachmentContent / verificationSignal / selfReviewSignal`
     * 五段原判证据文本），复判直接复用——返工后当前附件/产出失效，读当前视图必然漂移。
     * 通过记录不冻结（原对象不会失效，无需额外存储）。</p>
     *
     * <p>{@code NULL} = 历史行（本列上线前落库）⇒ 抽检复判按当前视图处理
     * （{@code ReviewSnapshotMatcher} 对无快照记录不拦，避免把老数据排除出抽检）。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> snapshot;
}
