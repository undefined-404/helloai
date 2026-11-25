package com.helloai.core.shared.constraint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 约束文档目录（`G-037` 任务约束类型化试点；shared 域，纯 IO + 解析，全域可用）。
 *
 * <p><b>角色</b>：平台级「可被任务引用的契约文档」白名单。拆解侧（planner 域）注入目录摘要
 * 供 LLM 声明引用；执行侧（agent 域）经 {@code internal_load_constraint_doc} 按 {@code ref}+
 * {@code scope} 读取正文。本类位于 {@code shared} 域，两个消费者都顺向依赖，无跨域问题。</p>
 *
 * <p><b>白名单怎么防「任意文件读取」</b>：{@code ref} 必须命中 classpath 上的
 * {@code constraint-docs/manifest.json}，{@code path} 只作为该注册条目内的相对路径解析——
 * 未登记 {@code ref}、未登记 {@code scope} 一律拒绝（未知引用返回「不可用」而非抛异常）。</p>
 *
 * <p><b>三路径语义（G-037 正式引入 §3.1）</b>：{@code path} = 平台侧读取路径，仅供内部工具与
 * 正文内联裁剪使用，<b>禁止注入任何执行上下文</b>（宿主绝对路径对沙箱执行器不可见——外部 agent
 * 据此猜路径造成伪造留痕是试点确认的缺陷）；{@code sandboxPath} = 执行侧卷内相对路径
 * （挂载点 /workspace 之下，外部 agent 辅形态「卷内读取」用）；{@code repoPath} = git 仓库相对
 * 路径（G-033 落地后第一通道，规范随仓库版本一致）。执行侧透出只允许后两者，绝不允许 {@code path}。</p>
 *
 * <p><b>章节为什么按标题匹配而不是行号</b>：docs 治理文档会持续增删改（行号必然漂移）；
 * manifest 只登记「章节标题前缀」这一稳定锚点，运行时解析首段正文、按标题行切分。
 * 文档改版时只需增删 manifest 条目，不维护行号快照（判据 11：模板/文本替换后必须可校验）。</p>
 *
 * <p><b>fail-open 纪律（判据 27：观测不得伤主链）</b>：文件缺失 / manifest 损坏 / 章节未命中
 * 一律返回 {@code unavailable} 结果，<b>绝不抛异常</b>——约束是增强项，读不到就让模型知道
 * 「约束不可用」，不允许把整个拆解 / 执行拖垮。</p>
 */
