package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentDutyLeaseMapper;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.mapper.ConversationArchiveMapper;
import com.helloai.core.agent.mapper.ConversationMessageMapper;
import com.helloai.core.agent.port.AgentAuthPort;
import com.helloai.core.agent.service.AgentCredentialService;
import com.helloai.core.agent.service.AgentLifecycleService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentSkillPolicyService;
import com.helloai.core.agent.service.AgentStatsService;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.entity.RewardLog;
import com.helloai.core.task.service.ActivityLogService;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 核心服务实现。负责 Agent 注册、CRUD、enrichment 查询、级联删除。
 * 阶段五起：task 域数据一律经 task 域服务接口（SubTaskService/RewardService/
 * ActivityLogService），自身直捅 Mapper 仅限于 agent 域内部（含 §6.140 承接的
 * task 域跨域收口方法）。
 *
 * <p><b>§7.8 类规模拆分评审结论（2026-08-23）</b>：本类为 agent 域聚合汇聚点，
 * 超 500 行 / 8 依赖红线，按 §7.8 选项二书面声明不继续拆分：</p>
 * <ul>
 *     <li>已剥离：技能策略（AgentSkillPolicyService）、生命周期（AgentLifecycleService）、
 *         统计与积分明细（AgentStatsService）、凭据与密钥（AgentCredentialService /
 *         AgentApiKeyCipher）、MCP 服务器（AgentMcpServerService）；</li>
 *     <li>剩余职责：注册 / 幂等注册 / 认证（含惰性加密迁移）、CRUD 与分页、级联删除、
 *         §6.140 跨域收口薄转发；</li>
 *     <li>不拆理由：注册与认证共享密钥 / 凭据链路（issueConsumerToken → 加密 → hash），
 *         级联删除必须单事务编排多域清理顺序；已外置部分均为独立子域服务，剩余聚合逻辑
 *         互相引用紧密，无独立可测职责可继续剥离。</li>
 * </ul>
 *
 * @see Agent
 */
