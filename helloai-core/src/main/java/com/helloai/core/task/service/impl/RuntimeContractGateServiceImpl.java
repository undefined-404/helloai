package com.helloai.core.task.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.util.RuntimeContractGate;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.task.service.RuntimeContractGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * `G5` 实现：确定性文本匹配（{@link RuntimeContractGate}）+ 一次有界 LLM 语义判断。
 *
 * <p><b>为什么语义结论只给 warn</b>：`ADR-008` 决策 17 —— LLM 结论永无 block 权，block 只给
 * 确定性规则。模型说「可能影响下游」时，正确动作是把风险摆给用户，而不是替用户否决整改。</p>
 *
 * <p><b>为什么确定性已阻断就不调模型</b>：确定性结论已经给出可操作的证据（哪条下游、哪个标识），
 * 再花 30s 等一次模型不会改变结论；先便宜后昂贵，与 G4 同口径。</p>
 *
 * <p><b>为什么失败一律 fail-open + 留痕</b>：超时 / 模型不可用 / 输出不可解析都返回一条
 * `warn` 的「语义门未执行」。不阻断正确整改，也不让「这轮只过了确定性层」这件事静默消失。</p>
 *
 * <p><b>为什么 {@link SemanticJudgePort} 用 {@code ObjectProvider} 懒解析</b>：它的实现链
 * （agent 支撑 → AgentService → SubTaskStatsPortAdapter）反依赖 {@code SubTaskService}，
 * 强依赖会形成
 * {@code SubTaskServiceImpl → RuntimeContractGateServiceImpl → SemanticJudgePortImpl → AgentServiceImpl → SubTaskService}
 * 的构造环；懒解析把该边推迟到真正调用时（此时容器已就绪）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeContractGateServiceImpl implements RuntimeContractGateService {

    /** 语义层规则号（确定性层见 {@code RuntimeContractGate.G5_DOWNSTREAM_REF_BREAK}）。 */
    public static final String G5_SEMANTIC_CONTRACT_BREAK = "G5_SEMANTIC_CONTRACT_BREAK";

    private static final String PROMPT_PATH = "prompts/runtime-contract-gate.md";
    private static final String SEVERITY_WARN = "warn";

    /** 有界等待上限：与 G4 同口径，超时窗口外到达的结果直接丢弃。 */
    static final int TIMEOUT_SECONDS = 30;

    /** 单段材料注入上限：判定只需看出「要什么 / 给对方什么」。 */
    static final int MATERIAL_FIELD_LIMIT = 800;

    /** 最多采纳的语义冲突条数（防模型刷屏）。 */
    static final int MAX_BREAKS = 8;

    private static final int EVIDENCE_LIMIT = 160;

    private final ObjectProvider<SemanticJudgePort> semanticJudgePortProvider;
    private final ObjectMapper objectMapper;

    @Override
    public RuntimeContractGate.Result check(String oldDeliverable, String oldAcceptance,
                                            String newDeliverable, String newAcceptance,
                                            List<RuntimeContractGate.Downstream> downstreams,
                                            List<RuntimeContractGate.Upstream> upstreams,
                                            Long judgeAgentId) {
        RuntimeContractGate.Result deterministic = RuntimeContractGate.check(
                oldDeliverable, oldAcceptance, newDeliverable, newAcceptance, downstreams);
        if (deterministic.hasBlocking()) {
            return deterministic;
        }
        List<RuntimeContractGate.Finding> findings = new ArrayList<>(deterministic.findings());
        findings.addAll(semantic(oldDeliverable, oldAcceptance, newDeliverable, newAcceptance,
                downstreams, upstreams, judgeAgentId));
        return new RuntimeContractGate.Result(List.copyOf(findings));
    }

    // ── 语义层 ─────────────────────────────────────────────────────

    private List<RuntimeContractGate.Finding> semantic(String oldDeliverable, String oldAcceptance,
                                                       String newDeliverable, String newAcceptance,
                                                       List<RuntimeContractGate.Downstream> downstreams,
                                                       List<RuntimeContractGate.Upstream> upstreams,
                                                       Long judgeAgentId) {
        boolean noDownstream = downstreams == null || downstreams.isEmpty();
        boolean noUpstream = upstreams == null || upstreams.isEmpty();
        if (noDownstream && noUpstream) {
            return List.of();
        }
        if (judgeAgentId == null) {
            return List.of(notExecuted("未取到可用于判定的 planner agent"));
        }
        try {
            String raw = callWithTimeout(judgeAgentId,
                    renderPrompt(oldDeliverable, oldAcceptance, newDeliverable, newAcceptance,
                            downstreams, upstreams));
            if (raw == null || raw.isBlank()) {
                return List.of(notExecuted("模型调用超时或失败"));
            }
            List<Break> breaks = parse(raw);
            if (breaks == null) {
                return List.of(notExecuted("模型输出不是约定 JSON"));
            }
            List<RuntimeContractGate.Finding> findings = new ArrayList<>();
            for (Break b : breaks) {
                if (findings.size() >= MAX_BREAKS) {
                    break;
                }
                findings.add(new RuntimeContractGate.Finding(G5_SEMANTIC_CONTRACT_BREAK,
                        SEVERITY_WARN, evidence(b)));
            }
            log.info("G5 语义门判定完成: breaks={}", breaks.size());
            return findings;
        } catch (Exception e) {
            log.warn("G5 语义门判定异常（降级放行）: err={}", e.getMessage());
            return List.of(notExecuted("判定异常: " + e.getMessage()));
        }
    }

    /** 有界等待；失败/超时返回 {@code null}（与 {@code SemanticContractGate} 同口径）。 */
    private String callWithTimeout(Long agentId, String userPrompt) {
        String systemPrompt = "你是执行期整改的契约校验器，只输出约定的 JSON。";
        try {
            SemanticJudgePort port = semanticJudgePortProvider.getObject();
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> port.judgeOnce(agentId, systemPrompt, userPrompt));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("G5 语义门判定超时（{}s，降级放行）: agentId={}", TIMEOUT_SECONDS, agentId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("G5 语义门判定被中断（降级放行）");
            return null;
        } catch (Exception e) {
            log.warn("G5 语义门判定调用失败（降级放行）: err={}", e.getMessage());
            return null;
        }
    }

    // ── 材料与解析 ─────────────────────────────────────────────────

    String renderPrompt(String oldDeliverable, String oldAcceptance,
                        String newDeliverable, String newAcceptance,
                        List<RuntimeContractGate.Downstream> downstreams,
                        List<RuntimeContractGate.Upstream> upstreams) throws Exception {
        String template;
        try (InputStream in = new ClassPathResource(PROMPT_PATH).getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【整改前要求】\n交付物：").append(clip(oldDeliverable)).append('\n')
                .append("验收：").append(clip(oldAcceptance)).append("\n\n");
        sb.append("【整改后要求】\n交付物：").append(clip(newDeliverable)).append('\n')
                .append("验收：").append(clip(newAcceptance)).append("\n\n");
        if (downstreams != null && !downstreams.isEmpty()) {
            sb.append("【下游期待】\n");
            for (RuntimeContractGate.Downstream d : downstreams) {
                sb.append("- 《").append(nz(d.title())).append("》：").append(clip(d.text())).append('\n');
            }
            sb.append('\n');
        }
        if (upstreams != null && !upstreams.isEmpty()) {
            sb.append("【上游产出/约束】\n");
            for (RuntimeContractGate.Upstream u : upstreams) {
                sb.append("- 《").append(nz(u.title())).append("》：").append(clip(u.text())).append('\n');
            }
        }
        return template.replace("{{MATERIAL}}", sb.toString().trim());
    }

    /**
     * @return {@code null} = 不是约定 JSON（调用方按「未执行」处理，而不是「无冲突」）
     */
    List<Break> parse(String raw) {
        JsonNode root = readJson(raw);
        if (root == null) {
            return null;
        }
        JsonNode arr = root.path("breaks");
        if (!arr.isArray()) {
            return null;
        }
        List<Break> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String reason = text(item, "reason");
            if (reason == null) {
                continue;
            }
            out.add(new Break(text(item, "target"), reason, text(item, "quote")));
            if (out.size() >= MAX_BREAKS) {
                break;
            }
        }
        return out;
    }

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

    record Break(String target, String reason, String quote) {
    }

    private static RuntimeContractGate.Finding notExecuted(String reason) {
        return new RuntimeContractGate.Finding(G5_SEMANTIC_CONTRACT_BREAK, SEVERITY_WARN,
                "语义门未执行（" + reason + "），本次仅按确定性规则放行");
    }

    private static String evidence(Break b) {
        StringBuilder sb = new StringBuilder("语义判断可能影响下游/上游");
        if (b.target() != null) {
            sb.append("「").append(b.target()).append("」");
        }
        sb.append("：").append(b.reason());
        if (b.quote() != null) {
            sb.append("（整改后原文：").append(clip(b.quote())).append("）");
        }
        String text = sb.toString();
        return text.length() <= EVIDENCE_LIMIT ? text : text.substring(0, EVIDENCE_LIMIT) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "（未命名）" : s;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= MATERIAL_FIELD_LIMIT ? t : t.substring(0, MATERIAL_FIELD_LIMIT) + "…";
    }
}
