package com.helloai.core.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * classpath 内置技能包正文读取（{@code skills/plugins/*.md}）。
 *
 * <p><b>行为与改造前逐字一致</b>：读取段整体自
 * {@code AgentSkillSpecServiceImpl#loadSpeedSummary} 搬出——文件缺失记 WARN 返回
 * {@code null}，读取异常记 WARN 返回 {@code null}（best-effort，不阻断执行链）。
 * 位置前缀与 {@link SkillPackageScanner} 的扫描位置同源（{@code skills/plugins/}）。</p>
 */
@Slf4j
@Component
public class ClasspathSkillContentSource implements SkillContentSource {

    /** 内置技能包目录（REF-1.2a 起的事实源位置）。 */
    private static final String LOCATION_PREFIX = "skills/plugins/";

    @Override
    public String readBody(SkillPackage pkg) {
        if (pkg == null) {
            return null;
        }
        String fileName = pkg.fileName();
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        try {
            ClassPathResource resource = new ClassPathResource(LOCATION_PREFIX + fileName);
            if (!resource.exists()) {
                log.warn("平台技能规范文件缺失，跳过: label={}, path={}", pkg.name(), fileName);
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("平台技能规范读取失败，跳过该规范（不阻断执行链）: label={}, err={}",
                    pkg.name(), e.getMessage());
            return null;
        }
    }
}
