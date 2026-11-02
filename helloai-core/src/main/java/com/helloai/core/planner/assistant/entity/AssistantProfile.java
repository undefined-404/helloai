package com.helloai.core.planner.assistant.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 助理身份（PA-3，V132）。
 *
 * <p>对话页头部身份卡的数据源：name/identity/model/skillIds/pinned/greeting，
 * 字段取自 LobsterAI {@code coworkStore.ts:418-440} 的 Agent 实体（name/identity/model/
 * skillIds/pinned）。只做<b>单助理展示</b>，不做人格市场。</p>
 *
 * <p><b>ownerId 可空</b>：跟随 PA-1「宽松并入」口径——平台预置的助理 owner 为 NULL（全局可见），
 * 用户级助理挂 {@code ownerId}。首期无写入入口，表多为空，读取侧给默认值兜底。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "assistant_profile", autoResultMap = true)
public class AssistantProfile extends BaseEntity {

    /** 助理名。 */
    private String name;

    /** 身份定位（一句）。 */
    private String identity;

    /** 默认模型（展示）。 */
    private String model;

    /** 技能 id 列表（JSONB）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<Long> skillIds;

    /** 是否置顶展示。 */
    private Integer pinned;

    /** 开场白。 */
    private String greeting;

    /** 归属用户 id（null=平台预置全局助理）。 */
    private Long ownerId;
}