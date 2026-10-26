package com.helloai.core.agent.runtime.sandbox.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能包资源加载器（`SKX-6`，2026-10-26）：把 classpath 里的技能包读成「待注入的文件列表」。
 *
 * <p><b>包从哪来</b>：随 jar 固化在 {@code classpath:skills/packages/<name>/<version>/}（`SKX-4`
 * 入库，122 文件 / 4.13MB）。刻意<b>不</b>走对象存储 / 挂载卷：离线部署可用、版本随构建可追溯，
 * 也免掉「运行时去下载一个执行依赖」的失败面（论证见计划 §4.3）。</p>
 *
 * <p><b>枚举手法</b>：与 {@code PromptTemplateServiceImpl} 的 {@code onboarding} 技能包同一套
 * ——{@link PathMatchingResourcePatternResolver} + 在 URL 里定位包根前缀取相对路径。不新造方式，
 * 因为 jar 内（{@code jar:file:...!/BOOT-INF/classes!/}）与 IDE 目录（{@code file:.../target/classes/}）
 * 两种形态都要能跑，而 {@link Resource#getFilename()} 会丢掉中间目录层级。</p>
 *
 * <p><b>三道闸门（都在这里，不在注入侧）</b>：</p>
 * <ol>
 *   <li><b>体积上限 16MB</b>：边读边累加，超限立刻抛错——不先把 100MB 读进堆再判断；</li>
 *   <li><b>路径安全</b>：包名 / 版本必须匹配 {@code [A-Za-z0-9._-]+}，相对路径不得含 {@code ..}
 *       或前导 {@code /}——防「包内构造出能跳出目标目录的路径」（当前包是我们自己打的，属纵深防御）；</li>
 *   <li><b>清单校验</b>：{@code MANIFEST.json} 的 {@code fileCount} / {@code totalBytes} /
 *       {@code sha256Tree} 与<b>实际读到的字节</b>逐项比对。这一条才是真正的「打包漏文件」探针——
 *       maven 资源过滤、{@code .gitattributes} 换行转换、增量构建残留都会让 jar 内容与源目录不一致，
 *       而仅看文件数看不出来（能跑与不能跑可能只差一个被 import 的模块）。</li>
 * </ol>
 *
 * <p><b>清单文件本身不注入</b>：{@code MANIFEST.json} 是平台侧元数据（裁剪口径 / 上游 commit），
 * {@code archify} 不需要它；包里 {@code fileCount} / {@code totalBytes} 也是按「不含清单」统计的，
 * 把它一起写进容器会让「注入数 == 清单数」这条自检失真。</p>
 */
@Slf4j
@Component
public class SkillPackageLoader {

    /** classpath 下的包根（不带前缀，便于同时拼定位前缀与 Ant 模式）。 */
    static final String RESOURCE_ROOT = "skills/packages/";

    /**
     * 模式匹配必须用 {@code classpath*:}（不是 {@code classpath:}）。
     *
     * <p><b>为什么</b>：{@code classpath:} + 通配只会在<b>第一个</b>命中的 classpath 根里走目录树。
     * 单测环境有 {@code target/classes} 与 {@code target/test-classes} 两个根，实测只扫到测试夹具、
     * 漏掉了主资源里的 {@code archify}；生产 fat jar 里通常只有一个根所以「看起来没事」——
     * 这类「测试/生产某一边才坏」的差异最难查。{@code classpath*:} 会遍历全部根。</p>
     */
    static final String CLASSPATH_ALL = "classpath*:";

    /** 清单文件名（平台侧元数据，不注入容器）。 */
    static final String MANIFEST_FILE = "MANIFEST.json";

    /**
     * 目录索引的定位锚：包根下的 {@code <name>/<version>/MANIFEST.json}。
     *
     * <p><b>为什么不用「列目录」</b>（2026-10-26 实测，jar 内）：对「两级目录通配」这种模式，
     * jar 里返回的是<b>目录 URL</b>而不是两级条目（Spring 的 jar 遍历只枚举文件、不枚举目录），
     * 于是「按目录名解析 name/version」在 IDE 目录形态能跑、打进 jar 就得到空表。改成<b>以文件结尾</b>
     * 的模式后，匹配器走全条目枚举，三种形态结果一致（IDE / 普通 jar / Boot 嵌套 fat jar）。</p>
     *
     * <p>副作用（有意）：<b>没有 {@code MANIFEST.json} 的包不会出现在目录里</b>——那种包本来就会被
     * {@link #load} 拒绝（无法校验完整性），提前在索引阶段隐身是一致的口径。</p>
     */
    static final String INDEX_ANCHOR = "*/*/" + MANIFEST_FILE;

    /**
     * 单包注入体积上限（字节，16MB）。
     *
     * <p>取 16MB 的理由：{@code archify} 实测 4.13MB，留约 4 倍余量给「将来装更大的技能包」；
     * 同时远低于一次批量写会造成内存 / 传输问题的量级。超限一律拒绝而不是截断——
     * 截断的技能包必然跑不起来，失败要早且要说明原因。</p>
     */
    static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    /** 包名 / 版本段的合法字符（同时满足「是合法路径段」与「不含 {@code .}/{@code ..}」）。 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 已入库技能包的<b>目录索引</b>（只含 name / version，不含内容）。
     *
     * <p><b>为什么允许缓存</b>：classpath 在一个进程生命周期内不可变（包随 jar 固化），
     * 而这份索引会被「工具可用性判定」每轮调用——那是热路径，不能每次都走一遍资源树。
     * 索引不含内容，也就不存在「缓存了旧内容」的问题（内容侧仍是每次重读）。</p>
     */
    private volatile List<BundledSkill> bundledCache;

    /**
     * 已入库的技能包（目录级，`SKX-8` 的可用性判定与 `internal_run_skill` 的名字解析都用它）。
     *
     * @param name    包名（对应 {@code skills/packages/<name>/}）
     * @param version 版本目录名
     */
    public record BundledSkill(String name, String version) {
    }

    /**
     * 列出 classpath 里已入库的全部技能包（名字 + 版本），按 name / version 升序、去重。
     *
     * <p><b>只扫目录不读内容</b>：用于「有没有包可用」与「这个名字对应哪个版本」。
     * 实现上是 {@code skills/packages} 下两级目录的枚举——故意<b>不</b>读
     * {@code skill-release.json} 或 {@code MANIFEST.json}，因为那会把「列目录」变成「读 N 个包」。</p>
     *
     * @return 已入库技能包（可能为空列表，不为 null）
     */
    public List<BundledSkill> bundledSkills() {
        List<BundledSkill> cached = bundledCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (bundledCache != null) {
                return bundledCache;
            }
            bundledCache = scanBundledSkills();
            return bundledCache;
        }
    }

    /** 是否至少有一个技能包已入库（`SKX-8` 生效开关的一部分）。 */
    public boolean hasBundledSkills() {
        return !bundledSkills().isEmpty();
    }

    /**
     * 按名字解析已入库的技能包。
     *
     * <p><b>同名多版本取最高</b>：按 {@code name} 分组后取版本字典序最大者（当前单版本，
     * 多版本是「升级期并存」的情形——保留旧目录不覆盖，取新的那个）。解析失败返回 {@code null}，
     * 由调用方决定措辞（拒绝并列出可用包名），不在此处抛异常。</p>
     *
     * @param name 包名（严格匹配，不做大小写归一）
     * @return 命中的包；未入库 ⇒ {@code null}
     */
    public BundledSkill resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        BundledSkill hit = null;
        for (BundledSkill skill : bundledSkills()) {
            if (!skill.name().equals(trimmed)) {
                continue;
            }
            if (hit == null || skill.version().compareTo(hit.version()) > 0) {
                hit = skill;
            }
        }
        return hit;
    }

    private List<BundledSkill> scanBundledSkills() {
        String prefix = "/" + RESOURCE_ROOT;
        String locationPattern = CLASSPATH_ALL + RESOURCE_ROOT + INDEX_ANCHOR;
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            // 没有任何包时不值得当成错误：返回空表，「没包可用」由调用方表达。
            log.info("SkillPackageLoader: 未发现已入库技能包（{}）", e.getMessage());
            return List.of();
        }
        Set<BundledSkill> found = new LinkedHashSet<>();
        for (Resource resource : resources) {
            String url;
            try {
                url = resource.getURL().toString();
            } catch (IOException e) {
                continue;
            }
            int index = url.indexOf(prefix);
            if (index < 0) {
                continue;
            }
            // tail 形如 "archify/3.1.0/MANIFEST.json"
            String[] segments = url.substring(index + prefix.length()).split("/");
            if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
                continue;
            }
            found.add(new BundledSkill(segments[0], segments[1]));
        }
        List<BundledSkill> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(BundledSkill::name).thenComparing(BundledSkill::version));
        log.info("SkillPackageLoader: 已入库技能包 {}", sorted);
        return List.copyOf(sorted);
    }

    /**
     * 加载并校验一个技能包。
     *
     * @param name    包名（如 {@code archify}）
     * @param version 版本（如 {@code 3.1.0}）
     * @return 待注入的包（文件已排序、哈希树已算、清单已核对）
     * @throws IllegalStateException 路径不安全 / 包不存在 / 超体积上限 / 清单缺失或不一致
     */
    public LoadedSkillPackage load(String name, String version) {
        String safeName = requireSafeSegment(name, "packageName");
        String safeVersion = requireSafeSegment(version, "version");
        String prefix = "/" + RESOURCE_ROOT + safeName + "/" + safeVersion + "/";
        String locationPattern = CLASSPATH_ALL + RESOURCE_ROOT + safeName + "/" + safeVersion + "/**/*";

        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            // 包目录不存在时 resolver 直接抛 IOException（class path resource ... does not exist）：
            // 对调用方而言这就是「没这个包」，措辞要统一，否则每次都要读一长串 Spring 内部文案。
            throw new IllegalStateException("技能包不存在或未打入 jar: " + locationPattern
                    + "（" + e.getMessage() + "）", e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("技能包不存在或未打入 jar: " + locationPattern);
        }

        List<LoadedSkillPackage.Entry> entries = new ArrayList<>(resources.length);
        List<byte[]> digests = new ArrayList<>(resources.length);
        byte[] manifestBytes = null;
        long total = 0L;
        for (Resource resource : resources) {
            String relativePath = relativePathOf(resource, prefix);
            if (relativePath == null) {
                continue;
            }
            byte[] data = readAll(resource);
            if (data == null) {
                continue;
            }
            if (MANIFEST_FILE.equals(relativePath)) {
                manifestBytes = data;
                continue;
            }
            total += data.length;
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("技能包体积超过上限 " + MAX_TOTAL_BYTES + " 字节: "
                        + safeName + "@" + safeVersion + "（累计到 " + relativePath + " 时已达 " + total + "）");
            }
            entries.add(new LoadedSkillPackage.Entry(relativePath, data));
            digests.add(sha256(data));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("技能包为空（只有清单没有载荷）: " + locationPattern);
        }
        if (manifestBytes == null) {
            throw new IllegalStateException("技能包缺少 " + MANIFEST_FILE + "（打包不完整，无法校验完整性）: "
                    + safeName + "@" + safeVersion);
        }

        // 排序口径必须与生成清单的脚本一致：按相对路径的**字节序**（等价于 LC_ALL=C 排序）。
        // 用 String.compareTo 在纯 ASCII 路径上等价，但仍显式按 UTF-8 字节比较，避免将来出现
        // 非 ASCII 文件名时与清单算法分叉。
        Integer[] order = new Integer[entries.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (left, right) -> Arrays.compare(
                entries.get(left).relativePath().getBytes(StandardCharsets.UTF_8),
                entries.get(right).relativePath().getBytes(StandardCharsets.UTF_8)));
        List<LoadedSkillPackage.Entry> sortedEntries = new ArrayList<>(entries.size());
        byte[][] sortedDigests = new byte[entries.size()][];
        for (int i = 0; i < order.length; i++) {
            sortedEntries.add(entries.get(order[i]));
            sortedDigests[i] = digests.get(order[i]);
        }

        String tree = sha256Tree(sortedEntries, sortedDigests);
        verifyManifest(manifestBytes, safeName, safeVersion, sortedEntries.size(), total, tree);

        log.info("SkillPackageLoader: 技能包已就绪 name={}, version={}, files={}, bytes={}, tree={}",
                safeName, safeVersion, sortedEntries.size(), total, tree.substring(0, 16));
        return new LoadedSkillPackage(safeName, safeVersion, List.copyOf(sortedEntries), total, tree);
    }

    /**
     * 清单校验：三项逐一比对实际值，任一不符即拒绝注入。
     *
     * <p><b>为什么不降级成 warn</b>：清单不符意味着「jar 里的包」与「被验证过的包」不是同一份。
     * 而 `SKX-1`/`SKX-3` 那两轮三段门禁（`validate`/`deliver`/`check` 全绿）验证的正是清单描述的
     * 那一份；内容变了却没重跑门禁，装进去的包有什么问题无人知道。fail-close 在这里比 fail-open 便宜。</p>
     */
    private void verifyManifest(byte[] manifestBytes, String name, String version,
                                int actualFileCount, long actualTotalBytes, String actualTree) {
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestBytes);
        } catch (IOException e) {
            throw new IllegalStateException("MANIFEST.json 解析失败: " + e.getMessage(), e);
        }
        String expectedTree = textOf(root, "sha256Tree");
        int expectedFileCount = intOf(root, "fileCount", -1);
        long expectedTotalBytes = longOf(root, "totalBytes", -1L);
        if (expectedFileCount != actualFileCount || expectedTotalBytes != actualTotalBytes) {
            throw new IllegalStateException(String.format(
                    "技能包清单与实际内容不一致 name=%s version=%s：清单 files=%d bytes=%d，实际 files=%d bytes=%d"
                            + "（多半是打包时漏文件或做了资源过滤；需重新生成 MANIFEST.json）",
                    name, version, expectedFileCount, expectedTotalBytes, actualFileCount, actualTotalBytes));
        }
        if (expectedTree != null && !expectedTree.equalsIgnoreCase(actualTree)) {
            throw new IllegalStateException(String.format(
                    "技能包内容哈希与清单不一致 name=%s version=%s：清单 %s…，实际 %s…"
                            + "（内容被改动但未重新验证，须重跑三段门禁后更新清单）",
                    name, version, abbreviate(expectedTree), abbreviate(actualTree)));
        }
    }

    /**
     * 从资源 URL 里取出「相对包根」的路径。
     *
     * <p>返回 {@code null} 表示该 URL 不在包根之下（例如通配匹配到了目录占位项），调用方跳过。
     * 目录项以 {@code /} 结尾，同样跳过。</p>
     */
    private static String relativePathOf(Resource resource, String prefix) {
        String url;
        try {
            url = resource.getURL().toString();
        } catch (IOException e) {
            return null;
        }
        int index = url.indexOf(prefix);
        if (index < 0) {
            return null;
        }
        String relativePath = url.substring(index + prefix.length());
        if (relativePath.isEmpty() || relativePath.endsWith("/")) {
            return null;
        }
        int query = relativePath.indexOf('?');
        if (query >= 0) {
            relativePath = relativePath.substring(0, query);
        }
        if (relativePath.contains("..") || relativePath.startsWith("/")) {
            throw new IllegalStateException("技能包内存在越界路径: " + relativePath);
        }
        return relativePath;
    }

    private static byte[] readAll(Resource resource) {
        if (!resource.isReadable()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取技能包文件失败 " + resource.getDescription()
                    + ": " + e.getMessage(), e);
        }
    }

    /** 包名 / 版本作为容器路径段，必须是安全段（且不能是 {@code .} / {@code ..}）。 */
    private static String requireSafeSegment(String value, String field) {
        if (value == null || value.isBlank() || !SAFE_SEGMENT.matcher(value.trim()).matches()
                || ".".equals(value.trim()) || "..".equals(value.trim())) {
            throw new IllegalStateException("非法的 " + field + ": " + value
                    + "（只允许字母、数字、点、下划线、连字符）");
        }
        return value.trim();
    }

    /** 内容哈希树：{@code sha256( Σ "<fileSha256>  <relativePath>\n" )}，行序为路径字节序。 */
    private static String sha256Tree(List<LoadedSkillPackage.Entry> entries, byte[][] digests) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < entries.size(); i++) {
                digest.update(hex(digests[i]).getBytes(StandardCharsets.UTF_8));
                digest.update("  ".getBytes(StandardCharsets.UTF_8));
                digest.update(entries.get(i).relativePath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算技能包哈希树失败: " + e.getMessage(), e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int intOf(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asInt();
    }

    private static long longOf(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 16 ? value : value.substring(0, 16);
    }
}
