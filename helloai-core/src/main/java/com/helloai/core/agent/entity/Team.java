package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.TeamStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Team 实体（N-002，C2-S1）。
 *
 * <p>命名可复用的 Agent 组合；生命周期 DRAFT/ACTIVE/ARCHIVED。
 * 组合关系在 {@code team_member} 表，agent 表零侵入。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team")
public class Team extends BaseEntity {

    /** 命名组合（唯一）。 */
    private String name;

    private String description;

    /** 生命周期：DRAFT / ACTIVE / ARCHIVED。 */
    private TeamStatus status;
}
