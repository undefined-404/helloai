package com.helloai.core.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能包目录（REF-1.2a）：扫描结果的<b>进程内缓存壳</b>。
 *
 * <p><b>懒加载</b>：首次访问才扫描——不拖慢启动，也不让坏 md 影响 boot（与
 * {@code ToolRegistryImpl} 的懒加载口径一致）。</p>
 *
 * <p><b>无 TTL</b>：classpath 内容在运行期内不变（jar 不可写；{@code target/classes} 在 devtools
 * 重启时随类加载器一起换掉、Bean 会重建）。TTL 只会带来「某个时刻突然换目录」的不确定行为。
 * 需要重扫时调 {@link #refresh()}（单测与未来外部目录热更的落点）。</p>
 *
 * <p><b>健康面 vs 目录面</b>：{@link #entries()} 含坏包（供技能目录 API 显示原因）；
 * {@link #healthyPackages()} / {@link #byName()} <b>只出健康包</b>——它们喂的是执行链与
 * Planner 白名单，坏包混入会导致「可被指派但注入不出内容」。</p>
 */
@Slf4j
@Component
public class SkillPackageCatalog {

    private final SkillPackageScanner scanner;

    /** null = 尚未加载（懒加载哨兵）。 */
    private volatile List<SkillCatalogEntry> cache;

    public SkillPackageCatalog() {
        this(SkillPackageScanner.forClasspath());
    }

    SkillPackageCatalog(SkillPackageScanner scanner) {
        this.scanner = scanner;
    }

    /** 全部条目（含坏包），按 name 升序。 */
    public List<SkillCatalogEntry> entries() {
        List<SkillCatalogEntry> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = List.copyOf(scanner.scan());
                    cache = current;
                }
            }
        }
        return current;
    }

    public List<SkillCatalogEntry> healthyEntries() {
        return entries().stream().filter(SkillCatalogEntry::healthy).toList();
    }

    /** 健康技能包，按 name 升序（等价于改造前 {@code listPackages()} 的语义）。 */
    public List<SkillPackage> healthyPackages() {
        return healthyEntries().stream().map(SkillCatalogEntry::spec).toList();
    }

    /** 标签 → 技能包（仅健康包）。标签即 frontmatter 的 {@code name}。 */
    public Map<String, SkillPackage> byName() {
        Map<String, SkillPackage> map = new LinkedHashMap<>();
        for (SkillPackage pkg : healthyPackages()) {
            map.put(pkg.name(), pkg);
        }
        return map;
    }

    /** 清空缓存，下次访问重新扫描。 */
    public void refresh() {
        cache = null;
    }
}
