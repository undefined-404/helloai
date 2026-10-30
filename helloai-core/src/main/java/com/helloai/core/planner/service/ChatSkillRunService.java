package com.helloai.core.planner.service;

import com.helloai.core.planner.entity.ChatSkillRun;

/**
 * 对话内技能执行（SKX-13）契约：提交 / 查询 / 取消。
 *
 * <p><b>状态推进不在这里做</b>：提交只落 {@code PENDING}，由
 * {@link com.helloai.core.planner.skill.SkillExecutionWorker} 在后台线程推进到终态。</p>
 */
public interface ChatSkillRunService {

    /**
     * 提交一次技能执行（落 {@code PENDING}，<b>不执行</b>）。
     *
     * <p>互斥：同一会话已有 {@code RUNNING} 的执行时<b>拒绝</b>并抛
     * {@code BizException}，不排队。理由：沙箱槽按会话复用（槽键 = {@code -conversationId}），
     * 并发两次会互相踩工作目录与产物路径；而「同一会话连着发两条技能」本身就是用户误操作，
     * 排队等 377s 不如直接提示「本会话已有技能在执行」。</p>
     *
     * @param conversationId 所属会话
     * @param userId         发起者用户 id
     * @param skillName      技能包名
     * @param triggerSource  触发方式（{@code CHAT} / {@code EDIT}）
     * @return 落库后的运行记录（PENDING）
     */
    ChatSkillRun submit(Long conversationId, Long userId, String skillName, String triggerSource);

    /**
     * 查一次执行的状态（前端轮询用）。
     *
     * @param runId 运行记录 id
     * @return 运行记录；不存在返回 {@code null}
     */
    ChatSkillRun get(Long runId);

    /**
     * 取消一次执行（活跃态 → {@code CANCELLED}，即 {@code PENDING} 或 {@code RUNNING}）。
     *
     * <p>取消是<b>协作式</b>：状态先置为 CANCELLED，worker 在下一个阶段边界检查到就停止。
     * 已经跑完的（终态）不会被改——否则一次迟到的取消会把已成功的产物标成取消。</p>
     *
     * @param runId 运行记录 id
     * @return 是否成功取消（已终态或不存在返回 false）
     */
    boolean cancel(Long runId);

    /**
     * 查该会话当前活跃的执行（刷新页面/换设备后恢复进度用）。
     *
     * @param conversationId 会话 id
     * @return 最近一条 {@code PENDING}/{@code RUNNING} 记录；没有返回 {@code null}
     */
    ChatSkillRun active(Long conversationId);
}
