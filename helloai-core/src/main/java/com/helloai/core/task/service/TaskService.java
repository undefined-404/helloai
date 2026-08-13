package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;

import java.util.List;
import java.util.Map;

/**
 * 任务核心服务。负责任务级联删除、关联统计、重新发布。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service
 * （AgentInboxService 为无回向依赖的叶子服务，注入以复用门铃链路）。
 */
public interface TaskService extends IService<Task> {

    /**
     * 创建任务（初始状态 PENDING）。
     *
     * <p>按 §6.3 分层红线从 TaskController 收口：实体装配与落库归 Service，
     * Controller 只负责参数接收与返回封装。等价于 {@code createTask(title, description, null, null, null)}。</p>
     *
     * @param title       任务标题
     * @param description 任务描述
     * @return 已创建的任务
     */
    Task createTask(String title, String description);

    /**
     * 创建任务（初始状态 PENDING），可指定任务级 SLA 分钟数（A0-7 新增，V48）。
     *
     * <p>{@code slaMinutes} 可空：null=无时限；非 null 时在计划确认（confirmPlan）
     * 阶段按 {@code 确认时刻 + slaMinutes} 下发各子任务 {@code deadline}，
     * 外部 Agent 经 pullTasks 的 {@code deadline} 字段感知时限。
     * 等价于 {@code createTask(title, description, slaMinutes, null, null)}。</p>
     *
     * @param title       任务标题
     * @param description 任务描述
     * @param slaMinutes  任务 SLA 分钟数，null=无时限
     * @return 已创建的任务
     */
    Task createTask(String title, String description, Integer slaMinutes);

    /**
     * 创建任务（初始状态 PENDING），可指定任务级 SLA 与执行策略（A1 新增，V47 收尾）。
     *
     * <p>{@code agentPolicy} / {@code requiredSkills} 可空：null=不设置，落库走 DB 默认值
     * {@code {}} / {@code []}，与旧数据行为完全一致。policy 键结构见 {@code TaskAgentPolicy}。</p>
     *
     * @param title          任务标题
     * @param description    任务描述
     * @param slaMinutes     任务 SLA 分钟数，null=无时限
     * @param agentPolicy    任务级 Agent 指定策略，null=不设置
     * @param requiredSkills 任务要求的能力列表，null=不设置
     * @return 已创建的任务
     */
    Task createTask(String title, String description, Integer slaMinutes,
                    Map<String, Object> agentPolicy, List<String> requiredSkills);

    /**
     * 任务条件查询（状态过滤 + 创建时间倒序）。
     *
     * <p>按 §6.3 分层红线从 TaskController 收口：条件构造归 Service 层。
     * {@code page == null || page <= 0} 时返回全量列表（包装成 IPage，便于 Controller 统一处理）。</p>
     *
     * @param status   任务状态，可为 null（不过滤）
     * @param page     页码，null 或 <=0 表示不分页
     * @param pageSize 每页条数（仅分页时生效）
     * @return 分页结果或全量列表包装
     */
    IPage<Task> pageTasks(TaskStatus status, Integer page, int pageSize);

    /**
     * 更新任务状态；任务不存在时返回 null（由 Controller 转 R.fail）。
     *
     * @param id     任务 ID
     * @param status 新状态
     * @return 更新后的任务，或 null
     */
    Task updateStatus(Long id, TaskStatus status);

    /**
     * 更新任务标题与描述；任务不存在时返回 null（由 Controller 转 R.fail）。
     * 等价于 {@code updateTask(id, title, description, null, null, null)}。
     *
     * @param id          任务 ID
     * @param title       新标题
     * @param description 新描述
     * @return 更新后的任务，或 null
     */
    Task updateTask(Long id, String title, String description);

    /**
     * 更新任务基本信息、SLA 与执行策略（A1 新增，V47 收尾）；任务不存在时返回 null。
     *
     * <p>更新语义：null 字段不 set（保持现状，不进入 UPDATE 语句）；
     * {@code agentPolicy} 传空 Map / {@code requiredSkills} 传空列表表示显式清空
     * （写回 {@code {}} / {@code []}）。policy 键结构见 {@code TaskAgentPolicy}。</p>
     *
     * @param id             任务 ID
     * @param title          新标题，null 不更新
     * @param description    新描述，null 不更新
     * @param slaMinutes     新 SLA 分钟数，null 不更新
     * @param agentPolicy    新执行策略，null 不更新
     * @param requiredSkills 新能力列表，null 不更新
     * @return 更新后的任务，或 null
     */
    Task updateTask(Long id, String title, String description, Integer slaMinutes,
                    Map<String, Object> agentPolicy, List<String> requiredSkills);

    /**
     * 关联统计（删除前风险提示）。
     *
     * @param taskId 任务 ID
     * @return 各关联表计数 Map
     */
    Map<String, Object> getRelatedCounts(Long taskId);

    /**
     * 任务级联物理删除。
     *
     * <p>单事务内按外键依赖逆序清理，删除后数据库中不再存在该任务的任何行，
     * 与"消息只是门铃、DB 是唯一事实源"的防重原则天然兼容。</p>
     *
     * @param taskId       任务 ID
     * @param confirmTitle 确认标题（必须与任务标题一致）
     * @return 删除前的影响面统计
     */
    Map<String, Object> deleteTaskCascade(Long taskId, String confirmTitle);

    /**
     * 重新发布任务：状态重置为 PENDING 并重新通知全部 PLANNER。
     *
     * <p>不触碰已有子任务——子任务有独立生命周期与归属校验，重复规划与否
     * 由 PLANNER 侧根据现状决策；使用新 eventId 投递收件箱，
     * (event_id, agent_id) 唯一约束不会与历史通知冲突。</p>
     *
     * @param taskId 任务 ID
     * @return 重新发布后的任务
     */
    Task republish(Long taskId);
}
