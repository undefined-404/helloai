package com.helloai.api.dto.skill;

/**
 * 需求对话「＋→技能」里的产出型技能选项（2026-10-26）。
 *
 * <p><b>为什么单独一个 DTO</b>：完整目录（{@link SkillPackageResponse}）带 schema / 校验规则 /
 * 拆解依赖，是给 Agent 配置页用的；对话菜单只需要「名字 + 一句说明」。
 * 更重要的是<b>权限面不同</b>——目录要 {@code skill:view}（管理员面），
 * 而对话入口面向普通需求方（{@code conversation:add}）。混用会让普通用户看不到任何技能。</p>
 *
 * @param name        技能包名（发送时随消息以 {@code [skill:name]} 前缀下发）
 * @param version     版本（仅展示与排障用）
 * @param description 一句话说明（前端截断展示）
 */
public record ChatSkillOption(String name, String version, String description) {
}
