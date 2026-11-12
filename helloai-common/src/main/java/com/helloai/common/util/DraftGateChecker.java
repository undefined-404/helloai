package com.helloai.common.util;

import com.helloai.common.constant.AcceptanceKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 草案质量门禁的**唯一判据来源**（门 1 依赖结构 / 门 2 单条机械可判项）。
 *
 * <p><b>为什么在 {@code common}</b>：拆解域（{@code planner}）与子任务域（{@code task}）都要跑同一套
 * 判据——拆解/展开后整批判定、草案编辑后单条复检、确认前放行判定。判据放在任一域都会让另一域
 * 反向依赖（{@code planner(0) > task(2)}），因此与 {@link AcceptanceKindValidator} 同置于最底层。</p>
 *
 * <p><b>2026-11-08 口径变更（本类相对 {@code PlanBatchGateChecker} 的实质差异）</b></p>
 * <ol>
 *   <li><b>入参从「LLM 输出序号」换成「已落库草案」</b>（2026-11-08）：旧实现判定发生在落库之前，
 *       只能用 1-based 序号定位；而序号只在单次 LLM 输出内有意义，一旦跨入口（展开新增的组、被编辑过
 *       的草案）必然错位，findings 也就无法按草案过滤。</li>
 *   <li><b>{@code draftRef} 对外口径从 {@code sub_task.id} 换成稳定 code</b>（{@code B2}，2026-11-09）：
 *       id 是行身份，重排/重建会变；code（{@code yyyyMMdd+seq}）是逻辑位置身份，重生成后不变。
 *       判定**内部键仍是 id**（依赖构图/环检测的唯一性依据，恒非 null、不失效）；findings 暴露用
 *       {@link DraftSnapshot#ref()}——code 优先，存量 V147 前无 code 的行回落 {@code String.valueOf(id)}。
 *       存量行回落是刻意的 fail-closed：宁可显示旧身份也不跳过判定。</li>
 *   <li><b>{@code G1_DEP_OUT_OF_RANGE} 语义随之改为「依赖指向不在本批次内的草案」</b>
 *       （悬空引用），不再判「序号越界」。序号越界是解析期问题，由调用方在「序号 → id」映射时兜底。</li>
 *   <li><b>门 2 接入 {@code acceptanceKind} 写法判定</b>：旧实现把它留在调用方以异常形式抛出，
 *       导致「一条验收写法不匹配」连带整批草案作废；现在它是 findings 的一条，只影响该草案。</li>
 * </ol>
 *
 * <p><b>只判定，不落库、不抛异常、不依赖框架</b>：结论由调用方按 {@code severity} 决定处置——
 * 「生成类」操作（拆解）把它当产出的一部分，草案照常落库、带诊断不可确认；「改造类」操作
 * （展开/重生成/人工编辑）据此回滚，用户原来的草案不受影响。</p>
 *
 * <p><b>block 权只给「结论与样本分布无关」的规则</b>：字段空不空、依赖图闭不闭合，判的是
 * 「有没有」与「图结构」；文本启发式（中文名词包含、覆盖矩阵）依据是某一时点的样本快照，
 * 分布一变就误拦合法产出。阈值型规则持有 block 权的条件是量化的——
 * 阈值 ≤ 存量分布最小值 / {@link #BLOCK_THRESHOLD_MARGIN}（见该类常量注释）。</p>
 */
public final class DraftGateChecker {

    /**
     * 规则版本：随判据口径变更而递增，历史 findings 可据此归因。
     * `s5-0` = 落库后 id 口径；`s5-1` = draftRef 换稳定 code；`s5-2` = 新增 {@link #G3_DEPTH_LIMIT}；
     * `s5-3` = 新增语义门 {@link #G4_UNSATISFIABLE_CONTRACT}（判据实现在
     * {@code core.planner.policy.SemanticContractGate}，不在本类）。
     */
    public static final String RULE_VERSION = "s5-3";

    /**
     * 阈值型规则持有 {@code block} 权的余量条件：**阈值 ≤ 存量分布最小值 / 本倍数**。
     *
     * <p>历史两例均由本条件正确判定：{@code deliverable} 5 / 8 = 0.6 倍 → 阈值整体删除；
     * {@code acceptance} 39 / 10 = 3.9 倍 → 保留 {@code block}。**改阈值前必须重跑存量回放
     * 并核对本倍数**，否则该条件又会退化成「某一时点的快照」。</p>
     */
    public static final int BLOCK_THRESHOLD_MARGIN = 3;

    /** acceptance 字符下限：唯一持有 {@code block} 权的阈值型规则（存量 39 字符 / 3.9 倍）。 */
    public static final int ACCEPTANCE_MIN_LENGTH = 10;

    public static final String G1_DEP_OUT_OF_RANGE = "G1_DEP_OUT_OF_RANGE";
    public static final String G1_SELF_DEP = "G1_SELF_DEP";
    public static final String G1_CYCLE = "G1_CYCLE";
    public static final String G2_BLANK = "G2_BLANK";
    public static final String G2_LEN_MIN = "G2_LEN_MIN";
    /** 验收写法与声明 {@code acceptanceKind} 矛盾（判据实现在 {@link AcceptanceKindValidator}）。 */
    public static final String G2_KIND_FORM_CONFLICT = "G2_KIND_FORM_CONFLICT";
    /**
     * 草案层级超过该 {@code task_archetype} 的上限（设计 §7 分档）。
     *
     * <p><b>为什么保留这条判据</b>：派生入口按 {@code task.depth} 已经硬拒超深派生（ADR-008 §19.7），
     * 但确认前放行走的是**另一条路径**——它只看库里的草案。历史多段 code 的存量草案（V147 之前
     * 展开留下的）仍可能超深，手工改库也无法拦住。缺了它，确认会放行一个违反深度契约的批次。</p>
     */
    public static final String G3_DEPTH_LIMIT = "G3_DEPTH_LIMIT";

    /**
     * 验收契约**不可满足**：某条草案的 {@code acceptance} 要求的事实，被其**上游任一草案的
     * {@code constraints} 明令禁止**（设计 §6.2）。
     *
     * <p><b>为什么这条判据不在本类实现</b>：它要读「验收要求」与「上游约束」两层语义，规则只能
     * 定向、覆盖不全；完整判定依赖一次模型调用。而 {@code DraftGateChecker} 在 {@code common}
     * 模块、零外部依赖（不许调 LLM），故判据落在 {@code core.planner.policy.SemanticContractGate}。
     * 常量放这里是为了让**所有 ruleId 只有一个登记处**，前端按 ruleId 分类展示时不至于两头找。</p>
     */
    public static final String G4_UNSATISFIABLE_CONTRACT = "G4_UNSATISFIABLE_CONTRACT";

    public static final String GATE_STRUCTURE = "gate1_structure";
    public static final String GATE_QUALITY = "gate2_quality";
    /**
     * 语义门（`G4_*`）的 gate 标识：与规则门的 {@code gate1/gate2} 并列，供前端分区展示
     * 「结构性错误 / 写法问题 / 语义冲突」三类结论。
     */
    public static final String GATE_SEMANTIC = "gate3_semantic";

    public static final String SEVERITY_BLOCK = "block";
    public static final String SEVERITY_WARN = "warn";

    /** 处置类别：A=整条重修 / B=结构化修补 / C=澄清或显式假设（S6 重修策略使用）。 */
    public static final String FIX_A = "A";
    public static final String FIX_B = "B";

    /** 处置状态：新建 findings 一律 `open`，由收敛守卫流转。 */
    public static final String DISPOSITION_OPEN = "open";

    private DraftGateChecker() {
    }

    /**
     * 判定输入：一条**已落库**草案的最小投影。
     *
     * <p>刻意为中性结构而非某个域的视图：两域各自适配，避免判据绑定到某一域的 DTO。</p>
     *
     * @param id              草案行 id——**判定内部键**：依赖构图 / 自引用 / 环检测的唯一性依据。
     *                        恒非 null（null 行不参与判定，见各规则的跳过分支）。
     * @param code            草案稳定身份 code（`B2` 后 findings 对外暴露用）；{@code null}（V147 前
     *                        存量行，AD-5a 存量迁入回填）时由 {@link #ref()} 回落 {@code String.valueOf(id)}。
     * @param declaredKindRaw <b>模型/用户显式声明</b>的 {@code acceptanceKind} 原文（合法枚举才带值，
     *                        未声明为 {@code null}）。<b>不能传落库后的枚举</b>：{@code AcceptanceKind.parse}
     *                        对 null/非法值回落 {@code AUTOMATED}，用它判「是否显式声明」会把这当成
     *                        「显式 AUTOMATED」，普通文本立刻被判写法不匹配（`LOG-20261024-040` 踩过）
     */
    public record DraftSnapshot(Long id, String code, String title, String content, String deliverable,
                               String acceptance, String declaredKindRaw, List<Long> dependsOn) {

        public DraftSnapshot {
            dependsOn = dependsOn == null ? List.of()
                    : dependsOn.stream().filter(Objects::nonNull).toList();
        }

        /** findings 定位身份：code 优先（稳定位置身份），存量无 code 回落 id 字符串（fail-closed）。 */
        public String ref() {
            return code != null && !code.isBlank() ? code : String.valueOf(id);
        }
    }

    /**
     * 一条门禁结论。字段与计划 §`findings` schema 对齐：
     * {@code gate / ruleId / severity / draftRef / subject / evidence / fixClass / disposition / ruleVersion}。
     *
     * @param draftRef 命中草案的**稳定 code**（{@code B2}，无 code 存量行回落 id 字符串）；
     *                 批次级问题（如环）为 {@code null}
     */
    public record Finding(String gate, String ruleId, String severity, String draftRef,
                          String subject, String evidence, String fixClass, String disposition,
                          String ruleVersion) {

        public Finding {
            if (disposition == null || disposition.isBlank()) {
                disposition = DISPOSITION_OPEN;
            }
        }
    }

    /** 检查结果：按 {@code severity} 分区，**阻断只看 {@code block}**。 */
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

        public boolean isEmpty() {
            return findings.isEmpty();
        }

        /** 按草案身份（code，无 code 回落 id 字符串）分组；{@code null} 键 = 批次级问题，调用方决定挂到哪条。 */
        public Map<String, List<Finding>> byDraft() {
            Map<String, List<Finding>> grouped = new LinkedHashMap<>();
            for (Finding f : findings) {
                grouped.computeIfAbsent(f.draftRef(), k -> new ArrayList<>()).add(f);
            }
            return grouped;
        }
    }

    /**
     * 不做深度判定（{@code maxDepth} 取本值）。
     *
     * <p>调用方拿不到 {@code task_archetype} 时用它——判据宁可少判一条，也不该按错误的档位误拦。
     * 已知 {@code archetype} 的路径必须传真实上限。</p>
     */
    public static final int NO_DEPTH_LIMIT = -1;

    /** 门 1 + 门 2 全量判定，**不判深度**（等价于 {@code check(drafts, NO_DEPTH_LIMIT)}）。 */
    public static Result check(List<DraftSnapshot> drafts) {
        return check(drafts, NO_DEPTH_LIMIT);
    }

    /**
     * 门 1 + 门 2 全量判定，并按 {@code maxDepth} 判 {@link #G3_DEPTH_LIMIT}。
     *
     * @param maxDepth 该任务 {@code task_archetype} 对应的展开上限（设计 §7）；{@link #NO_DEPTH_LIMIT} = 不判
     */
    public static Result check(List<DraftSnapshot> drafts, int maxDepth) {
        List<Finding> findings = new ArrayList<>();
        if (drafts == null || drafts.isEmpty()) {
            return new Result(findings);
        }
        checkDependencyRules(drafts, findings);
        checkDepthRule(drafts, findings, maxDepth);
        checkFieldRules(drafts, findings);
        return new Result(findings);
    }

    /**
     * 只跑门 2（字段空白 / 长度 / kind 写法）：供**单条草案编辑后**复检。
     *
     * <p>单条编辑不可能改变批内依赖图（{@code dependsOn} 不在任何编辑白名单里），故无需门 1。</p>
     */
    public static Result checkFields(List<DraftSnapshot> drafts) {
        List<Finding> findings = new ArrayList<>();
        checkFieldRules(drafts == null ? List.of() : drafts, findings);
        return new Result(findings);
    }

    /** 单条便捷入口。 */
    public static Result checkOne(DraftSnapshot draft) {
        return checkFields(List.of(draft));
    }

    // ── 门 1：依赖结构 ─────────────────────────────────────────────

    private static void checkDependencyRules(List<DraftSnapshot> drafts, List<Finding> findings) {
        Set<Long> known = new HashSet<>();
        for (DraftSnapshot d : drafts) {
            if (d.id() != null) {
                known.add(d.id());
            }
        }
        for (DraftSnapshot d : drafts) {
            if (d.id() == null) {
                continue;
            }
            for (Long dep : d.dependsOn()) {
                if (dep == null) {
                    continue;
                }
                if (dep.equals(d.id())) {
                    findings.add(finding(GATE_STRUCTURE, G1_SELF_DEP, SEVERITY_BLOCK, d.ref(),
                            "dependsOn", "不得依赖自身", FIX_A));
                    continue;
                }
                if (!known.contains(dep)) {
                    findings.add(finding(GATE_STRUCTURE, G1_DEP_OUT_OF_RANGE, SEVERITY_BLOCK, d.ref(),
                            "dependsOn", "依赖指向不在本批次内的草案: " + dep, FIX_A));
                }
            }
        }
        if (hasCycle(drafts)) {
            findings.add(finding(GATE_STRUCTURE, G1_CYCLE, SEVERITY_BLOCK, null, "dependsOn",
                    "存在循环依赖，需拆开环上的草案依赖边", FIX_A));
        }
    }

    /** Kahn 拓扑排序环检测（入度法：能全部出队 = 无环）。悬空依赖不参与构图（已单独记 finding）。 */
    private static boolean hasCycle(List<DraftSnapshot> drafts) {
        int n = drafts.size();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (drafts.get(i).id() != null) {
                index.put(drafts.get(i).id(), i);
            }
        }
        int[] inDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (Long dep : drafts.get(i).dependsOn()) {
                Integer from = dep == null ? null : index.get(dep);
                if (from == null || from == i) {
                    continue;
                }
                adjacency.get(from).add(i);
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

    // ── 门 1 附：层级深度（按 archetype 分档）──────────────────────

    /**
     * 层级深度判定：{@code depth > maxDepth} 即 block。
     *
     * <p><b>无 code 的存量行跳过</b>（{@code depthOf} 返回 -1）：V147 之前的草案没有 code，
     * 层级信息不存在，按「不判」处理是 fail-open。**不能**用 {@code 0} 兜底——那会把
     * 「不知道」当成「根层」，在 {@code DECIDE}（上限 1）这类档位上给出错误结论。</p>
     */
    private static void checkDepthRule(List<DraftSnapshot> drafts, List<Finding> findings, int maxDepth) {
        if (maxDepth < 0) {
            return;
        }
        for (DraftSnapshot d : drafts) {
            if (d.id() == null) {
                continue;
            }
            int depth = depthOf(d.code());
            if (depth > maxDepth) {
                findings.add(finding(GATE_STRUCTURE, G3_DEPTH_LIMIT, SEVERITY_BLOCK, d.ref(),
                        "code", "草案层级 depth=" + depth + " 超过上限 " + maxDepth
                                + "（更细的拆分应落在上一层的内容组织里，而不是继续下钻）", FIX_A));
            }
        }
    }

    /**
     * 由稳定 code 推导层级：{@code 段数 - 1}（{@code 202611090001} = 0，{@code 202611090001-003} = 1）。
     *
     * <p>与 {@code DraftCode#depth()} 同义——那边用 {@code split("-").size() - 1}，这里数字符，
     * 对合法 code（无连续/首尾连字符，由 {@code DraftCode} 构造时保证）完全等价。
     * 判据放在 {@code common} 不能依赖拆解域的值对象，故就地重算这一行。</p>
     *
     * @return 层级；{@code code} 为空（V147 前存量行）返回 {@code -1}
     */
    public static int depthOf(String code) {
        if (code == null || code.isBlank()) {
            return -1;
        }
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '-') {
                depth++;
            }
        }
        return depth;
    }

    // ── 门 2：单条机械可判项 ───────────────────────────────────────

    private static void checkFieldRules(List<DraftSnapshot> drafts, List<Finding> findings) {
        for (DraftSnapshot d : drafts) {
            if (d.id() == null) {
                continue;
            }
            String missing = firstBlankField(d);
            if (missing != null) {
                findings.add(finding(GATE_QUALITY, G2_BLANK, SEVERITY_BLOCK, d.ref(),
                        missing, "缺少 " + missing + " 字段", FIX_A));
                continue;
            }
            String acceptance = d.acceptance().trim();
            // deliverable 不设长度下限：它回答「交付什么」，天然可以极短（`三行状态回执` 这类
            // 验证/夹具任务在生产数据里是主流形态）；空话风险由 acceptance 承担。
            if (acceptance.length() < ACCEPTANCE_MIN_LENGTH) {
                findings.add(finding(GATE_QUALITY, G2_LEN_MIN, SEVERITY_BLOCK, d.ref(),
                        "acceptance", "验收判据过短（acceptance=" + acceptance.length()
                                + " 字符 < " + ACCEPTANCE_MIN_LENGTH + "）——空话型子任务", FIX_A));
            }
            AcceptanceKindValidator.Result kindCheck =
                    AcceptanceKindValidator.validate(parseDeclaredKind(d.declaredKindRaw()), acceptance);
            if (kindCheck instanceof AcceptanceKindValidator.Result.Fail fail) {
                findings.add(finding(GATE_QUALITY, G2_KIND_FORM_CONFLICT, SEVERITY_BLOCK, d.ref(),
                        "acceptance", fail.reason(), FIX_A));
            } else if (kindCheck instanceof AcceptanceKindValidator.Result.Warn warn) {
                findings.add(finding(GATE_QUALITY, G2_KIND_FORM_CONFLICT, SEVERITY_WARN, d.ref(),
                        "acceptance", warn.hint(), FIX_B));
            }
        }
    }

    /**
     * 显式声明的 kind 解析：只有「非空且是合法枚举」才算显式；缺失/非法值一律返回 {@code null}
     * （= 未声明，走 WARN 放行，不误伤存量产物）。
     */
    private static AcceptanceKind parseDeclaredKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AcceptanceKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstBlankField(DraftSnapshot d) {
        if (isBlank(d.title())) {
            return "title";
        }
        if (isBlank(d.content())) {
            return "content";
        }
        if (isBlank(d.deliverable())) {
            return "deliverable";
        }
        if (isBlank(d.acceptance())) {
            return "acceptance";
        }
        return null;
    }

    // ── payload 形状（写与清同源）───────────────────────────────────

    /**
     * 一条草案的 findings payload：{@code {findings, ruleVersion, checkedAt}}。
     *
     * <p><b>为什么形状必须由本类出</b>：写点和清点曾分散在多个调用方，新增写点的人只照抄
     * 「写」的那一半，于是 `2026-10-24` 修好的「只写不清」在 `2026-11-07` 的第三个写点原样复发。
     * 现在「有结论」与「已清零」是同一份结构的两种取值，消费方只看 {@code findings} 是否为空。</p>
     */
    public static Map<String, Object> payload(List<Finding> findings, String checkedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", findings == null ? List.of() : List.copyOf(findings));
        payload.put("ruleVersion", RULE_VERSION);
        payload.put("checkedAt", checkedAt);
        return payload;
    }

    /** 清零 payload：结构不变、{@code findings} 为空。 */
    public static Map<String, Object> emptyPayload(String clearedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", List.of());
        payload.put("ruleVersion", RULE_VERSION);
        payload.put("clearedAt", clearedAt);
        return payload;
    }

    /**
     * 构造一条新 finding：{@code disposition} 恒 {@code open}、{@code ruleVersion} 恒取当前版本。
     *
     * <p>对外可见是刻意的：调用点若自行 {@code new Finding(...)}，就要手填 {@code disposition}
     * 与 {@code ruleVersion}——前者是收敛守卫的判据、后者是灰度统计的分组键，填错不报错、
     * 只会让统计静默失真。</p>
     */
    public static Finding finding(String gate, String ruleId, String severity, String draftRef,
                                  String subject, String evidence, String fixClass) {
        return new Finding(gate, ruleId, severity, draftRef, subject, evidence,
                fixClass, DISPOSITION_OPEN, RULE_VERSION);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
