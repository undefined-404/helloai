package com.helloai.core.agent.port;

import java.util.List;

/**
 * 子任务「末轮评审上下文」只读视图 —— agent 域自有的数据契约，**零 task 实体泄漏**。
 *
 * <p><b>为什么需要它</b>：agent 侧回填器需要读 {@code SubTask.context.reviewHistory} 末轮
 * 的 {@code round / issues / executorDoneIssues} 三项。此前直接 import
 * {@code task.entity.SubTask} 再自行解析 JSON 结构 —— 既构成 §6 反向依赖，又把
 * <b>context 的 schema 知识</b>复制到了消费方（与提供方写入侧两份口径各写一份，必漂）。
 * 改为由 task 域解析后以本视图传出，schema 只在提供方一处维护。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2）</b>：消费方 {@code agent} 低于提供方 {@code task}
 * ⇒ 契约落消费方 {@code agent.port}；解析（{@code SubTask → LastReviewContext}）由提供方
 * task 侧完成，实现侧依赖 {@code task → agent} 属 <b>顺向合法</b>。</p>
 *
 * <p><b>与 {@link SubTaskSnapshot} 的分工</b>：{@code SubTaskSnapshot} 是「子任务本体字段」的
 * 通用快照；本记录只承载「末轮评审」这一处<b>派生语义</b>（由 context JSON 解析而来），
 * 与实体字段一一对应，故单独建 record 而不是塞进快照。</p>
 *
 * @param round              末轮轮次（task 域 {@code reviewHistory} 里 {@code round} 字段；
 *                           缺该字段时退化为 history 长度，故恒 ≥ 1）
 * @param issues             末轮驳回意见（无则为空列表，绝不 {@code null}）
 * @param executorDoneIssues 末轮已回填的「执行者已解决项」（无则为空列表，绝不 {@code null}）
 */
public record LastReviewContext(int round, List<String> issues, List<String> executorDoneIssues) {
}
