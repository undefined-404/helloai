package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具目录装配配置（MA-5「目录 + 按需取」第一步）。
 *
 * <p><b>白名单初值来源（MA5-2，2026-11-19 实测）</b>：按 {@code agent_event} 的
 * {@code tool_call_started} 聚合近 14 天调用频次（2026-11-03 ~ 2026-11-17，样本 151 次调用），
 * 取「高频 + 跨子任务分布广」的 top 4：{@code internal_run_in_sandbox}（56 次 / 14 子任务）、
 * {@code internal_get_sub_task_detail}（17 / 14）、{@code internal_load_skill}（16 / 10）、
 * {@code internal_get_deps_summary}（13 / 12）。{@code internal_attachment_digest}（13 次）虽高频
 * 但全部集中于 1 个子任务（单任务内批量调用），代表性差，不设白名单。观察期后可再调。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.tool-catalog")
public class ToolCatalogProperties {

    /**
     * 目录条目描述摘要封顶（字符/码点）。与 DSH {@code DEFAULT_CATALOG_DESCRIPTION_MAX_LENGTH=160}
     * 对齐——目录本身不能变成新负担；&lt;=0 视为不截断（全部保留完整描述 = 回到现状）。
     */
    private int descriptionMaxLength = 160;

    /**
     * 高频核心工具白名单：名单内工具在目录中保持<b>完整描述</b>（不摘要）。
     * 初值基于 MA5-2 的 {@code agent_event} 调用统计（见类 javadoc），观察期后按新统计再调。
     */
    private List<String> fullDefinitionTools = new ArrayList<>(List.of(
            "internal_run_in_sandbox",
            "internal_get_sub_task_detail",
            "internal_load_skill",
            "internal_get_deps_summary"));
}