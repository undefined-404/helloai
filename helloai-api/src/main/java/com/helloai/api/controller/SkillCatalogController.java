package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.skill.SkillPackageResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.skill.SkillCatalogEntry;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.skill.SkillPackageCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台技能包目录查询（REF-1.2c）。
 *
 * <p><b>为什么需要它</b>：技能元数据迁移到 md frontmatter 后，前端原先手抄的
 * {@code ENG_SKILL_OPTIONS} 对齐副本必须消灭（否则新增技能「后端生效、前端看不见」）。
 * 本端点即服务端下发的数据源。</p>
 *
 * <p><b>关键设计</b>：{@code label} 由服务端从 {@code description} 派生，已逐条核对与迁移前
 * 前端硬编码的 4 条字面量（如 {@code eng-code-review（代码评审规范）}）<b>逐字相同</b>——
 * 因此本次改造对用户可见文案零变化。</p>
 *
 * <p><b>坏技能包显式可见</b>：{@code corrupt=true} + 人可读 {@code error}，不静默丢弃
 * （REF-1.2b）；但坏包不参与执行链与 Planner 白名单（由目录层过滤）。</p>
 *
 * <p>命名：{@code skills} + {@code catalog}（平台能力包），刻意区别于
 * {@code AdminAgentController.getMySkillZipByAgentId}（Agent 角色接入手册 ZIP）。</p>
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillCatalogController {

    /** 描述中用作 label 分隔的全角冒号。 */
    private static final char DESCRIPTION_SEPARATOR = '：';

    private final SkillPackageCatalog skillPackageCatalog;

    @SaCheckPermission("skill:view")
    @GetMapping("/catalog")
    public R<List<SkillPackageResponse>> catalog() {
        return R.ok(skillPackageCatalog.entries().stream().map(this::toResponse).toList());
    }

    private SkillPackageResponse toResponse(SkillCatalogEntry entry) {
        SkillPackage spec = entry.spec();
        SkillPackageResponse response = new SkillPackageResponse();
        response.setName(spec.name());
        response.setVersion(spec.version());
        response.setDescription(spec.description());
        response.setRequiredTools(spec.requiredTools());
        response.setDependencies(spec.dependencies());
        response.setInputSchema(spec.inputSchema());
        response.setOutputSchema(spec.outputSchema());
        response.setValidationRules(spec.validationRules());
        response.setFileName(spec.fileName());
        response.setLabel(labelOf(spec));
        response.setCorrupt(!entry.healthy());
        response.setError(entry.error());
        return response;
    }

    /**
     * 显示名 = {@code name（描述首段）}。描述取首个全角冒号之前的部分；无冒号则取整串；
     * 描述为空时退化为 {@code name} 本身（坏包走这条）。
     */
    private String labelOf(SkillPackage spec) {
        String description = spec.description() == null ? "" : spec.description().trim();
        if (description.isEmpty()) {
            return spec.name();
        }
        int cut = description.indexOf(DESCRIPTION_SEPARATOR);
        String shortDescription = cut > 0 ? description.substring(0, cut) : description;
        return spec.name() + "（" + shortDescription + "）";
    }
}
