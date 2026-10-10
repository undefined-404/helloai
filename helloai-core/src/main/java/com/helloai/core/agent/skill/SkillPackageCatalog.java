package com.helloai.core.agent.skill;

import com.helloai.core.agent.entity.InstalledSkillPackage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能包目录（REF-1.2a / REF-1.6）：<b>双源</b>扫描结果的进程内缓存壳。
 *
 * <p><b>两个源</b>：① classpath 内置（{@code skills/plugins/*.md}，随发版走、不可替换）；
 * ② 受控存储已安装（PG {@code skill_package} 的 {@code state=ACTIVE} 行，经安装入口装入）。
 * 与内置同名的安装请求由安装服务直接拒绝（`D-2026-10-10-1⑨`），故两源在正常路径下不重名。</p>
 *
 * <p><b>懒加载</b>：首次访问才加载——不拖慢启动，也不让坏 md 影响 boot（与
 * {@code ToolRegistryImpl} 的懒加载口径一致）。</p>
 *
 * <p><b>无 TTL</b>：classpath 内容在运行期内不变（jar 不可写；{@code target/classes} 在 devtools
 * 重启时随类加载器一起换掉、Bean 会重建）。TTL 只会带来「某个时刻突然换目录」的不确定行为。
 * 需要重扫时调 {@link #refresh()}——**安装 / 激活 / 卸载技能包后的失效钩子**（REF-1.6）。</p>
 *
 * <p><b>健康面 vs 目录面</b>：{@link #entries()} 含坏包（供技能目录 API 显示原因）；
 * {@link #healthyPackages()} / {@link #byName()} <b>只出健康包</b>——它们喂的是执行链与
 * Planner 白名单，坏包混入会导致「可被指派但注入不出内容」。
 * 已安装包在入库前已过闸门与解析校验，不会以坏包形态出现在目录里。</p>
 *
 * <p><b>「当前生效哪一版」恒为单值</b>：同 name 至多一行 ACTIVE 由 V104 的 partial unique
 * index 保证；目录不按版本挑，只按状态取（`D-2026-10-10-1⑧`）。</p>
 */
@Slf4j
@Component
public class SkillPackageCatalog {

    private final SkillPackageScanner scanner;

    /** 已安装源；{@code null} = 无该源（测试装配 / 未启用安装面）。 */
    private final InstalledSkillPackageSource installedSource;

    /** 内置包正文读取口；已安装包的正文随目录快照从 DB 带回，不经过本端口。 */
    private final SkillContentSource contentSource;

    /** null = 尚未加载（懒加载哨兵）。 */
    private volatile Snapshot cache;

    /**
     * 生产装配：内置源 = classpath 扫描，已安装源与正文源由 Spring 注入。
     *
     * <p>{@code @Autowired} 标在本构造器上，Spring 只认它；另两个 public 构造器供测试直接 new。</p>
     */
    @Autowired
    public SkillPackageCatalog(InstalledSkillPackageSource installedSource,
                               SkillContentSource contentSource) {
        this(SkillPackageScanner.forClasspath(), installedSource, contentSource);
    }

    /** 测试 / 无安装面的装配：仅内置源。 */
    public SkillPackageCatalog() {
        this(SkillPackageScanner.forClasspath(), null, new ClasspathSkillContentSource());
    }

    /** 测试装配：指定扫描位置（corrupt fixture 等），仅内置源。 */
    SkillPackageCatalog(SkillPackageScanner scanner) {
        this(scanner, null, new ClasspathSkillContentSource());
    }

    SkillPackageCatalog(SkillPackageScanner scanner,
                        InstalledSkillPackageSource installedSource,
                        SkillContentSource contentSource) {
        this.scanner = scanner;
        this.installedSource = installedSource;
        this.contentSource = contentSource;
    }

    /**
     * 目录快照：条目 + 已安装包正文表（键 {@code name@version}）。
     *
     * <p>正文随快照一起缓存，使 {@code resolve()} 的每轮装配热路径零 DB 往返
     * （`D-2026-10-10-1②`）。</p>
     */
    private record Snapshot(List<SkillCatalogEntry> entries,
                            Map<String, String> installedBodies,
                            Set<String> builtinNames) {
    }

    /** 全部条目（含坏包），按 name 升序。 */
    public List<SkillCatalogEntry> entries() {
        return snapshot().entries();
    }

    public List<SkillCatalogEntry> healthyEntries() {
        return entries().stream().filter(SkillCatalogEntry::healthy).toList();
    }

    /** 健康技能包，按 name 升序（等价于改造前 {@code listPackages()} 的语义）。 */
    public List<SkillPackage> healthyPackages() {
        return healthyEntries().stream().map(SkillCatalogEntry::spec).toList();
    }

    /**
     * 标签 → 技能包（仅健康包）。标签即 frontmatter 的 {@code name}。
     *
     * <p><b>重名不静默</b>：双源合并后同名理论上只可能出现在"安装服务漏拦"的异常路径，
     * 此时记 ERROR 并保留先出现者——排序稳定 ⇒ 内置源在前 ⇒ <b>内置包胜出</b>
     * （内置是平台基线，不可被安装包悄悄改写）。改造前是裸 {@code map.put} 静默覆盖，
     * 与 REF-1.2b「坏包不静默跳过」的口径不一致，此处一并对齐。</p>
     */
    public Map<String, SkillPackage> byName() {
        Map<String, SkillPackage> map = new LinkedHashMap<>();
        for (SkillPackage pkg : healthyPackages()) {
            SkillPackage prev = map.putIfAbsent(pkg.name(), pkg);
            if (prev != null) {
                log.error("技能包标签重复，保留先出现者（内置源优先）: name={}, keptVersion={}, ignoredVersion={}",
                        pkg.name(), prev.version(), pkg.version());
            }
        }
        return map;
    }

    /**
     * 读技能包<b>原始正文</b>（含 frontmatter，未剥离未渲染）。
     *
     * <p>已安装包走目录快照里的正文表（零 I/O）；内置包走 {@link SkillContentSource}
     * （classpath 读取，文件缺失 / 读取失败返回 {@code null}，调用方按"技能不可用"跳过）。</p>
     *
     * @param pkg 技能包描述符
     * @return 原始正文；不可用时 {@code null}
     */
    public String bodyOf(SkillPackage pkg) {
        if (pkg == null) {
            return null;
        }
        String installed = snapshot().installedBodies().get(contentKey(pkg.name(), pkg.version()));
        if (installed != null) {
            return installed;
        }
        return contentSource == null ? null : contentSource.readBody(pkg);
    }

    /** 清空缓存，下次访问重新加载两源。安装 / 激活 / 卸载技能包后调用。 */
    public void refresh() {
        cache = null;
    }

    // ────────────────────────────────────────────────────────────
    //  内部
    // ────────────────────────────────────────────────────────────

    private Snapshot snapshot() {
        Snapshot current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = load();
                    cache = current;
                }
            }
        }
        return current;
    }

    private Snapshot load() {
        List<SkillCatalogEntry> all = new ArrayList<>(scanner.scan());
        Map<String, String> bodies = new LinkedHashMap<>();
        // 内置包名在并入已安装源**之前**收集，否则会把已安装的也算成内置。
        Set<String> builtinNames = new LinkedHashSet<>();
        for (SkillCatalogEntry entry : all) {
            builtinNames.add(entry.spec().name());
        }
        if (installedSource != null) {
            try {
                for (InstalledSkillPackage row : installedSource.activePackages()) {
                    all.add(SkillCatalogEntry.healthy(toSpec(row)));
                    bodies.put(contentKey(row.getName(), row.getVersion()), row.getBody());
                }
            } catch (Exception e) {
                // 已安装源故障不得连累内置源：内置技能是执行链的基线，缺失会让技能注入整链失效。
                log.error("已安装技能包读取失败，本次仅加载内置源: err={}", e.getMessage());
            }
        }
        // 按 name 升序（既有语义）。Collections.sort 稳定 ⇒ 同名时内置包（先加入）在前，
        // 与 byName() 的「内置胜出」约定一致。
        all.sort(Comparator.comparing(entry -> entry.spec().name()));
        return new Snapshot(List.copyOf(all), Map.copyOf(bodies), Set.copyOf(builtinNames));
    }

    /**
     * 该标签是否已被 classpath 内置包占用。
     *
     * <p>内置包随发版走、不可替换（`D-2026-10-10-1⑨`），安装服务据此**拒绝同名安装**，
     * 避免发版基线与运行内容分叉。</p>
     */
    public boolean isBuiltinName(String name) {
        return name != null && snapshot().builtinNames().contains(name);
    }

    /** 正文表键：{@code name@version}（多版本共存后 name 不再唯一指向一份内容）。 */
    private static String contentKey(String name, String version) {
        return (name == null ? "" : name) + "@" + (version == null ? "" : version);
    }

    /** 已安装行 → 元数据描述符。{@code fileName} 置空：已安装包无 classpath 物理文件。 */
    private static SkillPackage toSpec(InstalledSkillPackage row) {
        return new SkillPackage(
                row.getName(),
                row.getVersion(),
                row.getDescription(),
                row.getRequiredTools(),
                row.getDependencies(),
                row.getInputSchema(),
                row.getOutputSchema(),
                row.getValidationRules(),
                "");
    }
}
