package com.helloai.core.planner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.planner.entity.ChatSkillRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对话内技能执行 Mapper（V128）。
 *
 * <p>只放 MyBatis-Plus 内置方法覆盖不了的语句；常规读写走 {@code BaseMapper}。</p>
 */
@Mapper
public interface ChatSkillRunMapper extends BaseMapper<ChatSkillRun> {

    /**
     * 领取执行任务：{@code PENDING → RUNNING} 的<b>条件更新</b>。
     *
     * <p>返回 1 才算领到。这是执行幂等的唯一闸门：同一 run 被重复投递
     * （用户重试、worker 重跑）时只有第一次能把状态推过去，其余为 0 直接放弃 ——
     * 不会出现两条线程同时跑同一个技能、互相踩沙箱工作目录。</p>
     */
    @Update("UPDATE chat_skill_run SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PENDING' AND deleted = 0")
    int markRunning(@Param("id") Long id);

    /**
     * 推进阶段（仅 RUNNING 中可推进）。
     *
     * <p>条件带上 {@code status = 'RUNNING'}：已取消或已超时的执行不该再改阶段，
     * 否则会把「已终止」的记录改成「正在渲染」，前端会一直转下去。</p>
     */
    @Update("UPDATE chat_skill_run SET phase = #{phase}, phase_detail = #{detail}, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int markPhase(@Param("id") Long id,
                  @Param("phase") String phase,
                  @Param("detail") String detail);

    /** 执行成功：落产物附件与 payload，清空历史错误。 */
    @Update("UPDATE chat_skill_run SET status = 'SUCCEEDED', phase = 'DONE', phase_detail = NULL, "
            + "error_message = NULL, attachment_id = #{attachmentId}, result_payload = #{payload}, "
            + "finished_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int markSucceeded(@Param("id") Long id,
                      @Param("attachmentId") Long attachmentId,
                      @Param("payload") String payload);

    /**
     * 执行失败：落错误摘要（用户可见级别）。
     *
     * <p>条件含 {@code PENDING}：排队期间也可能失败（并发提交被数据库唯一索引拒绝、
     * 服务重启导致任务丢失等）。只允许从 RUNNING 失败的话，这类记录会永远停在 PENDING，
     * 而 PENDING 又占着「同会话互斥」的额度 —— 该会话的技能执行会被永久堵死。</p>
     */
    @Update("UPDATE chat_skill_run SET status = 'FAILED', phase = 'DONE', phase_detail = NULL, "
            + "error_message = #{error}, finished_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN ('PENDING', 'RUNNING') AND deleted = 0")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /**
     * 取消执行：{@code PENDING|RUNNING → CANCELLED} 的条件更新。
     *
     * <p>条件含 {@code PENDING}：任务已提交但 worker 还没领取（线程池排队）时，
     * 用户点取消必须生效 —— 只认 RUNNING 的话，这次取消等于没点，任务随后照样跑。
     * 与 worker 的 {@code markRunning} 并发时由行锁串行化：无论谁赢，
     * 结果都是「取消生效」或「跑起来后在下一个阶段边界自行中止」。</p>
     *
     * <p>已终态的记录不可被「迟到」的取消请求改成取消，
     * 否则一次慢到达的取消会把已经成功的产物标成取消。</p>
     */
    @Update("UPDATE chat_skill_run SET status = 'CANCELLED', phase = 'DONE', phase_detail = NULL, "
            + "finished_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN ('PENDING', 'RUNNING') AND deleted = 0")
    int markCancelled(@Param("id") Long id);

    /**
     * 同会话是否已有活跃任务（互斥预检查）。
     *
     * <p>「活跃」= {@code PENDING} 或 {@code RUNNING}：ADR-007 定的是<b>拒绝不排队</b>，
     * 排队中的 PENDING 也算占用，否则连发两条会先各自落一条 PENDING、
     * 随后在 worker 里撞 {@code uk_skill_run_active} 唯一索引。</p>
     *
     * <p>只是<b>预检查</b>：最终保证在数据库层（{@code uk_skill_run_active} 部分唯一索引）。
     * 应用层先查是为了给出可读报错（「本会话已有技能在执行」），而不是让用户撞数据库约束异常。</p>
     */
    @Select("SELECT COUNT(*) FROM chat_skill_run WHERE conversation_id = #{conversationId} "
            + "AND status IN ('PENDING', 'RUNNING') AND deleted = 0")
    int countActiveByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 同会话最近的活跃任务（刷新页面/换设备后恢复进度用）。
     *
     * <p>按 id 倒序取一条：雪花 id 单调递增，等价于「最近提交的那次」。
     * 正常流程下最多一条（互斥保证），取一条是为了在历史脏数据下也不炸。</p>
     */
    @Select("SELECT * FROM chat_skill_run WHERE conversation_id = #{conversationId} "
            + "AND status IN ('PENDING', 'RUNNING') AND deleted = 0 ORDER BY id DESC LIMIT 1")
    ChatSkillRun selectActiveByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 启动恢复：把上次进程留下的 {@code PENDING} / {@code RUNNING} 记录收尾为 {@code FAILED}。
     *
     * <p><b>为什么必须做</b>：进程重启后后台线程已不存在，这些行不会再被任何 worker 领取；
     * 而它们占着「同会话互斥」的额度（预检查与部分唯一索引都认活跃态）⇒
     * 该会话之后每次提交技能都会被「已有技能在执行」挡回，且没有任何自愈路径。
     * 执行一次可达数百秒，重启撞上的窗口不是毫秒级。</p>
     *
     * <p><b>前提</b>：单实例部署（当前 `docker-compose.server.yml` 只有一个 `app` 服务）。
     * 若将来横向扩容，本语句会误杀其他实例正在跑的任务，届时必须改为「按实例 id 归属」判定。</p>
     *
     * @return 收尾的记录数
     */
    @Update("UPDATE chat_skill_run SET status = 'FAILED', phase = 'DONE', phase_detail = NULL, "
            + "error_message = #{error}, finished_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
            + "WHERE status IN ('PENDING', 'RUNNING') AND deleted = 0")
    int failInterruptedRuns(@Param("error") String error);
}
