package com.helloai.core.planner.memory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 用户结构化画像（PA-2，V132）。
 *
 * <p><b>职责分层</b>：{@code user_profile} 存<b>结构化画像字段</b>（name/role/preferences/style/note，
 * 规则抽取 + 合并 + 时间戳覆盖），是「精选画像」；{@code long_term_memory} 的 USER 型存<b>精选记忆
 * 子集</b>；{@code requirement_message} 是<b>真相源</b>（只存不查）。三者共同落实 QwenPaw 双轨分层
 * 但超越其「纯 Markdown 无校验」短板。</p>
 *
 * <p><b>profile_json 变迁</b>：{@code version} 每次合并 +1（时间戳覆盖冲突由调用方判定）；
 * {@code source} 标记抽取来源（RULE 规则打底，后续 LLM 抽取可填 LLM）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "user_profile", autoResultMap = true)
public class UserProfile extends BaseEntity {

    /** 归属用户 id（非空，画像必须挂人）。 */
    private Long userId;

    /** 结构化画像字段（JSONB）：name/role/preferences[]/style/note。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> profileJson;

    /** 抽取来源：RULE（规则打底）/ LLM（后续 Provider）。 */
    private String source;

    /** 画像版本：每次字段合并 +1（冲突覆盖以新值/新时间戳为准）。 */
    private Integer version;
}