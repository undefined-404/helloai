package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.skill.ChatSkillOption;
import com.helloai.api.dto.skill.SkillPackageResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.skill.SkillCatalogEntry;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.skill.SkillPackageCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillCatalogController {

    /** 描述中用作 label 分隔的全角冒号。 */
    private static final char DESCRIPTION_SEPARATOR = '：';

    /** 「能在沙箱里执行技能 CLI」的工具名（技能包 frontmatter 的 requiredTools 里声明）。 */
    private static final String RUN_SKILL_TOOL = "internal_run_skill";

    private final SkillPackageCatalog skillPackageCatalog;

    @SaCheckPermission("skill:view")
    @GetMapping("/catalog")
    public R<List<SkillPackageResponse>> catalog() {
        return R.ok(skillPackageCatalog.entries().stream().map(this::toResponse).toList());
    }

    /**
     * 需求对话「＋→技能」可选清单（2026-10-26，2026-10-27 收紧）。
     *
     * <p><b>只返回「能独立出产物」的技能包</b>，两条判据同时成立：</p>
     * <ol>
     *   <li>{@code requiredTools} 声明了 {@code internal_run_skill}——有它才能在沙箱里跑技能 CLI，
     *       产物才能经 {@code internal_export_artifact} 变成可下载附件。{@code eng-*} 那批是写作规范，
     *       混进菜单会让用户以为点了就会出文件。</li>
     *   <li>{@code dependencies} 为空——声明了依赖的包是「某个技能的规范补充」，不是独立入口。
     *       典型例子 {@code biz-process-lifecycle}：它只写「状态机该怎么建」，真正渲染靠依赖包
     *       {@code archify}。单列进菜单会让人以为选它就能出图，实际还要靠 archify 才跑得起来。
     *       这类包改为「被依赖时自动并入」：用户选 archify，执行链按 {@code SkillDirective}
     *       的依赖展开把它一起带上（需要建模纪律时仍然拿得到）。</li>
     * </ol>
     *
     * <p><b>权限刻意不同</b>：目录端点要 {@code skill:view}（管理面），本端点只要
     * {@code conversation:add}——需求方不该为了选个画图技能去申请技能库权限。</p>
     */
    @SaCheckPermission("conversation:add")
    @GetMapping("/chat-options")
    public R<List<ChatSkillOption>> chatOptions() {
        List<SkillCatalogEntry> entries = skillPackageCatalog.entries();
        List<ChatSkillOption> options = entries.stream()
                .filter(SkillCatalogEntry::healthy)
                .map(SkillCatalogEntry::spec)
                .filter(spec -> spec.requiredTools().contains(RUN_SKILL_TOOL))
                .filter(spec -> spec.dependencies().isEmpty())
                .map(spec -> new ChatSkillOption(spec.name(), spec.version(), spec.description()))
                .toList();
        // 可观测性：菜单内容由服务端决定，「改了看不见」时第一现场就是这条日志
        // （过滤掉谁也要能看见——否则分不清「没下发」还是「前端没刷新」）。
        log.info("对话技能菜单下发 {} 项: {}；候选 {} 项（已排除规范类与依赖类）",
                options.size(), options.stream().map(ChatSkillOption::name).toList(), entries.size());
        return R.ok(options);
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
