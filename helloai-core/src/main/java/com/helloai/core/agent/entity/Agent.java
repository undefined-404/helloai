package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Agent 实体。
 *
 * <p>字段分组：</p>
 * <ul>
 *   <li>身份/调度：name / role / apiKey(consumerToken 工牌) / modelType / modelConfig / specializationSlug / status / score</li>
 *   <li>阶段 0 补全：accessType / capabilities / labels</li>
 *   <li>阶段 4 三件套：lastSeenTime / lastActiveTime / onlineStatus / offlineReason / offlineTime</li>
 * </ul>
 *
 * <p>状态分离原则（v2.4 P1 + v2.2 设计决策）：</p>
 * <ul>
 *   <li>AgentStatus（管理态：ACTIVE/DISABLED）— 鉴权只看这个</li>
 *   <li>AgentOnlineStatus（计算态：ONLINE/IDLE/OFFLINE/SLEEPING）— 调度过滤看这个</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class Agent extends BaseEntity {

    private String name;
    private AgentRole role;

    /**
     * Agent 工牌 consumerToken。
     *
     * <p>语义收口（T2）：
     * <ul>
     *   <li>CLI_CLIENT：继续作为 MCP / HTTP 接入鉴权 token</li>
     *   <li>API_KEY_LLM：只保留平台内身份标识，不再存真实 LLM Secret</li>
     * </ul>
     * 真实 LLM 凭证统一进入 `credential_vault`。</p>
     */
    private String apiKey;

    private String modelType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelConfig;

    private String specializationSlug;
    private AgentStatus status;
    private Integer score;

    // ============================================================
    // 阶段 0 补全字段
    // ============================================================

    /** 接入类型：CLI_CLIENT / API_KEY_LLM / WEB_BROWSER（v2.4 N1） */
    private AgentAccessType accessType;

    /** 能力画像（JSONB，Map 形式）。注册时按 accessType 默认值填充，可独立覆盖。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> capabilities;

    /** 标签（specialty / runtime / region 等，调度标签过滤用） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> labels;

    // ============================================================
    // 阶段 4 三件套心跳 + 计算态在线状态
    // ============================================================

    /** 最近一次心跳时间（heartbeat/拉取/ack 即刷新）— 在线判定依据 */
    private OffsetDateTime lastSeenTime;

    /** 最近一次任务活跃时间（start/submit/claim 即刷新）— 活跃度依据 */
    private OffsetDateTime lastActiveTime;

    /** 计算态在线状态（ONLINE/IDLE/OFFLINE/SLEEPING）— 由系统计算 */
    private AgentOnlineStatus onlineStatus;

    /** 离线原因（仅 OFFLINE 时写）：heartbeat_lost / ping_failed。SLEEPING 不写此字段 */
    private String offlineReason;

    /** 最近一次被判定离线的时间 */
    private OffsetDateTime offlineTime;

    // ============================================================
    // N11 阈值回退字段（V17 新增）
    // ============================================================

    /**
     * 连续失败次数；成功一次清零；>= threshold 时被 ExternalAgentFallbackTask
     * 视为回退候选。
     */
    private Integer consecutiveFailureCount;

    /** 最近一次失败时间（与 consecutive_failure_count 同步刷新） */
    private OffsetDateTime lastFailureTime;

    /**
     * 最近一次回退触发时间；用于 cooldown 判定，避免刚回退的 Agent
     * 在冷却期内被反复触发。
     */
    private OffsetDateTime lastFallbackTime;
}
