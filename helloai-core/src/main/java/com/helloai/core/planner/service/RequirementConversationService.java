package com.helloai.core.planner.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.planner.entity.RequirementConversation;

/**
 * 需求澄清会话薄 CRUD 服务。
 *
 * <p>业务编排（LLM 调用、终稿建任务）统一收口在
 * {@link com.helloai.core.planner.service.RequirementClarifyService}，本服务只提供数据访问。</p>
 */
public interface RequirementConversationService extends IService<RequirementConversation> {

    /**
     * 按「重构派生来源子任务」查当前 ACTIVE 会话（`ADR-008` AD-4 幂等入口）。
     *
     * @param sourceSubTaskId 来源子任务 id
     * @return 命中返回会话；无则 {@code null}
     */
    RequirementConversation findActiveBySourceSubTask(Long sourceSubTaskId);

    /**
     * 按「草案期派生锚」查当前 ACTIVE 会话（`ADR-008` §5 幂等入口）。
     *
     * <p>草案尚未物化成子任务，父锚是 {@code (taskId, draftCode)} 而不是
     * {@code source_sub_task_id}；同一草案重复点「拆出去」时复用同一会话，
     * 不重复建。</p>
     *
     * @param parentTaskId 草案所属任务 id
     * @param draftCode    草案编号
     * @return 命中返回会话；无则 {@code null}
     */
    RequirementConversation findActiveBySourceDraft(Long parentTaskId, String draftCode);

    /**
     * 派生任务被级联取消时关闭它的派生会话（`FINALIZED` → `ABANDONED`）。
     *
     * <p><b>为什么必须关</b>：父任务「拒绝重拆」会把整轮草案作废，并级联取消由这些草案
     * 派生出去的任务。此时派生会话的产出任务已取消、来源草案也已作废，若仍停在
     * {@code FINALIZED}：点「查看任务」跳到已取消的任务，点「重新生成」又因来源草案
     * 不在暂存区而 400——留下一条只能误导的僵尸会话。</p>
     *
     * <p>只动状态、不删消息：会话内容仍是「Y 的需求是怎么形成的」的追溯依据。</p>
     *
     * @param derivedTaskId 被取消的派生任务 id
     * @return true=确实关闭了会话
     */
    boolean abandonByDerivedTask(Long derivedTaskId);
}
