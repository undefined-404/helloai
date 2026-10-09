package com.helloai.core.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 技能包目录扫描（REF-1.2a）：classpath {@code skills/plugins/*.md} → 按 frontmatter 解析出技能包。
 *
 * <p><b>目录即真相</b>：新增一个 md 即新增一个技能，无需改 Java 代码。{@code fileName} 由实际文件名推导
 * （frontmatter 禁止声明），因此「文件名唯一」自动保证「fileName 唯一」。</p>
 *
 * <p><b>jar 内可用</b>：用 {@code classpath*:} 前缀 + {@link Resource#getInputStream()}，
 * <b>不用</b> {@code getFile()}（jar 内不可用）。</p>
 *
 * <p><b>排序</b>：结果按 {@code name} 升序（同名前按 {@code fileName}）。扫描顺序在文件系统 / jar /
 * 不同 JVM 下不保证，不排序会让注入段顺序抖动。</p>
 *
 * <p><b>失败处理</b>：坏文件产出 {@link SkillCatalogEntry#corrupt} 条目（不静默丢弃、不抛异常）；
 * 整个目录扫描失败则返回空列表并记 ERROR——空目录会静默关闭全部技能注入，必须是 error 而非 warn。</p>
 */
@Slf4j
public class SkillPackageScanner {

    /** 生产扫描位置：只扫平铺一层，避免误收未来的子目录结构。 */
    public static final String CLASSPATH_LOCATION = "classpath*:skills/plugins/*.md";

    private final String locationPattern;

    private final ResourcePatternResolver resolver;

    public SkillPackageScanner(String locationPattern, ResourcePatternResolver resolver) {
        this.locationPattern = locationPattern;
        this.resolver = resolver;
    }

    public static SkillPackageScanner forClasspath() {
        return new SkillPackageScanner(CLASSPATH_LOCATION, new PathMatchingResourcePatternResolver());
    }

    /** 供测试注入 fixture 目录（如 {@code classpath*:skills/plugins-corrupt/*.md}）。 */
    public static SkillPackageScanner forLocation(String locationPattern) {
        return new SkillPackageScanner(locationPattern, new PathMatchingResourcePatternResolver());
    }

    public List<SkillCatalogEntry> scan() {
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            log.error("技能包目录扫描失败，本次将不注入任何平台技能: location={}, err={}",
                    locationPattern, e.getMessage());
            return List.of();
        }
        List<SkillCatalogEntry> entries = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (fileName == null || !fileName.endsWith(".md")) {
                continue;
            }
            entries.add(readOne(resource, fileName));
        }
        entries.sort(Comparator.comparing(SkillCatalogEntry::name).thenComparing(SkillCatalogEntry::fileName));
        long corrupt = entries.stream().filter(e -> !e.healthy()).count();
        if (corrupt > 0) {
            log.warn("技能包目录存在 {} 个不可用条目（不影响执行链，详见技能目录 API）: location={}",
                    corrupt, locationPattern);
        }
        return entries;
    }

    private SkillCatalogEntry readOne(Resource resource, String fileName) {
        try {
            String raw;
            try (InputStream in = resource.getInputStream()) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            SkillFrontMatter.Split split = SkillFrontMatter.split(raw);
            if (split.broken()) {
                return corruptWithLog(fileName, split.error());
            }
            if (!split.hasFrontMatter()) {
                return corruptWithLog(fileName, "frontmatter 缺失：文件未以 --- 开头（技能包元数据必需）");
            }
            SkillPackage spec = SkillPackageParser.parse(fileName, split.yaml());
            String stem = SkillCatalogEntry.stem(fileName);
            if (!spec.name().equals(stem)) {
                // 非致命：name 是展示与命中用的标签，fileName 才是资源定位；不一致时只提示（校验脚本会 FAIL）
                log.warn("技能包 name 与文件名不一致: fileName={}, name={}（请对齐，校验脚本会判 FAIL）",
                        fileName, spec.name());
            }
            return SkillCatalogEntry.healthy(spec);
        } catch (SkillCorruptException e) {
            return corruptWithLog(fileName, e.getMessage());
        } catch (Exception e) {
            return corruptWithLog(fileName, "读取失败：" + e.getMessage());
        }
    }

    private SkillCatalogEntry corruptWithLog(String fileName, String error) {
        log.warn("技能包不可用: fileName={}, reason={}", fileName, error);
        return SkillCatalogEntry.corrupt(fileName, error);
    }
}
