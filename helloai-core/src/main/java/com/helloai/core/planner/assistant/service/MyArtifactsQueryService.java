package com.helloai.core.planner.assistant.service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 「我的产物」聚合读取面（PA-5，2026-11-02）。
 *
 * <p><b>三源归一</b>：把三个产物来源归并成一份「当前用户」视角的产物清单，供
 * PA-4 统一工作台右栏展示。三源及其归属口径（按计划 §9 D1「直滤 owner」）：
 * <ul>
 *   <li><b>conversation</b>：会话附件（{@code chat_attachment}，含对话技能产物
 *       {@code saveArtifact}——两者均落该表、均以用户行级 {@code uploader_user_id} 归属），
 *       按 {@code = 当前用户} 直滤；
 *   <li><b>execution</b>：子任务执行产物（{@code attachment}，含子任务技能产物
 *       {@code SkillArtifactExportService}——同表无来源列），表内无用户字段，
 *       借道 {@code sub_task.task_id → task.owner_id = 当前用户} 推导归属。
 * </ul>
 *
 * <p><b>为什么不细分 skill 源</b>：P0 表结构无「技能产物」来源列——子任务技能产物
 * 与执行产物共用 {@code attachment}、对话技能产物与用户上传共用 {@code chat_attachment}，
 * 行数据无法区分。真三源拆分需新增 provenance 列（评估 §6.6 行 3 越权防护的下游），
 * 不在本项（计划 §0 ④/§9 D1）。故本查询面只吐 {@code conversation|execution} 两源。
 *
 * <p><b>边界</b>：只做<b>查询聚合展示</b>；下载 / 预览的越权防护（uid 级隔离，
 * 评估 §6.6 行 3）不在本项做（计划 §0 ④）。结果统一为
 * {@link ArtifactItem}，按 {@link #createdAt()} 倒序，前端标注来源分栏。</p>
 */
public interface MyArtifactsQueryService {

    /** 来源：会话附件（及对话技能产物，均落 {@code chat_attachment}、以用户行级归属）。 */
    String SOURCE_CONVERSATION = "conversation";

    /** 来源：子任务执行/技能产物（{@code attachment}，借道任务归属推导）。 */
    String SOURCE_EXECUTION = "execution";

    /** 归一后的单条产物（跨源统一投影，供 VO 转前端展示）。 */
    record ArtifactItem(
            /** conversation | execution —— 前端据此分栏。 */
            String source,
            /** 来源物理主键：chat_attachment.id 或 attachment.id。 */
            Long id,
            String fileName,
            String fileType,
            String mimeType,
            Long fileSize,
            /** 关联上下文标题：会话标题（conversation）或任务标题（execution）。 */
            String contextTitle,
            /** 关联上下文 id（会话 id 或任务 id），前端跳转用。 */
            Long contextId,
            /** 时间线用：来源行 createTime。 */
            OffsetDateTime createdAt
    ) {
    }

    /**
     * 聚合当前用户的全部产物（三源合并），按 {@link ArtifactItem#createdAt()} 倒序。
     *
     * @param limit 每源各自拉取上限（三源合并后再整体倒序截断到 limit）。
     *              传 null 时用实现内部默认上限。
     * @return 归一产物列表（绝不返回 null，可空列表）
     */
    List<ArtifactItem> myArtifacts(Integer limit);

    /**
     * 仅「会话产物」源（{@code chat_attachment}），按创建时间倒序。
     *
     * <p>个人工作台「最近产物」用（PA-4 改版，2026-11-02）：只展示最近会话中的产物，
     * 不混入子任务执行产物。</p>
     *
     * @param limit 条数上限（null/≤0 用实现默认值）
     * @return 归一产物列表（绝不返回 null，可空列表）
     */
    List<ArtifactItem> conversationArtifacts(Integer limit);
}