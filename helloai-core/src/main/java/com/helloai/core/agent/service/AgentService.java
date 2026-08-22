package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.entity.RewardLog;

import java.util.List;
import java.util.Map;

/**
 * Agent 核心服务。负责 Agent 注册、CRUD、enrichment 查询、级联删除。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service。
 *
 * @see Agent
 */
public interface AgentService extends IService<Agent> {

    /**
     * 注册 Agent（重名报错），EXECUTOR 角色额外启用默认 MCP 工具。
     */
    Agent register(String name, AgentRole role, String description);

    /**
     * name 幂等注册（get-or-create）：同名已存在且 role 一致时归位复用，不重发 consumerToken。
     */
    Agent registerOrGet(String name, AgentRole role, String description);

    /**
     * 校验 modelType 格式、可用性及角色唯一性。
     *
     * <p>格式：providerCode:modelName；模型须启用；同模型在同一角色下只能被一个
     * API_KEY_LLM Agent 使用。null/blank 时跳过校验。注册前预校验用，避免校验失败
     * 留下已创建的无 modelType Agent。</p>
     *
     * @param modelType       待校验的 modelType，null/blank 时跳过
     * @param role            Agent 角色
     * @param excludeAgentId  排除的 Agent ID（编辑自身时排除；新增时传 null）
     * @throws com.helloai.common.base.BizException 格式错误 / 模型不可用 / 角色内模型已被占用时
     */
    void validateModelType(String modelType, AgentRole role, Long excludeAgentId);

    /**
     * 校验 Agent skills 不超出模型能力。
     *
     * <p>规则（D2=A 标准校验 + 自定义豁免）：仅标准技能标签（{@code AgentSkillDeriver.STANDARD_SKILLS}）
     * 查模型白名单（capabilitySkills ∪ availableOptionalSkills）；非标准项视为自定义技能豁免；
     * modelType 为 null/blank 或模型未识别（表中不存在）时直接通过（降级兼容）。</p>
     *
     * @param modelType 形如 providerCode:modelName，null/blank 时跳过
     * @param skills    Agent 待校验技能（null/空时跳过）
     * @throws com.helloai.common.base.BizException 标准技能超出模型白名单时
     */
    void validateAgentSkills(String modelType, List<String> skills);

    /**
     * 收口技能落库推导。
     *
     * <p>API_KEY_LLM 且 modelType 已识别 → 能力驱动推导
     * （{@code AgentSkillDeriver.deriveWithCapabilities}，能力锁定 + 白名单过滤 + 自定义豁免）；
     * 其他接入类型或未识别模型 → 走基础推导（{@code AgentSkillDeriver.derive}）。</p>
     *
     * @param agent          注册/编辑中的 Agent（accessType/name/remark/modelType 参与推导）
     * @param explicitSkills 用户显式传入的技能（可为 null/空）
     * @return 清洗后的技能列表（非 null）
     */
    List<String> deriveSkillsForRegistration(Agent agent, List<String> explicitSkills);

    /**
     * 按 consumerToken（api_key）查询 Agent。
     */
    Agent getByApiKey(String apiKey);

    /**
     * 按角色查询 Agent 列表。
     */
    List<Agent> listByRole(AgentRole role);

    /**
     * 查询全部 ACTIVE Agent（按积分倒序）。
     */
    List<Agent> listActive();

    /**
     * 更新 Agent 状态。
     */
    void updateStatus(Long agentId, AgentStatus status);

    /**
     * 重置 Agent 工牌 consumerToken。
     */
    String resetApiKey(Long agentId);

    /**
     * 更新 Agent 基础字段（name/modelType/remark，非 null 生效）。
     */
    void updateAgent(Long agentId, String name, String modelType, String remark);

    /**
     * 注册 Agent 并附加可选扩展字段（modelType/modelConfig/skills）。
     *
     * <p>按 §6.3 分层红线从 AdminAgentController 收口；作为事务代理入口，
     * 内部 {@link #register} 的事务注解在自调用场景不生效，由本方法统一托管。<br>
     * skills 按能力驱动落库（API_KEY_LLM + 已识别模型时 thinking 锁定、
     * 白名单过滤、自定义豁免），未传显式技能时同样推导（能力锁定不缺席）。</p>
     */
    Agent registerWithExtras(String name, AgentRole role, String description,
                             String modelType, Map<String, Object> modelConfig,
                             List<String> skills);

    /**
     * 更新 Agent 扩展字段；Agent 不存在时返回 false（Controller 保持原有 R.fail 语义）。
     *
     * <p>按 §6.3 分层红线从 AdminAgentController 收口；仅非 null 字段生效。</p>
     */
    boolean updateAgentDetail(Long agentId, String name, String modelType, Map<String, Object> modelConfig,
                              String remark, List<String> skills);

    /**
     * 持久化注册后置的可选字段变更（AgentController.applyRegistrationExtras 收口）。
     */
    void updateAgentExtras(Agent agent);

    /**
     * Agent 总数（收口 AdminAgentController 的 lambdaQuery().count() 直调）。
     */
    long countAll();

