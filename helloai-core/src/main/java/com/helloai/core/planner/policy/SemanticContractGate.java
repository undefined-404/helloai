package com.helloai.core.planner.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.task.port.SubTaskView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 语义门：{@code G4_UNSATISFIABLE_CONTRACT}——验收要求撞上上游约束（设计 §6.2）。
 *
 * <p><b>要解决的是什么</b>：{@code TD-11} 的 {@code acceptancePreconditions} 只标注不阻断，
 * 于是「上游明令禁止、下游却把该事实写进验收」的草案一路放行到执行链，执行者无解——编数据与
 * 交空表都被判低分，预算耗尽进死信（{@code LOG-20261025-014} 的原始形态）。
 * 规则门看不见这一层：{@code G1} 只判依赖图、{@code G2} 只判单条字段写法、
 * {@code G3} 只判层级，都不读「验收 × 上游约束」的语义关系。</p>
 *
 * <p><b>为什么落在 planner 而不是 {@code DraftGateChecker}</b>：完整判定依赖一次模型调用，
 * 而 {@code DraftGateChecker} 在 {@code helloai-common}、零外部依赖（不许调 LLM）。
 * ruleId 常量仍登记在 {@code DraftGateChecker}，保持「所有 ruleId 一个登记处」。</p>
 *
 * <p><b>为什么整类 fail-open</b>：模型不可用 / 超时 / 输出不可解析时，本类返回一条
 * {@code severity=warn} 的「语义门未执行」结论并**放行**。理由与全链路门禁一致：
 * 质量闸门拦不住的代价（用户点确认就卡住、无法自救）高于放过一次的代价（一次不可满足契约）。
 * 但**未执行必须留痕**——否则「规则存在」会退化成「路径上不生效」，这是判据 1 的原话。</p>
 *
 * <p><b>只在确认前跑一次</b>：{@code confirmPlan} 是唯一放行口，也是唯一值得付一次模型调用的
 * 时点。拆解 / 展开 / 编辑三条生成与改造路径不调模型（改造类另有回滚兜底）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticContractGate {

    private static final String PROMPT_PATH = "prompts/planner-semantic-gate.md";

    /** 单字段注入上限：草案的 acceptance / constraints 可能很长，判定只需要能看出「要什么 / 禁什么」。 */
    static final int FIELD_LIMIT = 400;

    /** 每条草案最多列出的上游约束条数（DAG 深时上游可能很多）。 */
    static final int UPSTREAM_LIMIT = 5;

    /** 一次判定最多采纳的冲突条数（防止模型刷屏；超出部分截断）。 */
    static final int MAX_CONFLICTS = 8;

    /** 判定证据字段上限：错误消息要能在一行里读完。 */
    static final int EVIDENCE_QUOTE_LIMIT = 60;
    static final int EVIDENCE_REASON_LIMIT = 120;

    /** 单次判定的等待上限（秒）：底层请求无法强杀，超时窗口外到达的结果直接丢弃。 */
    static final int TIMEOUT_SECONDS = 30;

    private final SemanticJudgePort semanticJudgePort;
    private final ObjectMapper objectMapper;

    /**
     * 判定「验收 × 上游约束」冲突。
     *
     * @param drafts         本任务当前全部待审草案（必须是**整批**：跨批次引入的约束只有整批才看得见）
     * @param plannerAgentId 产出这批草案的 planner agent；{@code null} 时无法借模型 ⇒ 记「未执行」放行
     * @return 结论；**从不抛异常**（失败一律折算成 warn 级「未执行」）
     */
    public DraftGateChecker.Result judge(List<SubTaskView> drafts, Long plannerAgentId) {
        if (drafts == null || drafts.isEmpty()) {
            return new DraftGateChecker.Result(List.of());
        }
        try {
            List<Node> nodes = buildNodes(drafts);
            if (nodes.stream().allMatch(n -> n.upstreams().isEmpty())) {
                // 没有「有上游」的草案 ⇒ 不存在「上游约束」这个输入，调模型是纯浪费 token
                return new DraftGateChecker.Result(List.of());
            }
            if (plannerAgentId == null) {
                return notExecuted("未取到 planner agent（草案 context 无 plannerAgentId）");
            }
            String raw = callWithTimeout(plannerAgentId, renderUserPrompt(nodes));
            if (raw == null) {
                return notExecuted("模型调用超时或失败");
            }
            if (raw.isBlank()) {
                return notExecuted("模型返回空正文");
            }
            List<Conflict> conflicts = parse(raw, nodes);
            if (conflicts == null) {
                return notExecuted("模型输出不是约定 JSON");
            }
            log.info("语义门判定完成: drafts={}, conflicts={}", nodes.size(), conflicts.size());
            return new DraftGateChecker.Result(toFindings(nodes, conflicts));
        } catch (Exception e) {
            log.warn("语义门判定异常（降级放行）: err={}", e.getMessage());
            return notExecuted("判定异常: " + e.getMessage());
        }
    }

    // ── 输入装配 ───────────────────────────────────────────────────

    /** 草案 + 它的**传递**上游（依赖可跨多层，只看直接上游会漏掉 `A→B→C` 里 A 对 C 的约束）。 */
    record Node(Long id, String ref, String title, String acceptance, String constraints, List<Node> upstreams) {
    }

    List<Node> buildNodes(List<SubTaskView> drafts) {
        Map<Long, SubTaskView> byId = new LinkedHashMap<>();
        for (SubTaskView d : drafts) {
            if (d != null && d.id() != null) {
                byId.put(d.id(), d);
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (SubTaskView d : byId.values()) {
            List<Node> ups = new ArrayList<>();
            Set<Long> seen = new LinkedHashSet<>();
            collectUpstreams(d, byId, seen, ups);
            nodes.add(new Node(d.id(), refOf(d), d.title(), d.acceptance(), d.constraints(), ups));
        }
        return nodes;
    }

    /** 深度优先收集传递上游（`seen` 同时防环：门 1 判过环，这里只是防御脏数据）。 */
    private void collectUpstreams(SubTaskView current, Map<Long, SubTaskView> byId,
                                  Set<Long> seen, List<Node> out) {
        if (out.size() >= UPSTREAM_LIMIT || current.dependsOn() == null) {
            return;
        }
        for (Long depId : current.dependsOn()) {
            if (depId == null || !seen.add(depId)) {
                continue;
            }
            SubTaskView up = byId.get(depId);
            if (up == null) {
                continue; // 跨批次依赖：本批拿不到它的约束，规则门 G1 已单独报
            }
            out.add(new Node(up.id(), refOf(up), up.title(), up.acceptance(), up.constraints(), List.of()));
            if (out.size() >= UPSTREAM_LIMIT) {
                return;
            }
            collectUpstreams(up, byId, seen, out);
        }
    }

    static String refOf(SubTaskView d) {
        return d.draftCode() != null && !d.draftCode().isBlank()
                ? d.draftCode() : String.valueOf(d.id());
    }

    // ── Prompt ─────────────────────────────────────────────────────

    String renderUserPrompt(List<Node> nodes) throws Exception {
        String template;
        try (InputStream in = new ClassPathResource(PROMPT_PATH).getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            if (n.upstreams().isEmpty()) {
                continue; // 无上游 ⇒ 无约束可比对，不进材料（省 token，也不给模型发散的机会）
            }
            sb.append("### Ref: ").append(n.ref()).append(" | 标题：").append(nz(n.title())).append('\n');
            sb.append("上游约束：\n");
            for (Node up : n.upstreams()) {
                sb.append("- 上游 Ref ").append(up.ref()).append("《").append(nz(up.title())).append("》：")
                        .append(clip(up.constraints(), FIELD_LIMIT)).append('\n');
            }
            sb.append("本草案验收：").append(clip(n.acceptance(), FIELD_LIMIT)).append("\n\n");
        }
        return template.replace("{{DRAFTS}}", sb.toString().trim());
    }

    // ── 模型调用 ───────────────────────────────────────────────────

    /** 有界等待；失败/超时返回 {@code null}（与 {@code ExecutorIssueResolutionAssessor} 同口径）。 */
    private String callWithTimeout(Long plannerAgentId, String userPrompt) {
        String systemPrompt = "你是任务拆解的门禁判定器，只输出约定的 JSON。";
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> semanticJudgePort.judgeOnce(plannerAgentId, systemPrompt, userPrompt));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("语义门判定超时（{}s，降级放行）: plannerAgentId={}", TIMEOUT_SECONDS, plannerAgentId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("语义门判定被中断（降级放行）");
            return null;
        } catch (Exception e) {
            log.warn("语义门判定调用失败（降级放行）: err={}", e.getMessage());
            return null;
        }
    }

    // ── 输出解析 ───────────────────────────────────────────────────

    /**
     * 一条模型给出的冲突。
     *
     * @param acceptanceQuote 验收原文片段（模型可能改写，落证据时仍原样引用，让用户能核对）
     */
    record Conflict(String draftRef, String upstreamRef, String acceptanceQuote,
                    String constraintQuote, String reason) {
    }

    /**
     * 解析模型输出。
     *
     * <p><b>只认本批出现过的 Ref</b>：模型常把 `draftRef` 写成标题或自造编号，这种冲突定位不到草案，
     * 采纳它只会得到一条用户无法操作的 blocking（错误消息里没有可点的行）。</p>
     *
     * @return {@code null} = 输出不是约定 JSON（调用方按「未执行」处理，而不是按「无冲突」）
     */
    List<Conflict> parse(String raw, List<Node> nodes) {
        JsonNode root = readJson(raw);
        if (root == null) {
            return null;
        }
        JsonNode arr = root.path("conflicts");
        if (!arr.isArray()) {
            return null;
        }
        Set<String> knownRefs = new LinkedHashSet<>();
        for (Node n : nodes) {
            knownRefs.add(n.ref());
        }
        List<Conflict> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (out.size() >= MAX_CONFLICTS) {
                break;
            }
            String draftRef = text(item, "draftRef");
            String upstreamRef = text(item, "upstreamRef");
            if (draftRef == null || upstreamRef == null) {
                continue;
            }
            if (!knownRefs.contains(draftRef) || !knownRefs.contains(upstreamRef)) {
                log.debug("语义门丢弃无法定位的冲突: draftRef={}, upstreamRef={}", draftRef, upstreamRef);
                continue;
            }
            if (draftRef.equals(upstreamRef)) {
                continue;
            }
            out.add(new Conflict(draftRef, upstreamRef, text(item, "acceptanceQuote"),
                    text(item, "constraintQuote"), text(item, "reason")));
        }
        return out;
    }

    /** 优先整体当 JSON；失败时退一步取第一个 `{` 到最后一个 `}`（模型偶尔会带前后说明文字）。 */
    private JsonNode readJson(String raw) {
        String body = raw == null ? "" : raw.trim();
        JsonNode direct = tryRead(body);
        if (direct != null) {
            return direct;
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return tryRead(body.substring(start, end + 1));
    }

    private JsonNode tryRead(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ── 结论映射 ───────────────────────────────────────────────────

    private List<DraftGateChecker.Finding> toFindings(List<Node> nodes, List<Conflict> conflicts) {
        Map<String, Node> byRef = new HashMap<>();
        for (Node n : nodes) {
            byRef.put(n.ref(), n);
        }
        List<DraftGateChecker.Finding> findings = new ArrayList<>();
        for (Conflict c : conflicts) {
            Node up = byRef.get(c.upstreamRef());
            String upstreamTitle = up == null ? c.upstreamRef() : nz(up.title());
            String evidence = "验收要求撞上游约束：" + quote(c.acceptanceQuote())
                    + " 与上游《" + upstreamTitle + "》的约束 " + quote(c.constraintQuote())
                    + " 不可能同时满足" + why(c.reason());
            findings.add(new DraftGateChecker.Finding(
                    DraftGateChecker.GATE_SEMANTIC,
                    DraftGateChecker.G4_UNSATISFIABLE_CONTRACT,
                    DraftGateChecker.SEVERITY_BLOCK,
                    c.draftRef(),
                    "acceptance",
                    evidence,
                    FIX_CLARIFY,
                    DraftGateChecker.DISPOSITION_OPEN,
                    DraftGateChecker.RULE_VERSION));
        }
        return findings;
    }

    /**
     * 「未执行」结论：{@code warn} 级，挂批次（`draftRef = null`）→ 由
     * {@code SubTaskGateFindingsStore} 落到首条草案。
     *
     * <p>刻意用 warn 而非 block：模型不可用时拦下确认等于让用户无路可走；但也不能什么都不写，
     * 否则没人知道这一轮的「通过」只过了规则门。</p>
     */
    private DraftGateChecker.Result notExecuted(String reason) {
        return new DraftGateChecker.Result(List.of(new DraftGateChecker.Finding(
                DraftGateChecker.GATE_SEMANTIC,
                DraftGateChecker.G4_UNSATISFIABLE_CONTRACT,
                DraftGateChecker.SEVERITY_WARN,
                null,
                "acceptance",
                "语义门未执行（" + reason + "），本次仅按规则门放行",
                FIX_CLARIFY,
                DraftGateChecker.DISPOSITION_OPEN,
                DraftGateChecker.RULE_VERSION)));
    }

    /**
     * 语义冲突的处置类别 = {@code C}（澄清或显式假设）。
     *
     * <p>不走 {@code A}（整条重修）：冲突是「验收与上游约束」这对关系的矛盾，重写整条草案
     * 往往会把上游约束一起改掉；{@code C} 的语义正是「要么澄清上游约束、要么在验收里写显式假设」，
     * 与本判据给的修复路径一致。</p>
     */
    private static final String FIX_CLARIFY = "C";

    private static String quote(String s) {
        if (s == null || s.isBlank()) {
            return "（原文缺失）";
        }
        return "「" + clip(s, EVIDENCE_QUOTE_LIMIT) + "」";
    }

    private static String why(String reason) {
        return reason == null || reason.isBlank() ? "" : "：" + clip(reason, EVIDENCE_REASON_LIMIT);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "（未命名）" : s;
    }

    static String clip(String s, int limit) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= limit ? t : t.substring(0, limit) + "…";
    }
}
