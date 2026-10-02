package com.helloai.core.task.service;

import com.helloai.common.constant.TaskMemberJoinSource;

import java.util.List;

/**
 * Task-Team 成员服务（任务级授权边界的唯一判据来源）。
 *
 * <p>设计依据：{@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §3。</p>
 *
 * <p>本服务是「谁是某个任务的团队成员」的<b>唯一权威入口</b>——附件的
 * {@code AttachmentVisibility.TASK} 分支、以及将来 P2 的 MCP 读取工具，
 * 都必须经 {@link #isMember(Long, Long)} 判定，不得各自查表。</p>
 */
public interface TaskAgentMemberService {

    /**
     * 判某 agent 是否属于某任务的 Task-Team（在队成员）。
     *
     * <p>语义：<b>加入过即成员</b>（改派不置 LEFT），故本方法对历史参与者也返回 {@code true}。</p>
     *
     * @param taskId  主任务 ID；为 {@code null} 返回 {@code false}
     * @param agentId Agent ID；为 {@code null} 返回 {@code false}
     */
    boolean isMember(Long taskId, Long agentId);

    /**
     * 幂等登记入队（不存在则插入，已存在则保持 ACTIVE）。并发安全，
     * 可由调用方事务包裹，也可独立调用。
     *
     * @param taskId 主任务 ID；{@code null} 静默跳过
     * @param agentId Agent ID；{@code null} 静默跳过
     * @param source 入队来源（记录首次入队入口，不覆盖非 REBUILT 的历史值）
     */
    void register(Long taskId, Long agentId, TaskMemberJoinSource source);

    /** 某任务的全部在队成员 agentId（无成员返回空列表，不返回 {@code null}）。 */
    List<Long> listActiveAgentIds(Long taskId);

    /**
     * 重建对账（启动一次性，幂等）：以权威源 {@code sub_task.assigned_agent_id} 补齐成员表。
     *
     * <p><b>为什么只扫 sub_task</b>：跨域直捅 {@code agent_execution_record} 会引入
     * 跨域表访问（架构红线）。历史执行者由两条规则兜住：① 附件可见性 TASK 分支中
     * "上传者恒可读自传附件"；② 改派入口自本方案上线后由 REASSIGNED 钩子持续记录。</p>
     *
     * <p>幂等：重复执行不会产生重复行（{@code uk(task_id, agent_id)} + ON CONFLICT）；
     * 中途失败也安全——未处理的行下次启动继续补齐。</p>
     *
     * @return 本次写入（含已存在被复活的）行数
     */
    int rebuildFromAuthoritativeAssignments();
}