    /**
     * 全量 Agent 按积分倒序（收口 ScoreController 的 lambdaQuery() 直调）。
     */
    List<Agent> listAllOrderByScoreDesc();

    /**
     * 分页查询 Agent（支持 role/status 过滤、名称/备注关键字、score/createTime 排序）。
     */
    Page<Agent> listAgentsPaged(int pageNum, int pageSize, AgentRole role, AgentStatus status,
                                String keyword, String sortBy, String sortOrder);

    /**
     * Agent 工作量统计（assigned/inProgress/done/blocked/review 计数）。
     */
    Map<String, Integer> workloadStats(Long agentId);

    /**
     * Agent 进行中的子任务数。
     */
    int inProgressCount(Long agentId);

    /**
     * Agent 积分排名（并列按同分取同一名次）。
     */
    int scoreRank(Long agentId);

    /**
     * Agent 详情（不存在抛 BizException）。
     */
    Agent getAgentDetail(Long agentId);

    /**
     * Agent 关联数据计数（subTask/review/reward/activity）。
     */
    Map<String, Object> getRelatedCounts(Long agentId);

    /**
     * 级联删除 Agent（校验名称确认后 unlink 子任务并物理清理关联数据）。
     */
    Map<String, Object> deleteAgentCascade(Long agentId, String confirmName);

    /**
     * Agent 积分明细分页。
     */
    Page<RewardLog> getScoreLogs(Long agentId, int pageNum, int pageSize);

    /**
     * Agent 活动日志分页（可按 action 过滤）。
     */
    Page<ActivityLog> getActivityLogs(Long agentId, int pageNum, int pageSize, String action);

    /**
     * 管理员手动暂停 Agent：校验非 SLEEPING 后写入 SLEEPING + task_timeline 审计。
     */
    Agent sleepAgent(Long agentId, String operator, String reason);

    /**
     * 批量暂停 Agent：部分成功语义，单个失败不影响其他 Agent。
     */
    Map<String, Object> sleepAgentBatch(List<Long> agentIds, String operator, String reason);

    /**
     * 查询当前 SLEEPING 状态的 Agent（可按 role 过滤，按 update_time DESC 排序）。
     */
    List<Agent> findSleepingByRole(AgentRole role);

    /**
     * 管理员手动恢复 Agent：校验 SLEEPING 后置 OFFLINE（不强行 ONLINE）+ task_timeline 审计。
     */
    Agent wakeAgent(Long agentId, String operator, String reason);

    /**
     * 校验同一模型在同一角色下唯一。
     *
     * <p>规则：deepseek-v4-flash 和 kimi-k3 可同时注册为 Planner；
     * 但 deepseek-v4-flash 不能注册两个 Planner。</p>
     *
     * @param providerCode Provider Code（如 deepseek）
     * @param modelName    模型名称（如 deepseek-v4-flash）
     * @param role         Agent 角色
     * @param excludeAgentId 排除的 Agent ID（编辑时排除自身）
     * @throws com.helloai.common.base.BizException 当同一角色已存在使用该模型的 Agent 时
     */
    void validateModelUniqueInRole(String providerCode, String modelName, AgentRole role, Long excludeAgentId);

    // ══════════════════════════════════════════════════════════════
    //  §6.140 task→agent.mapper 双向红线收口（跨域直捅清零）
    //  承接 SubTaskServiceImpl / TaskServiceImpl / FeedServiceImpl 的 Mapper 直调
    // ══════════════════════════════════════════════════════════════

    /**
     * 行锁读取 Agent（E2 并发额度原子防线，原 SubTaskServiceImpl 直调 AgentMapper）。
     *
     * <p>{@code SELECT ... FOR UPDATE} 锁定 agent 行，串行化同一 Agent 的并发派发。
     * <b>必须在调用方事务内使用</b>：本方法只发锁语句、不自行开启事务，
     * 行锁随调用方事务存续而释放（与 {@code SubTaskService.getByIdForUpdate} 同规则）。</p>
     *
     * @return Agent；不存在或已删除时返回 null
     */
    Agent lockByIdForUpdate(Long agentId);

    /**
     * 全量 Agent 摘要列表（id/name/role/status/score，未删除；前端活动流列表数据源）。
     */
    List<Agent> listSummaries();

    /**
     * 统计某任务的执行记录数（删除前风险提示，原 TaskServiceImpl 直调 AgentExecutionRecordMapper）。
     */
    int countExecutionByTaskId(Long taskId);

    /**
     * 统计引用某任务及其子任务/审查记录的未读收件箱消息数（删除前风险提示）。
     */
    int countUnreadInboxByTaskRef(Long taskId);

    /**
     * 任务级联删除的 agent 域痕迹清理：按 taskId 物理删除 inbox / execution_record /
     * archive / message 关联行（原 TaskServiceImpl 直调 4 个 agent Mapper）。
     *
     * <p>inbox 的 DELETE 子查询依赖 sub_task/review_record 行仍存在，
     * 调用方必须在同一事务内先于子任务/审查记录执行（与 deleteTaskCascade 的
     * 既有删除顺序约定一致）。</p>
     *
     * @return 清理的总行数
     */
    int physicalDeleteTaskTrace(Long taskId);
}