@Slf4j
@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent> implements AgentService, AgentAuthPort {

    // 阶段五 task↔agent 事件解耦：task 域数据一律经 task 域服务接口，
    // 不再直捅 task.mapper（关联统计 / 级联删除 / 原子认领见 SubTaskService 等）
    private final SubTaskService subTaskService;
    private final RewardService rewardService;
    private final ActivityLogService activityLogService;
    private final AgentInboxMapper agentInboxMapper;
    private final AgentDutyLeaseMapper agentDutyLeaseMapper;
    // §6.140：承接 TaskServiceImpl 跨域收口（任务级联删除/关联统计的 agent 域执行）
    private final AgentExecutionRecordMapper agentExecutionRecordMapper;
    private final ConversationArchiveMapper conversationArchiveMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final AgentMcpServerService agentMcpServerService;
    private final AgentApiKeyCipher agentApiKeyCipher;
    private final AgentCredentialService credentialService;
    private final AgentSkillPolicyService skillPolicyService;
    private final AgentLifecycleService lifecycleService;
    private final AgentStatsService statsService;

    @Autowired
    public AgentServiceImpl(SubTaskService subTaskService,
                            RewardService rewardService,
                            ActivityLogService activityLogService,
                            AgentInboxMapper agentInboxMapper,
                            AgentDutyLeaseMapper agentDutyLeaseMapper,
                            AgentExecutionRecordMapper agentExecutionRecordMapper,
                            ConversationArchiveMapper conversationArchiveMapper,
                            ConversationMessageMapper conversationMessageMapper,
                            AgentMcpServerService agentMcpServerService,
                            AgentApiKeyCipher agentApiKeyCipher,
                            AgentCredentialService credentialService,
                            AgentSkillPolicyService skillPolicyService,
                            AgentLifecycleService lifecycleService,
                            AgentStatsService statsService) {
        this.subTaskService = subTaskService;
        this.rewardService = rewardService;
        this.activityLogService = activityLogService;
        this.agentInboxMapper = agentInboxMapper;
        this.agentDutyLeaseMapper = agentDutyLeaseMapper;
        this.agentExecutionRecordMapper = agentExecutionRecordMapper;
        this.conversationArchiveMapper = conversationArchiveMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.agentMcpServerService = agentMcpServerService;
        this.agentApiKeyCipher = agentApiKeyCipher;
        this.credentialService = credentialService;
        this.skillPolicyService = skillPolicyService;
        this.lifecycleService = lifecycleService;
        this.statsService = statsService;
    }

    // ══════════════════════════════════════════════════════════════
    //  注册 / 基础 CRUD（不变）
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Agent register(String name, AgentRole role, String description) {
        var existing = lambdaQuery().eq(Agent::getName, name).one();
        if (existing != null) {
            throw new BizException("名称 '" + name + "' 已被注册");
        }
        Agent agent = new Agent();
        agent.setName(name);
        agent.setRole(role);
        // 等保存储加密：apiKey 以 enc:v1:AES-GCM 密文落库，明文仅本次注册响应返回一次
        String plainKey = credentialService.issueConsumerToken();
        agent.setApiKey(agentApiKeyCipher.encrypt(plainKey));
        agent.setApiKeyHash(agentApiKeyCipher.sha256Hex(plainKey));
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setScore(0);
        agent.setRemark(description);
        // 补全默认值
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setCapabilities(new java.util.HashMap<>());
        agent.setLabels(new java.util.HashMap<>());
        agent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        save(agent);
        log.info("Agent 注册成功: name={}, role={}, id={}, accessType={}, consumerTokenIssued={}",
                name, role, agent.getId(), agent.getAccessType(), agent.getApiKey() != null);
        if (role == AgentRole.EXECUTOR) {
            agentMcpServerService.enableDefaultsForAgent(agent.getId());
        }

        return agent;
    }

    /**
     * name 幂等注册（get-or-create）。
     *
     * <p>同名 Agent 已存在时复用而非报错：校验 role 一致后将其归位
     * （status 置回 ACTIVE、SLEEPING 置回 OFFLINE）并直接返回，不重发 consumerToken。
     * 供 E2E 脚本等可重入场景使用，收敛时间戳注册导致的 Agent 膨胀；
     * 人工注册仍走 {@link #register} 保留严格重名报错。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Agent registerOrGet(String name, AgentRole role, String description) {
        Agent existing = lambdaQuery().eq(Agent::getName, name).one();
        if (existing == null) {
            return register(name, role, description);
        }
        if (existing.getRole() != role) {
            throw new BizException("名称 '" + name + "' 已被角色 " + existing.getRole() + " 注册，无法以 " + role + " 复用");
        }
        boolean changed = false;
        if (existing.getStatus() != AgentStatus.ACTIVE) {
            existing.setStatus(AgentStatus.ACTIVE);
            changed = true;
        }
        if (existing.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            existing.setOnlineStatus(AgentOnlineStatus.OFFLINE);
            changed = true;
        }
        if (changed) {
            updateById(existing);
        }
        log.info("Agent 幂等复用: name={}, role={}, id={}, 归位={}", name, role, existing.getId(), changed);
        return existing;
    }

    @Override
    public Agent getByApiKey(String apiKey) {
        // 1) 主路径：hash 点查（等保加密后 AES-GCM 密文不可 SQL eq 匹配）
        Agent agent = lambdaQuery().eq(Agent::getApiKeyHash, agentApiKeyCipher.sha256Hex(apiKey)).one();
        if (agent == null) {
            // 2) 兜底：存量明文行 hash 未回填（Flyway 回填失败场景），逐条明文比对
            agent = findLegacyPlaintextAgent(apiKey);
        }
        if (agent == null) {
            return null;
        }
        if (!agentApiKeyCipher.matches(apiKey, agent.getApiKey())) {
            // 哈希碰撞防御：定位命中但解密比对不一致，视为未命中
            return null;
        }
        lazyMigrateToEncrypted(agent, apiKey);
        return agent;
    }

    /**
     * 认证内核专用：按 API Key 校验并取回 Agent（无效 401 / 已禁用 403）。
     * 由 system 域 AuthService 原逻辑下沉而来（§3.x 依赖方向红线），
     * 业务语义与 {@link #getByApiKey} 完全一致，仅追加状态校验。
     */
    @Override
    public Agent validateApiKey(String apiKey) {
        Agent agent = getByApiKey(apiKey);
        if (agent == null) {
            throw new BizException(401, "无效的 API Key");
        }
        if (agent.getStatus() == AgentStatus.DISABLED) {
            throw new BizException(403, "Agent 已禁用");
        }
        return agent;
    }

    /** 存量明文兜底：hash 列为空的行逐条明文比对（Agent 表规模小，可接受）。 */
    private Agent findLegacyPlaintextAgent(String apiKey) {
        List<Agent> legacy = lambdaQuery().isNull(Agent::getApiKeyHash).list();
        for (Agent a : legacy) {
            if (a.getApiKey() != null && agentApiKeyCipher.matches(apiKey, a.getApiKey())) {
                return a;
            }
        }
        return null;
    }

    /** 惰性迁移：认证命中且仍为存量明文时，加密 + hash 双写（失败仅告警，不影响认证）。 */
    private void lazyMigrateToEncrypted(Agent agent, String plainKey) {
        if (agentApiKeyCipher.isEncrypted(agent.getApiKey())) {
            return;
        }
        try {
            String encrypted = agentApiKeyCipher.encrypt(plainKey);
            lambdaUpdate()
                    .set(Agent::getApiKey, encrypted)
                    .set(Agent::getApiKeyHash, agentApiKeyCipher.sha256Hex(plainKey))
                    .eq(Agent::getId, agent.getId())
                    .update();
            agent.setApiKey(encrypted);
            agent.setApiKeyHash(agentApiKeyCipher.sha256Hex(plainKey));
            log.info("agent.api_key 惰性加密迁移完成: agentId={}", agent.getId());
        } catch (Exception e) {
            log.warn("agent.api_key 惰性加密迁移失败（不影响本次认证，下次重试）: agentId={}", agent.getId(), e);
        }
    }

    @Override
    public List<Agent> listByRole(AgentRole role) {
        return lambdaQuery().eq(Agent::getRole, role).list();
    }

    @Override
    public List<Agent> listActive() {
        return lambdaQuery().eq(Agent::getStatus, AgentStatus.ACTIVE)
                .orderByDesc(Agent::getScore).list();
    }

    @Override
    public List<Agent> listAssignableExecutors() {
        // 内部 LLM Agent（API_KEY_LLM）由平台代为调用，无视在线态恒可指派；
        // 外部 Agent 要求在线（ONLINE/IDLE），避免改派给离线 Agent 后命令无人消费
        return listByRole(AgentRole.EXECUTOR).stream()
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM
                        || a.getOnlineStatus() == AgentOnlineStatus.ONLINE
                        || a.getOnlineStatus() == AgentOnlineStatus.IDLE)
                .sorted(Comparator.comparingInt((Agent a) -> a.getAccessType() == AgentAccessType.API_KEY_LLM ? 0 : 1)
                        .thenComparing(Comparator.comparing(Agent::getScore, Comparator.nullsLast(Comparator.reverseOrder()))))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long agentId, AgentStatus status) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        agent.setStatus(status);
        updateById(agent);
        log.info("Agent 状态变更: id={}, status={}", agentId, status);
    }

    @Override
    public String resetApiKey(Long agentId) {
        return credentialService.resetApiKey(agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAgent(Long agentId, String name, String modelType, String remark) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        if (name != null) agent.setName(name);
        if (modelType != null) agent.setModelType(modelType);
        if (remark != null) agent.setRemark(remark);
        updateById(agent);
        log.info("Agent 信息更新: id={}", agentId);
    }

    /**
     * 注册 Agent 并附加可选扩展字段（modelType/modelConfig/skills）。
     *
     * <p>按 §6.3 分层红线从 AdminAgentController 收口；作为事务代理入口，
     * 内部 {@link #register} 的事务注解在自调用场景不生效，由本方法统一托管。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Agent registerWithExtras(String name, AgentRole role, String description,
                                    String modelType, Map<String, Object> modelConfig,
                                    List<String> skills) {
        // 创建 Agent 前校验 modelType 格式、模型可用性、角色唯一性
        skillPolicyService.validateModelType(modelType, role, null);
        Agent agent = register(name, role, description);
        agent.setModelType(modelType);
        if (modelConfig != null) agent.setModelConfig(modelConfig);
        // 技能按模型能力校验 + 推导落库（thinking 锁定、白名单过滤、自定义豁免）
        skillPolicyService.validateAgentSkills(agent.getModelType(), skills);
        agent.setSkills(skillPolicyService.deriveSkillsForRegistration(agent, skills));
        updateById(agent);
        return agent;
    }

    /**
     * 更新 Agent 扩展字段；Agent 不存在时返回 false（Controller 保持原有 R.fail 语义）。
     *
     * <p>按 §6.3 分层红线从 AdminAgentController 收口；仅非 null 字段生效。
     * 编辑时校验 modelType（允许保留原有 modelType 不传）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAgentDetail(Long agentId, String name, String modelType, Map<String, Object> modelConfig,
                                     String remark, List<String> skills) {
        Agent agent = getById(agentId);
        if (agent == null) return false;
        // 编辑 modelType 时校验（排除当前 Agent 自身）
        if (modelType != null && !modelType.isBlank()) {
            skillPolicyService.validateModelType(modelType, agent.getRole(), agentId);
            agent.setModelType(modelType);
        }
        if (name != null) agent.setName(name);
        if (modelConfig != null) agent.setModelConfig(modelConfig);
        if (remark != null) agent.setRemark(remark);
        if (skills != null) {
            // 技能先按模型能力校验（标准技能查白名单、自定义豁免、未识别模型放行），
            // 再按能力驱动重写落库（thinking 锁定不回退）
            skillPolicyService.validateAgentSkills(agent.getModelType(), skills);
            agent.setSkills(skillPolicyService.deriveSkillsForRegistration(agent, skills));
        }
        updateById(agent);
        log.info("Agent 信息更新: id={}", agentId);
        return true;
    }

    /**
     * 校验 Agent skills 不超出模型能力。
     *
     * <p>规则（D2=A 标准校验 + 自定义豁免）：仅标准技能标签查模型白名单
     * （capabilitySkills ∪ availableOptionalSkills）；非标准项视为自定义技能豁免；
     * modelType 为 null/blank 或模型未识别（表中不存在）时直接通过（降级兼容）。</p>
     */
    @Override
    public void validateAgentSkills(String modelType, List<String> skills) {
        skillPolicyService.validateAgentSkills(modelType, skills);
    }

    /**
     * 收口技能落库推导。
     *
     * <p>API_KEY_LLM 且 modelType 已识别 → 能力驱动推导（能力锁定 + 白名单过滤 + 自定义豁免）；
     * 其他接入类型或未识别模型 → 走基础推导（显式优先）。</p>
     */
    @Override
    public List<String> deriveSkillsForRegistration(Agent agent, List<String> explicitSkills) {
        return skillPolicyService.deriveSkillsForRegistration(agent, explicitSkills);
    }

    /**
     * 校验 modelType 格式、可用性及角色唯一性。
     *
     * <p>格式：providerCode:modelName。模型须启用。同模型在同一角色下只能被一个 API_KEY_LLM Agent 使用。</p>
     *
     * @param modelType       待校验的 modelType，null/blank 时跳过校验（保留原值场景）
     * @param role            Agent 角色
     * @param excludeAgentId  排除的 Agent ID（编辑自身时排除；新增时传 null）
     */
    @Override
    public void validateModelType(String modelType, AgentRole role, Long excludeAgentId) {
        skillPolicyService.validateModelType(modelType, role, excludeAgentId);
    }

    /**
     * 持久化注册后置的可选字段变更（AgentController.applyRegistrationExtras 收口）。
     */
    @Override
    public void updateAgentExtras(Agent agent) {
        updateById(agent);
    }

    /**
     * Agent 总数（收口 AdminAgentController 的 lambdaQuery().count() 直调）。
     */
    @Override
    public long countAll() {
        return lambdaQuery().count();
    }

    /**
     * 全量 Agent 按积分倒序（收口 ScoreController 的 lambdaQuery() 直调）。
     */
    @Override
    public List<Agent> listAllOrderByScoreDesc() {
        return lambdaQuery().orderByDesc(Agent::getScore).list();
    }

    // ══════════════════════════════════════════════════════════════
    //  分页列表
    // ══════════════════════════════════════════════════════════════

    @Override
    public Page<Agent> listAgentsPaged(int pageNum, int pageSize, AgentRole role, AgentStatus status,
                                       String keyword, String sortBy, String sortOrder) {
        var wrapper = new LambdaQueryWrapper<Agent>()
                .eq(role != null, Agent::getRole, role)
                .eq(status != null, Agent::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Agent::getName, keyword)
                        .or().like(Agent::getRemark, keyword));
        if ("score".equals(sortBy)) {
            wrapper.orderBy(true, "asc".equalsIgnoreCase(sortOrder), Agent::getScore);
        } else {
            wrapper.orderBy(true, "asc".equalsIgnoreCase(sortOrder), Agent::getCreateTime);
        }
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    // ══════════════════════════════════════════════════════════════
    //  Workload
    // ══════════════════════════════════════════════════════════════

    @Override
    public Map<String, Integer> workloadStats(Long agentId) {
        return statsService.workloadStats(agentId);
    }

    @Override
    public int inProgressCount(Long agentId) {
        return statsService.inProgressCount(agentId);
    }

    @Override
    public int scoreRank(Long agentId) {
        return statsService.scoreRank(agentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  详情
    // ══════════════════════════════════════════════════════════════

    @Override
    public Agent getAgentDetail(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        return agent;
    }

    // ══════════════════════════════════════════════════════════════
    //  关联统计
    // ══════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getRelatedCounts(Long agentId) {
        // 阶段五：关联统计收口到 AgentStatsService（经 task 域服务接口取数）
        return statsService.getRelatedCounts(agentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  级联删除
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteAgentCascade(Long agentId, String confirmName) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        if (!agent.getName().equals(confirmName)) {
            throw new BizException("名称不匹配，请确认后重试");
        }

        // 先统计（阶段五：经 task 域服务接口，不再直捅 task.mapper）
        int subTaskCount = (int) subTaskService.countByAssignedAgent(agentId);
        int reviewCount = (int) subTaskService.countReviewByReviewerAgent(agentId);
        int rewardCount = (int) rewardService.countByAgent(agentId);
        int activityCount = (int) activityLogService.countByAgent(agentId);

        // unlink 子任务（assigned_agent_id 置空，保留任务与审查记录）
        subTaskService.unlinkByAssignedAgent(agentId);

        // 清理级联数据（物理删除：@TableLogic 会把普通 delete 改写为 UPDATE deleted=1，
        // 这里走 task 域服务的自定义 DELETE SQL 真删，不留残留行）
        rewardService.physicalDeleteByAgent(agentId);
        activityLogService.physicalDeleteByAgent(agentId);
        agentMcpServerService.physicalDeleteByAgentId(agentId);
        // agent_inbox / agent_duty_lease 对 agent.id 有外键约束，必须先于 agent 行删除
        agentInboxMapper.physicalDeleteByAgentId(agentId);
        agentDutyLeaseMapper.physicalDeleteByAgentId(agentId);

        baseMapper.physicalDeleteById(agentId);

        log.info("Agent 级联删除完成: id={}, name={}, reward={}, activity={}",
                agentId, agent.getName(), rewardCount, activityCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentName", agent.getName());
        result.put("subTaskCount", subTaskCount);
        result.put("reviewCount", reviewCount);
        result.put("rewardCount", rewardCount);
        result.put("activityCount", activityCount);
        return result;
    }

    // ══════════════════════════════════════════════════════════════
    //  积分明细 / 活动日志
    // ══════════════════════════════════════════════════════════════

    @Override
    public Page<RewardLog> getScoreLogs(Long agentId, int pageNum, int pageSize) {
        return statsService.getScoreLogs(agentId, pageNum, pageSize);
    }

    @Override
    public Page<ActivityLog> getActivityLogs(Long agentId, int pageNum, int pageSize, String action) {
        return statsService.getActivityLogs(agentId, pageNum, pageSize, action);
    }

    // ══════════════════════════════════════════════════════════════
    //  SLEEPING 状态管理（实现下沉 AgentLifecycleService）
    //  - sleep：管理员手动暂停 Agent，系统不自动设 SLEEPING
    //  - wake：恢复后设 OFFLINE（让系统心跳自然计算 IDLE/ONLINE，不强行 ONLINE）
    //  - SLEEPING 不写 offline_reason/offline_time
    //  - 每次操作都写 task_timeline 审计（event_type=agent_sleep/agent_wake, role=SYSTEM）
    // ══════════════════════════════════════════════════════════════

    @Override
    public Agent sleepAgent(Long agentId, String operator, String reason) {
        return lifecycleService.sleepAgent(agentId, operator, reason);
    }

    @Override
    public Map<String, Object> sleepAgentBatch(List<Long> agentIds, String operator, String reason) {
        return lifecycleService.sleepAgentBatch(agentIds, operator, reason);
    }

    @Override
    public List<Agent> findSleepingByRole(AgentRole role) {
        return lifecycleService.findSleepingByRole(role);
    }

    @Override
    public Agent wakeAgent(Long agentId, String operator, String reason) {
        return lifecycleService.wakeAgent(agentId, operator, reason);
    }

    @Override
    public void validateModelUniqueInRole(String providerCode, String modelName, AgentRole role, Long excludeAgentId) {
        skillPolicyService.validateModelUniqueInRole(providerCode, modelName, role, excludeAgentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.140 task→agent.mapper 双向红线收口实现
    //  承接 SubTaskServiceImpl / TaskServiceImpl / FeedServiceImpl 的 Mapper 直调
    // ══════════════════════════════════════════════════════════════

    @Override
    public Agent lockByIdForUpdate(Long agentId) {
        return baseMapper.selectByIdForUpdate(agentId);
    }

    @Override
    public List<Agent> listSummaries() {
        return lambdaQuery()
                .select(Agent::getId, Agent::getName, Agent::getRole, Agent::getStatus, Agent::getScore)
                .eq(Agent::getDeleted, 0)
                .list();
    }

    @Override
    public int countExecutionByTaskId(Long taskId) {
        return agentExecutionRecordMapper.countByTaskId(taskId);
    }

    @Override
    public int countUnreadInboxByTaskRef(Long taskId) {
        return agentInboxMapper.countUnreadByTaskRef(taskId);
    }

    @Override
    public int physicalDeleteTaskTrace(Long taskId) {
        // 顺序与 deleteTaskCascade 既有约定一致：inbox 依赖 sub_task/review_record 子查询，
        // 调用方必须同一事务内先于子任务/审查记录执行本方法
        int total = 0;
        total += agentInboxMapper.physicalDeleteByTaskRef(taskId);
        total += agentExecutionRecordMapper.physicalDeleteByTaskId(taskId);
        total += conversationArchiveMapper.physicalDeleteByTaskId(taskId);
        total += conversationMessageMapper.physicalDeleteByTaskId(taskId);
        return total;
    }
}
