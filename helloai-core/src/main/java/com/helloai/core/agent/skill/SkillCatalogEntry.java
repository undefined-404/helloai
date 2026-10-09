package com.helloai.core.agent.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能目录条目：健康包与坏包<b>共用同一形状</b>，坏包不被静默丢弃（REF-1.2b）。
 *
 * <p><b>为什么坏包也要有 {@code spec}</b>：目录列表需要稳定的排序键与展示标识；
 * {@code spec.name} 取「文件名去 {@code .md}」，即使 frontmatter 完全不可读也能定位是哪个文件坏了。</p>
 *
 * <p><b>注意</b>：坏包<b>只出现在目录（API / 扫描结果）</b>，不进 {@code resolve()} /
 * {@code listPackages()}——后者同时喂 Planner 目录渲染与子任务技能白名单，若混入坏包会出现
 * 「可被指派但注入不出内容且无提示」。</p>
 */
public record SkillCatalogEntry(SkillPackage spec, String error) {

    public SkillCatalogEntry {
        spec = spec == null
                ? new SkillPackage("", "", "", List.of(), List.of(), Map.of(), Map.of(), List.of(), "")
                : spec;
    }

    public static SkillCatalogEntry healthy(SkillPackage spec) {
        return new SkillCatalogEntry(spec, null);
    }

    /** 坏包条目：{@code name} 取文件名去 {@code .md}，其余元数据置空，{@code fileName} 保留实际文件名。 */
    public static SkillCatalogEntry corrupt(String fileName, String error) {
        String safeName = stem(fileName);
        SkillPackage degraded = new SkillPackage(
                safeName, "", "", List.of(), List.of(), Map.of(), Map.of(), List.of(), fileName == null ? "" : fileName);
        return new SkillCatalogEntry(degraded, error);
    }

    public boolean healthy() {
        return error == null;
    }

    public String name() {
        return spec.name();
    }

    public String fileName() {
        return spec.fileName();
    }

    /** 文件名去扩展名；用于坏包的兜底标识。 */
    public static String stem(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
