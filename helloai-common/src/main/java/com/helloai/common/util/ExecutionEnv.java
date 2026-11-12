package com.helloai.common.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务级「执行环境」声明（{@code task.context.executionEnv}）的解析与渲染。
 *
 * <p><b>为什么需要这一维</b>：子任务原有四要素（title / content / deliverable / acceptance）全部属于
 * <b>业务维</b>，只回答「做什么、怎么算完」；它不回答「在哪跑、跑得动吗、连什么库」。缺口在数据
 * 结构里根本无处存放，于是拆解模型只能把「仓库中」「数据库中」当成已知事实写进 content，执行者
 * 照着去找必然落空——`2026-11-12` 那次「缺仓库检出 / schema / DB 连接」人工介入就是这么来的。
 * 参考实现把「环境配置」与「问题描述」当成**平级的第一等输入**而不是附属文本。</p>
 *
 * <p><b>借鉴来源（已核对下载到本地的源码）</b>：
 * <ul>
 *   <li>{@code SWE-agent/sweagent/environment/repo.py:42-233} —— {@code RepoConfig} 是四态：
 *       {@code PreExistingRepoConfig}（环境内已有）/ {@code LocalRepoConfig}（本地路径）/
 *       {@code GithubRepoConfig}（远程 URL）/ 不配置。各态自带 {@code base_commit}。</li>
 *   <li>{@code SWE-agent/sweagent/run/batch_instances.py:131-138} —— 四态的**判别规则**只写在
 *       一处：空串 ⇒ 不用仓库；不含 {@code /} ⇒ 环境内已有目录；URL ⇒ 远程；其余 ⇒ 本地路径。
 *       用一个字符串 + 判别规则表达四种情形，而不是让模型在描述里自由发挥。</li>
 * </ul>
 * 与参考实现的**有意差异**：它只为 GitHub 单独建类（因为自带 {@code GITHUB_TOKEN} 注入链），
 * 我们没有任何凭据注入链，故 {@link RepoKind#REMOTE} 泛化为「任何 URL / git-ssh 地址」，
 * 覆盖 GitHub / Gitee / 自建 GitLab。凭据不进本结构——本结构会被渲染进提示词。</p>
 *
 * <p><b>为什么放在 {@code helloai-common}</b>：同一份声明至少两个域的读取方——拆解域（{@code planner}）
 * 要把「环境未声明」这条判据传给模型，执行域（{@code agent}）以后要把「仓库在哪」传给执行者。
 * 后者下标更大（更底层），依赖 {@code planner} 是反向依赖；故与 {@link DraftGateChecker}、
 * {@link com.helloai.common.constant.UncertaintyKind} 同置于最底层。</p>
 *
 * <p><b>只读不写、解析不抛异常</b>：本类不含任何持久化逻辑，只把 {@code task.context} 里那段
 * 自由 JSONB 转成结构化视图。脏数据（数字、嵌套错位、类型不符）一律降级为空集合——环境是
 * 「附加价值」，不该有否决权：解析失败只是少一段提示，不该让整次拆解失败。</p>
 *
 * <p><b>{@code declared} 为什么要单独一位</b>：「未声明执行环境」与「已声明但本任务不需要代码库」
 * 是两件完全不同的事——前者要求模型申报缺口，后者是明确的边界。用「字段是否都为空」判这两者
 * 会混成一件（{@code MEMORY} 判据：把「可挽回」与「不可挽回」混成一件事的同类事故）。</p>
 */
public record ExecutionEnv(boolean declared, Repo repo, List<String> runtime, List<DataSource> datasources) {

    /**
     * 仓库形态四态（对应参考实现的四个 {@code RepoConfig} 子类 + 不配置）。
     *
     * <p>判别顺序**不可调换**：先判 URL，再判「不含分隔符」，最后余下的才算本地路径。
     * 反过来的话 {@code E:/yhzx/1027/helloai} 这类含 {@code /} 的 Windows 路径会被误判。</p>
     */
    public enum RepoKind {
        /** 不使用代码库（明确声明「本任务不需要代码」）。 */
        NONE,
        /** 执行环境内已存在的目录（只给目录名，不含分隔符）——对应 {@code PreExistingRepoConfig}。 */
        PREEXISTING,
        /** 远程仓库 URL / git-ssh 地址——对应 {@code GithubRepoConfig}（见类注释的差异说明）。 */
        REMOTE,
        /** 本地文件系统路径——对应 {@code LocalRepoConfig}。 */
        LOCAL
    }

    /**
     * 仓库声明。
     *
     * @param kind    形态；{@code null} 视为 {@link RepoKind#NONE}
     * @param value   目录名 / 路径 / URL；{@code NONE} 时为 {@code null}
     * @param baseRef 环境基线（分支 / tag / commit）；缺省 {@code HEAD}，语义同参考实现的
     *                {@code base_commit}——「在哪个基线上跑」，与代码基线是两件事
     */
    public record Repo(RepoKind kind, String value, String baseRef) {

        public Repo {
            kind = kind == null ? RepoKind.NONE : kind;
            value = value == null || value.isBlank() ? null : value.trim();
            baseRef = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef.trim();
            // 声明了形态却没给值 = 该项未声明：降级 NONE，避免渲染出「远程 」这种半截话
            if (value == null) {
                kind = RepoKind.NONE;
            }
        }
    }

    /**
     * 数据源声明（**不是平台自己的库**——平台库来自 {@code application-*.yml}，与子任务要连的库无关）。
     *
     * @param type  类型：postgresql / mysql / oracle / redis / es / mongodb …
     * @param url   地址（host:port/db 或完整连接串）；可为 {@code null}
     * @param ready 是否已就绪；{@code null} = 未说明（**不当作已就绪**）
     */
    public record DataSource(String type, String url, Boolean ready) {

        public DataSource {
            type = type == null || type.isBlank() ? null : type.trim();
            url = url == null || url.isBlank() ? null : url.trim();
        }
    }

    public ExecutionEnv {
        repo = repo == null ? new Repo(RepoKind.NONE, null, null) : repo;
        runtime = runtime == null ? List.of() : List.copyOf(runtime);
        datasources = datasources == null ? List.of() : List.copyOf(datasources);
    }

    /** 未声明（{@code task.context.executionEnv} 缺失 / 类型不对 / 空对象）。 */
    public static final ExecutionEnv EMPTY = new ExecutionEnv(false, null, null, null);

    // ── 解析 ────────────────────────────────────────────────────────────

    /**
     * 防御式解析 {@code task.context.executionEnv} 的 JSONB 值。
     *
     * @param raw JSONB 反序列化后的原始值（期望 {@code Map}，可为 {@code null}）
     * @return 解析结果；非 Map / 空 Map 回落 {@link #EMPTY}（{@code declared=false}），永不抛异常
     */
    public static ExecutionEnv parse(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return EMPTY;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> map.put(String.valueOf(k), v));
        return new ExecutionEnv(true,
                parseRepo(map.get(KEY_REPO)),
                asStringList(map.get(KEY_RUNTIME)),
                parseDataSources(map.get(KEY_DATASOURCES)));
    }

    /**
     * 仓库解析：既接受简写字符串（{@code "repo": "E:/a/b"}，形态由 {@link #classify} 判），
     * 也接受完整对象（{@code {"kind":"local","value":"…","baseRef":"dev"}}）。
     *
     * <p>显式 {@code kind} **优先于**判别：模型 / 人已经说了形态就不再猜。但显式 {@code kind}
     * 与值矛盾时不做纠正（例如 {@code kind=preexisting} 却给了带斜杠的路径）——那是声明方的
     * 意图问题，纠正会让「我明明写了本地路径」变成另一种形态，更难排查。</p>
     */
    private static Repo parseRepo(Object raw) {
        if (raw instanceof String s) {
            return s.isBlank() ? EMPTY.repo() : new Repo(classify(s), s, null);
        }
        if (!(raw instanceof Map<?, ?> m)) {
            return EMPTY.repo();
        }
        Object value = firstNonNull(m.get(KEY_REPO_VALUE), m.get("path"), m.get("url"), m.get("name"));
        String text = value instanceof String s ? s : null;
        Object baseRef = firstNonNull(m.get(KEY_REPO_BASE_REF), m.get("ref"), m.get("branch"), m.get("commit"));
        RepoKind explicit = parseKind(m.get("kind"));
        RepoKind kind = explicit != null ? explicit : classify(text);
        return new Repo(kind, text, baseRef instanceof String b ? b : null);
    }

    /**
     * 四态判别（判别规则照 {@code batch_instances.py:131-138}，位置与顺序相同）：
     * <pre>
     * 空            → NONE
     * URL / git@    → REMOTE
     * 不含分隔符     → PREEXISTING（执行环境内已存在的目录名）
     * 其余          → LOCAL
     * </pre>
     * 分隔符同时认 {@code /} 与 {@code \}：参考实现只判 {@code /}，在 Windows 本地路径
     * {@code E:\yhzx\helloai} 上会误判成「环境内已有目录」。
     */
    public static RepoKind classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return RepoKind.NONE;
        }
        String v = raw.trim();
        String lower = v.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("git@") || lower.startsWith("ssh://")
                || lower.startsWith("git://")) {
            return RepoKind.REMOTE;
        }
        if (v.indexOf('/') < 0 && v.indexOf('\\') < 0) {
            return RepoKind.PREEXISTING;
        }
        return RepoKind.LOCAL;
    }

    private static RepoKind parseKind(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return RepoKind.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 非法形态按「没说」处理，回落判别；丢弃等于把声明方的话吃掉
            return null;
        }
    }

    private static List<DataSource> parseDataSources(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<DataSource> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object type = firstNonNull(m.get("type"), m.get("kind"), m.get("engine"));
                Object url = firstNonNull(m.get("url"), m.get("address"), m.get("dsn"), m.get("host"));
                result.add(new DataSource(type instanceof String t ? t : null,
                        url instanceof String u ? u : null, asBoolean(m.get("ready"))));
            } else if (item instanceof String s && !s.isBlank()) {
                result.add(fromDsnString(s));
            }
        }
        return result;
    }

    /** 简写形式：{@code "postgresql://localhost:15432/helloai"} 取 scheme 当类型；否则整串当类型。 */
    private static DataSource fromDsnString(String raw) {
        String v = raw.trim();
        int idx = v.indexOf("://");
        if (idx > 0) {
            return new DataSource(v.substring(0, idx), v, null);
        }
        return new DataSource(v, null, null);
    }

    /** 布尔取值的宽容读取：{@code true} / {@code "true"} / {@code "yes"} / {@code "1"} 为真，其余 null / false。 */
    private static Boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase();
            if (v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("是")) {
                return Boolean.TRUE;
            }
            if (v.equals("false") || v.equals("no") || v.equals("0") || v.equals("否")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** 运行时列表：数组逐项取字符串；也接受逗号 / 顿号 / 分号分隔的单串（人手填的常见形态）。 */
    private static List<String> asStringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s.trim());
                }
            }
            return result;
        }
        if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，、;；]")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
        }
        return result;
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    /**
     * 渲染为提示词段落（Markdown 列表）。**未声明时返回空串**，由调用方决定兜底文案
     * （判定口径写在提示词第 12 条，不在本类——「判据唯一来源」）。
     */
    public String render() {
        if (!declared) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        switch (repo.kind()) {
            case NONE -> sb.append("- 代码仓库：**不需要**（本任务不依赖代码库）\n");
            case PREEXISTING -> sb.append("- 代码仓库：执行环境内已存在，目录名 `")
                    .append(repo.value()).append("`\n");
            case REMOTE -> sb.append("- 代码仓库：远程 `").append(repo.value())
                    .append("`，基线 `").append(repo.baseRef()).append("`\n");
            case LOCAL -> sb.append("- 代码仓库：本地路径 `").append(repo.value())
                    .append("`，基线 `").append(repo.baseRef()).append("`\n");
        }
        if (!runtime.isEmpty()) {
            sb.append("- 运行时：").append(String.join("、", runtime)).append('\n');
        }
        if (!datasources.isEmpty()) {
            sb.append("- 数据源（子任务要连的库，不是平台自己的库）：\n");
            for (DataSource d : datasources) {
                sb.append("  - ").append(renderDataSource(d)).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 单条数据源：{@code postgresql — localhost:15432/helloai（已就绪）}；未说明就绪状态时不加括号。 */
    private static String renderDataSource(DataSource d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.type() == null ? "（类型未声明）" : d.type());
        if (d.url() != null) {
            sb.append(" — ").append(d.url());
        }
        if (Boolean.TRUE.equals(d.ready())) {
            sb.append("（已就绪）");
        } else if (Boolean.FALSE.equals(d.ready())) {
            sb.append("（**未就绪**）");
        }
        return sb.toString();
    }

    // ── 键名 ────────────────────────────────────────────────────────────

    /**
     * {@code task.context} 上的键：执行环境声明。
     *
     * <p><b>为什么键名放在这里而不是 {@code TaskContextKeys}</b>：该键的写入方在 {@code task} 域
     * （派生任务继承父任务环境，{@code TaskDerivationServiceImpl}），读取方在 {@code planner} 域。
     * 常量若留在 {@code planner.policy}，{@code task → planner} 就是反向依赖（下标 2 → 0）。
     * 键名属纯数据契约，按 §7.2 应落最底层的 {@code common}；{@code TaskContextKeys} 里那份
     * 转发本常量，既有引用点不用改。</p>
     */
    public static final String CONTEXT_KEY = "executionEnv";

    /** 顶层键：仓库。 */
    public static final String KEY_REPO = "repo";

    /** 顶层键：运行时列表。 */
    public static final String KEY_RUNTIME = "runtime";

    /** 顶层键：数据源列表。 */
    public static final String KEY_DATASOURCES = "datasources";

    /** 仓库对象的键：路径 / URL。 */
    public static final String KEY_REPO_VALUE = "value";

    /** 仓库对象的键：环境基线（分支 / tag / commit）。 */
    public static final String KEY_REPO_BASE_REF = "baseRef";
}
