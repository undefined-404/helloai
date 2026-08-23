package com.helloai.core.task.port;

import java.util.List;

/**
 * 审查数据访问端口（§6.146 端口反转）：task 域消费方不依赖 review 域
 * service/entity，一切审查数据诉求经本端口收口（布尔判定/评分事实/摘要/
 * 统计/级联删除），由 review 域 {@code ReviewServiceImpl} 落实 implements
 * （review → task 属于合法向下依赖）。
 *
 * <p>返回值只暴露基本类型与 task 域值对象（{@link ReviewFact} /
 * {@link ReviewSummary}），不泄漏 review 域实体，保证 task → review
 * 依赖方向零反向。</p>
 */
public interface ReviewPort {

    /** 最新一条审查记录是否 APPROVED；无审查记录返回 null（调用方保持默认值）。 */
    Boolean isLatestReviewApproved(Long subTaskId);

    /** 子任务全部审查事实（按 round 升序）；无记录返回空列表。 */
    List<ReviewFact> listReviewFactsBySubTaskId(Long subTaskId);

    /** 最新一轮审查摘要（结论/评分/评语）；无记录返回 null。 */
    ReviewSummary latestReviewSummary(Long subTaskId);

    /** 按审查者统计审查记录数。 */
    long countByReviewerAgentId(Long reviewerAgentId);

    /** 按任务统计审查记录数。 */
    long countByTaskId(Long taskId);

    /** 物理删除任务下全部审查记录（任务级联删除使用，调用方事务内执行）。 */
    int physicalDeleteByTaskId(Long taskId);
}
