package com.helloai.core.planner.policy;

import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `TD-13 S4`：拆解批次门 1（结构）/ 门 2（单条质量）的纯函数检查器。
 *
 * <p><b>只判定，不落库、不抛异常</b>：调用方（{@code PlannerDecomposeAsyncServiceImpl}）
 * 依据 {@link Result#hasBlocking()} 决定阻断，并把 {@link Finding} 落到
 * {@code task.context.gateFindings} / 草案 {@code context.gateFindings} 与时间线。</p>
 *
 * <p><b>门 2 只收机械可判项</b>：中文「空泛」没有可靠正则，强写只会把短而准的草案判空。
 * 因此这里只做字段空白、字符下限、以及「交付物里的显式专名完全不出现
 * 在验收里」这三类零歧义判定。`G2_KIND_FORM_CONFLICT` 不在此重复实现——它已由 TD-5 的
 * {@code AcceptanceKindValidator} 承担（显式声明不匹配 fail-close，缺失声明 WARN），
 * 再写一份更弱的同义规则只会把既有阻断降级。</p>
 *
 * <p><b>「显式专名」只认引号内术语</b>：曾把交付物里的 ASCII 词元（`docs`、
 * `contract-inspection-ledger`、`migrations`）也当专名，与纯中文验收做字面匹配。
 * 而「中文验收 + 英文路径交付物」是本项目主流写法，2026-11-07 真机 2/2 命中全为误报。
 * 英文文件名不是验收里必须复述的判据，按字面比会把主流合法形态判成缺陷。</p>
 * <p><b>`G2_NOUN_MISMATCH` 已退出 findings（`s4-3`）</b>：即便收窄到「只认引号内术语」，
 * 用库里 119 条真实草案重算仍有 21% 误报——交付物是《验收条件冲突说明与人工裁定请求》、
 * 验收写「文档须满足 3 项可数结构断言」，语义完全对应却不复述文档名。中文文本没有可靠的
 * 「同一个东西」判据，强判只会污染 `S5` 灰度样本。现仅保留 `debug` 日志供观察，不入
 * findings、不产生 timeline 事件。</p>
 *
 * <p><b>block 权只给零歧义规则（`s4-4`）</b>：字段空、依赖图结构（环 / 越界 / 自依赖）
 * 判的是「有没有」与「图是否闭合」，结论与样本分布无关；而长度阈值是某一时点的样本快照，
 * 分布一变就会误拦合法产出——`deliverable` 的 8 字符阈值曾拦住「三行状态回执」这类合法
 * 验证/夹具草案，且 `block` 的代价是整批拆解失败、重试必复现。故 `deliverable` 只判空白、
 * 不判长度；规则级别在调用点显式声明，启发式规则不得声明 {@code block}。</p>
 *
 * <p><b>阈值型规则的 block 条件是量化的（`s4-5`）</b>：`s4-4` 的绝对禁令与 `G2_LEN_MIN`
 * 残留 block 自相矛盾。改为可校验条件 {@link #BLOCK_THRESHOLD_MARGIN}——
 * 「阈值 ≤ 存量最小值 / 3」才可 block，且每次改阈值须重跑存量回放存证。当前仅
 * `G2_LEN_MIN`（acceptance）满足（39 / 10 = 3.9 倍），故它保留阻断权。</p>
 */
@Slf4j
public final class PlanBatchGateChecker {

    /** 规则版本：随判据口径变更而递增，历史 findings 可据此归因。 */
    public static final String RULE_VERSION = "s4-5";

    /**
     * 阈值型规则持有 {@code block} 权的余量条件（`s4-5`）：**阈值 ≤ 存量分布最小值 / 本倍数**。
     *
     * <p>把「阈值型一律不得 block」的绝对禁令换成可校验条件——纯禁令会把 acceptance 下限一并
     * 废掉，而该下限确实承担「挡 3 字符空话」的职责。判据依据：余量 ≥ 3 倍时，分布小幅漂移
     * 不会把合法产出推到阈值以下；不足 3 倍则说明阈值贴着当前分布，随时会误拦。</p>
     *
     * <p>历史两例均由本条件正确判定：`deliverable` 5 / 8 = 0.6 倍 → 必须降级（阈值已整体删除）；
     * `acceptance` 39 / 10 = 3.9 倍 → 可保留 {@code block}。**改阈值前必须重跑存量回放并核对
     * 本倍数**，否则该条件又会退化成「某一时点的快照」。</p>
     */
    public static final int BLOCK_THRESHOLD_MARGIN = 3;

    /**
     * acceptance 字符下限：唯一持有 {@code block} 权的阈值型规则。
     *
     * <p>2026-11-07 存量 119 条回放：最短 39 字符 ⇒ 39 / 10 = 3.9 倍 ≥ {@link #BLOCK_THRESHOLD_MARGIN}，
     * 满足余量条件、零误拦，故保留阻断。改小阈值会放宽空话准入，改大阈值会侵蚀余量——两者都须重跑回放。</p>
     */
    public static final int ACCEPTANCE_MIN_LENGTH = 10;

    public static final String G1_DEP_OUT_OF_RANGE = "G1_DEP_OUT_OF_RANGE";
    public static final String G1_SELF_DEP = "G1_SELF_DEP";
    public static final String G1_CYCLE = "G1_CYCLE";
    public static final String G2_BLANK = "G2_BLANK";
    public static final String G2_LEN_MIN = "G2_LEN_MIN";
    /** 已退出 findings（见类注释）；保留标识用于 debug 日志归因。 */
    public static final String G2_NOUN_MISMATCH = "G2_NOUN_MISMATCH";
    /**
     * `TD-5` 承担的「验收写法 ↔ `acceptanceKind` 矛盾」。
     *
     * <p>门 2 **不重复实现**该规则（再写一份更弱的同义规则会把既有 fail-close 降级）；
     * 此处仅供 `auditGateBlocked` 留痕时归因，使三处阻断共用同一套 `ruleId` 词表。</p>
     */
    public static final String G2_KIND_FORM_CONFLICT = "G2_KIND_FORM_CONFLICT";

    public static final String GATE_STRUCTURE = "gate1_structure";
    public static final String GATE_QUALITY = "gate2_quality";

    public static final String SEVERITY_BLOCK = "block";
    /** schema 词表保留：门 1/2 目前无规则产出 warn，`S5` 门 3a 起使用。 */
    public static final String SEVERITY_WARN = "warn";

    /** 处置类别：A=整条重修 / B=结构化修补 / C=澄清或显式假设（`S6` 重修策略使用）。 */
    public static final String FIX_A = "A";
    public static final String FIX_B = "B";

    /** 处置状态（计划 §收敛守卫）：新建 findings 一律 `open`，由 S6 收敛守卫流转。 */
    public static final String DISPOSITION_OPEN = "open";
    public static final String DISPOSITION_FIXED = "fixed";
    public static final String DISPOSITION_ACCEPTED = "accepted";
    public static final String DISPOSITION_ESCALATED = "escalated";

    /** 引号内术语：捕获组排除全部引号字符，避免 `《「外部失败回执」口径依据卡》` 抽成带前导 `「` 的残串。 */
    private static final Pattern QUOTED_TERM =
            Pattern.compile("[《「【“\"]([^《》「」【】“”\"]{2,20})[》」】”\"]");

    private PlanBatchGateChecker() {
    }

    /**
     * 一条门禁结论。字段与计划 §`findings` schema 对齐：
     * {@code gate / ruleId / severity / draftRef / subject / evidence / fixClass / disposition / ruleVersion}。
     *
     * @param draftRef    1-based 草案序号；批次级问题（如环）为 null
     * @param evidence    人可读的判定证据（含阈值与实际值/实际引用）
     * @param disposition 处置状态：{@code open} / {@code fixed} / {@code accepted} / {@code escalated}；
     *                    生产方只产出 {@code open}，流转由 S6 收敛守卫负责
     */
    public record Finding(String gate, String ruleId, String severity, Integer draftRef,
                          String subject, String evidence, String fixClass, String disposition,
                          String ruleVersion) {

        public Finding {
            if (disposition == null || disposition.isBlank()) {
                disposition = DISPOSITION_OPEN;
            }
        }
    }

    /**
     * 检查结果：按 {@code severity} 分区，**阻断只看 {@code block}**。
     *
     * <p>规则在调用点显式声明级别（见 {@link #finding}）：`block` 权只给零歧义确定性规则
     * （字段空、依赖图结构）；阈值型 / 文本启发式规则只能声明 {@code warn}，否则一条经验
     * 数字就能否决整批合法产出，而重试永远得到同样的结果。</p>
     */
    public record Result(List<Finding> findings) {

        public Result {
            findings = List.copyOf(findings);
        }

        public List<Finding> blocking() {
            return findings.stream().filter(f -> SEVERITY_BLOCK.equals(f.severity())).toList();
        }

        public List<Finding> warnings() {
            return findings.stream().filter(f -> SEVERITY_WARN.equals(f.severity())).toList();
        }

        public boolean hasBlocking() {
            return !blocking().isEmpty();
        }

        /** 阻断用异常消息：逐条拼 {@code 第 N 条 evidence}，保留既有错误信息可读性。 */
        public String blockMessage() {
            List<Finding> blocking = blocking();
            StringBuilder sb = new StringBuilder("拆解结果未通过门 1/2：");
            for (int i = 0; i < blocking.size(); i++) {
                Finding f = blocking.get(i);
                if (i > 0) {
                    sb.append("；");
                }
                sb.append(f.draftRef() == null ? "" : "第 " + f.draftRef() + " 条")
                        .append(f.evidence()).append("(").append(f.ruleId()).append(")");
            }
            return sb.toString();
        }
    }

    /** 门 1 + 门 2 全量检查。 */
    public static Result check(List<PlanDraftItem> items) {
        List<Finding> findings = new ArrayList<>();
        checkDependencyRules(items, findings);
        checkFieldRules(items, findings);
        logNounMismatch(items);
        return new Result(findings);
    }

    // ── 门 1：依赖结构 ─────────────────────────────────────────────

    private static void checkDependencyRules(List<PlanDraftItem> items, List<Finding> findings) {
        int n = items.size();
        for (int i = 0; i < n; i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null) {
                continue;
            }
            for (Integer dep : deps) {
                if (dep == null || dep < 1 || dep > n) {
                    findings.add(finding(GATE_STRUCTURE, G1_DEP_OUT_OF_RANGE, SEVERITY_BLOCK, i + 1,
                            "dependsOn", "依赖序号非法: " + dep + "（合法范围 1~" + n + "）", FIX_A));
                    continue;
                }
                if (dep == i + 1) {
                    findings.add(finding(GATE_STRUCTURE, G1_SELF_DEP, SEVERITY_BLOCK, i + 1,
                            "dependsOn", "不得依赖自身", FIX_A));
                }
            }
        }
        if (hasCycle(items)) {
            findings.add(finding(GATE_STRUCTURE, G1_CYCLE, SEVERITY_BLOCK, null, "dependsOn",
                    "存在循环依赖，整批拒绝；请重新触发拆解", FIX_A));
        }
    }

    /** Kahn 拓扑排序环检测（入度法：能全部出队 = 无环）。越界序号先被门 1 记录，此处跳过。 */
    private static boolean hasCycle(List<PlanDraftItem> items) {
        int n = items.size();
        int[] inDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null) {
                continue;
            }
            for (Integer dep : deps) {
                if (dep == null || dep < 1 || dep > n || dep == i + 1) {
                    continue;
                }
                adjacency.get(dep - 1).add(i);
                inDegree[i]++;
            }
        }
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                queue.add(i);
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            visited++;
            for (int next : adjacency.get(node)) {
                if (--inDegree[next] == 0) {
                    queue.add(next);
                }
            }
        }
        return visited < n;
    }

    // ── 门 2：单条机械可判项 ───────────────────────────────────────

    private static void checkFieldRules(List<PlanDraftItem> items, List<Finding> findings) {
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            String missing = firstBlankField(item);
            if (missing != null) {
                findings.add(finding(GATE_QUALITY, G2_BLANK, SEVERITY_BLOCK, i + 1,
                        missing, "缺少 " + missing + " 字段", FIX_A));
                continue;
            }
            String acceptance = item.getAcceptance().trim();
            // deliverable 不设长度下限：它回答「交付什么」，天然可以极短（`三行状态回执` 这类
            // 验证/夹具任务在生产数据里是主流形态）；空话风险由 acceptance 承担。
            if (acceptance.length() < ACCEPTANCE_MIN_LENGTH) {
                findings.add(finding(GATE_QUALITY, G2_LEN_MIN, SEVERITY_BLOCK, i + 1,
                        "acceptance", "验收判据过短（acceptance=" + acceptance.length()
                                + " 字符 < " + ACCEPTANCE_MIN_LENGTH
                                + "）——空话型子任务，落库前拒绝", FIX_A));
            }
        }
    }

    private static String firstBlankField(PlanDraftItem item) {
        if (isBlank(item.getTitle())) {
            return "title";
        }
        if (isBlank(item.getContent())) {
            return "content";
        }
        if (isBlank(item.getDeliverable())) {
            return "deliverable";
        }
        if (isBlank(item.getAcceptance())) {
            return "acceptance";
        }
        return null;
    }

    /**
     * 交付物与验收互不含关键名词：**只出 debug 日志，不入 findings**（见类注释 `s4-3`）。
     * 只取引号内术语；交付物里一个专名都抽不到时不判定。
     */
    private static void logNounMismatch(List<PlanDraftItem> items) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            PlanDraftItem item = items.get(i);
            if (isBlank(item.getDeliverable()) || isBlank(item.getAcceptance())) {
                continue;
            }
            Set<String> terms = extractTerms(item.getDeliverable());
            if (terms.isEmpty()) {
                continue;
            }
            String acceptance = item.getAcceptance().toLowerCase(Locale.ROOT);
            List<String> missing = terms.stream()
                    .filter(term -> !acceptance.contains(term.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missing.isEmpty()) {
                log.debug("[{}] 草案 {} 交付物专名未出现在验收判据中（仅供参考，不入 findings）: {}",
                        G2_NOUN_MISMATCH, i + 1, missing);
            }
        }
    }

    private static Set<String> extractTerms(String deliverable) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher quoted = QUOTED_TERM.matcher(deliverable);
        while (quoted.find()) {
            terms.add(quoted.group(1).trim());
        }
        return terms;
    }

    // ── 构造 ───────────────────────────────────────────────────────

    /**
     * 构造一条新 finding：{@code disposition} 恒为 {@link #DISPOSITION_OPEN}、{@code ruleVersion}
     * 恒取 {@link #RULE_VERSION}。
     *
     * <p><b>对外可见是刻意的</b>：编排层（{@code PlannerDecomposeAsyncServiceImpl}）在
     * {@code TD-5} 的阻断点也要产出同 schema 的 finding，若允许自行 {@code new Finding(...)}，
     * 就要在调用点手填 {@code disposition} 与 {@code ruleVersion}——前者是 `S6` 收敛守卫的判据、
     * 后者是灰度统计的分组键，填错不会报错、只会让统计与收敛静默失真。统一从这里出。</p>
     */
    public static Finding finding(String gate, String ruleId, String severity, Integer draftRef,
                                  String subject, String evidence, String fixClass) {
        return new Finding(gate, ruleId, severity, draftRef, subject, evidence,
                fixClass, DISPOSITION_OPEN, RULE_VERSION);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