@Component
public class ConstraintDocCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConstraintDocCatalog.class);

    /** classpath 上的 manifest 位置（与技能包 {@code skills/} 同级概念）。 */
    private static final String MANIFEST_LOCATION = "constraint-docs/manifest.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path docRoot;

    /** manifest 解析结果缓存（静态注册表，volatile 防可见性问题；条目增删需重启）。 */
    private volatile List<ConstraintDocEntry> cachedEntries;

    public ConstraintDocCatalog() {
        this(resolveDocRoot());
    }

    /** 测试 / 定制 docRoot 直连构造。 */
    public ConstraintDocCatalog(Path docRoot) {
        this.docRoot = docRoot;
    }

    /** 目录视图：docRoot 解析——系统属性 / 环境变量优先，缺省相对运行目录的 {@code ../doc}。 */
    private static Path resolveDocRoot() {
        String prop = System.getProperty("helloai.constraint-doc-root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("HELLOAI_CONSTRAINT_DOC_ROOT");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of("..", "doc");
    }

    /** 全量白名单目录（{@code ref} / {@code title} / {@code summary} / sections 元数据，不含正文）。 */
    public List<ConstraintDocEntry> list() {
        List<ConstraintDocEntry> entries = cachedEntries;
        if (entries == null) {
            entries = loadManifest();
            cachedEntries = entries;
        }
        return entries;
    }

    /**
     * 按 {@code ref} + {@code scope} 读取章节正文。
     *
     * <p>入参 {@code scope} 为空 / null = 整文档；非空 = 只取命中的章节。
     * 未登记 ref / scope、文件缺失、解析失败均返回不可用结果（不抛异常）。</p>
     */
    public ConstraintDocLoadResult load(String ref, List<String> scope) {
        ConstraintDocEntry entry = find(ref);
        if (entry == null) {
            return ConstraintDocLoadResult.unavailable(ref, "未登记的约束文档 ref: " + ref);
        }
        Path file = resolveFile(entry);
        if (file == null || !Files.isReadable(file)) {
            return ConstraintDocLoadResult.unavailable(ref,
                    "约束文档文件不可读，请检查 HELLOAI_CONSTRAINT_DOC_ROOT 指向（期望 docRoot=" + docRoot + ", path=" + entry.path() + ")");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<LoadedSection> sections = cutSections(entry, lines, scope);
            if (sections.isEmpty()) {
                return ConstraintDocLoadResult.unavailable(ref,
                        "scope 未命中任何章节（可用章节: " + sectionIds(entry) + "）");
            }
            return new ConstraintDocLoadResult(ref, entry.title(), sections, false, null);
        } catch (IOException e) {
            log.warn("约束文档读取失败（fail-open 降级）: ref={}, path={}, err={}",
                    ref, entry.path(), e.getMessage());
            return ConstraintDocLoadResult.unavailable(ref, "约束文档读取失败: " + e.getMessage());
        }
    }

    private ConstraintDocEntry find(String ref) {
        return list().stream().filter(e -> e.ref().equals(ref)).findFirst().orElse(null);
    }

    private Path resolveFile(ConstraintDocEntry entry) {
        if (entry.path() == null || entry.path().isBlank()) {
            return null;
        }
        Path p = Path.of(entry.path());
        return p.isAbsolute() ? p : docRoot.resolve(p).normalize();
    }

    /**
     * 按标题行切分章节：对每个 section，找到首条命中 {@code titlePattern} 的标题行，
     * 内容 = 该行之后直到下一个「同级或更高级标题行」（CODE_STYLE 的一级标题 {@code ^# N. }）
     * 或文件末尾。未命中的 section 跳过。
     */
    private List<LoadedSection> cutSections(ConstraintDocEntry entry, List<String> lines, List<String> scope) {
        // 定位所有「同级标题行」位置（CODE_STYLE 一级标题形态：`# N. xxx`）
        List<Integer> headingLines = new ArrayList<>();
        Pattern sameLevel = Pattern.compile("^# ");
        for (int i = 0; i < lines.size(); i++) {
            if (sameLevel.matcher(lines.get(i)).find()) {
                headingLines.add(i);
            }
        }
        List<LoadedSection> out = new ArrayList<>();
        for (ConstraintDocSectionMeta meta : entry.sections()) {
            if (scope != null && !scope.isEmpty() && !scope.contains(meta.id())) {
                continue;
            }
            Pattern p = Pattern.compile(meta.titlePattern());
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (p.matcher(lines.get(i)).find()) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                continue;
            }
            // 找到下一个同级标题
            int end = lines.size();
            for (int idx : headingLines) {
                if (idx > start) {
                    end = idx;
                    break;
                }
            }
            StringBuilder content = new StringBuilder();
            for (int i = start; i < end; i++) {
                content.append(lines.get(i)).append('\n');
            }
            out.add(new LoadedSection(meta.id(), meta.title(), content.toString()));
        }
        return out;
    }

    private String sectionIds(ConstraintDocEntry entry) {
        return entry.sections().stream().map(ConstraintDocSectionMeta::id)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private List<ConstraintDocEntry> loadManifest() {
        try {
            ClassPathResource resource = new ClassPathResource(MANIFEST_LOCATION);
            if (!resource.exists()) {
                log.warn("约束文档 manifest 缺失（G-037 降级为空目录）: {}", MANIFEST_LOCATION);
                return List.of();
            }
            String json;
            try (var in = resource.getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = objectMapper.readTree(json);
            List<ConstraintDocEntry> entries = new ArrayList<>();
            JsonNode docs = root.path("docs");
            if (docs.isArray()) {
                for (JsonNode doc : docs) {
                    List<ConstraintDocSectionMeta> sections = new ArrayList<>();
                    JsonNode secs = doc.path("sections");
                    if (secs.isArray()) {
                        for (JsonNode sec : secs) {
                            sections.add(new ConstraintDocSectionMeta(
                                    sec.path("id").asText(),
                                    sec.path("title").asText(""),
                                    sec.path("titlePattern").asText("")));
                        }
                    }
                    entries.add(new ConstraintDocEntry(
                            doc.path("ref").asText(),
                            doc.path("path").asText(),
                            doc.path("title").asText(),
                            doc.path("summary").asText(""),
                            sections,
                            doc.path("sandboxPath").asText(null),
                            doc.path("repoPath").asText(null)));
                }
            }
            return entries;
        } catch (IOException e) {
            log.warn("约束文档 manifest 解析失败（G-037 降级为空目录）: err={}", e.getMessage());
            return List.of();
        }
    }

    /** 目录条目（manifest 一行；不含正文）。 */
    public record ConstraintDocEntry(
            String ref,
            String path,
            String title,
            String summary,
            List<ConstraintDocSectionMeta> sections,
            String sandboxPath,
            String repoPath
    ) {
        /** 兼容构造：未声明执行侧路径（sandboxPath / repoPath 缺省为 null；老条目 / 测试直连）。 */
        public ConstraintDocEntry(String ref, String path, String title, String summary,
                                  List<ConstraintDocSectionMeta> sections) {
            this(ref, path, title, summary, sections, null, null);
        }
    }

    /** 章节元数据（manifest 登记；titlePattern 为标题前缀正则，运行时切分）。 */
    public record ConstraintDocSectionMeta(
            String id,
            String title,
            String titlePattern
    ) {
    }

    /** 读取结果（正常 / 不可用两态；unavailable 时 reason 说明降级原因）。 */
    public record ConstraintDocLoadResult(
            String ref,
            String title,
            List<LoadedSection> sections,
            boolean unavailable,
            String reason
    ) {
        public static ConstraintDocLoadResult unavailable(String ref, String reason) {
            return new ConstraintDocLoadResult(ref, null, List.of(), true, reason);
        }
    }

    /** 已加载章节（id + title + 正文）。 */
    public record LoadedSection(
            String id,
            String title,
            String content
    ) {
    }
}