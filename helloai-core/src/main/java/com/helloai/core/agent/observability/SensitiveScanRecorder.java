package com.helloai.core.agent.observability;

import com.helloai.common.config.SensitiveScanProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SensitiveScanPoint;
import com.helloai.common.constant.SensitiveScanRule;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.shared.util.SensitiveContentScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感内容扫描挂点统一入口（`MA-8` 第一段：**只记不拦**）。
 *
 * <p><b>本类只做「扫 + 记」两件事，绝不做阻断</b>：
 * 蓝图 §4 `MA-8` ⑥ 写死「在误报率未知的情况下 fail-close 是赌博」，且 `MA-8` 的定位是
 * 「只记录、随时可停、不通也不亏」的纯观测项。因此本类任何分支都不抛出、不返回
 * 「是否应阻断」的结论——调用方拿到 {@code void}，行为完全不受影响。</p>
 *
 * <h3>为什么需要一个服务，而不是让三个挂点各自调扫描器</h3>
 * 三个挂点要写<b>同一条</b>事件、同一种 payload。三处各写一遍必然分叉（判据 4
 * 「同一事实双写必分叉」）——尤其 payload 字段名一旦不同，观察期的聚合 SQL 就会漏数。
 * 扫描器（{@link SensitiveContentScanner}）管「怎么判」，本类管「怎么记」。</p>
 *
 * <h3>落点</h3>
 * {@code core/agent/observability}：与 {@code CircuitBreakerEventRecorder} 同类
 * （「旁路观测、不影响主链」）。planner 域调本类属 {@code planner > agent} 顺向合法
 * （CODE_STYLE 依赖链 {@code planner(0) > review(1) > task(2) > knowledge(3) > agent(4)}），
 * 反向则不成立——因此本类<b>不能</b>放在 planner 域。</p>
 */
@Slf4j
@Component
public class SensitiveScanRecorder {

    /** 事件负载里命中列表的长度上限（截断后明细只用于定位，聚合看 {@code ruleCounts}）。 */
    private static final int MAX_HITS_IN_PAYLOAD = 12;

    private final SensitiveScanProperties properties;
    private final TaskTimelinePort taskTimelinePort;

    public SensitiveScanRecorder(SensitiveScanProperties properties, TaskTimelinePort taskTimelinePort) {
        this.properties = properties;
        this.taskTimelinePort = taskTimelinePort;
    }

    /**
     * 扫描并记录（best-effort，<b>绝不抛出</b>）。
     *
     * <p>本方法一定不抛异常：任何扫描/写事件失败都只 log.warn。挂点分布在用户输入、
     * 产物落库、LLM 出站三条主链上，观测能力<b>不允许</b>反向影响业务可用性。</p>
     *
     * @param point     挂点（决定事件的 {@code point} 字段，聚合 SQL 按它分组）
     * @param text      待扫文本（{@code null} / 空串直接返回）
     * @param fieldName 命中所属字段名（如 {@code message} / {@code output} / {@code prompt}），用于定位
     * @param taskId    主任务 ID（可空；对话级挂点无任务）
     * @param subTaskId 子任务 ID（可空）
     * @param role      事件产生方角色
     * @param agentId   关联 Agent（可空）
     */
    public void scanAndRecord(SensitiveScanPoint point,
                              String text,
                              String fieldName,
                              Long taskId,
                              Long subTaskId,
                              AgentRole role,
                              Long agentId) {
        if (!properties.isEnabled() || text == null || text.isEmpty()) {
            return;
        }
        try {
            Set<SensitiveScanRule> disabled = resolveDisabledRules();
            SensitiveContentScanner.Result result =
                    SensitiveContentScanner.scan(text, properties.getMaxScanChars(), disabled);
            if (result.isEmpty()) {
                return;
            }
            // 日志只打「规则 + 数量 + 指纹」，绝不打原文（否则日志成为新的泄露源）
            log.warn("敏感内容命中（log-only，不阻断）: point={}, field={}, rules={}, hits={}, truncated={}, "
                            + "taskId={}, subTaskId={}",
                    point.code(), fieldName, result.hitRules(), result.hits().size(),
                    result.truncated(), taskId, subTaskId);
            taskTimelinePort.recordEvent(
                    taskId,
                    subTaskId,
                    TimelineEventCode.SENSITIVE_CONTENT_DETECTED.code(),
                    role,
                    agentId,
                    buildPayload(point, fieldName, result));
        } catch (Exception e) {
            log.warn("敏感内容扫描记录失败（不影响主链）: point={}, field={}, err={}",
                    point.code(), fieldName, e.getMessage());
        }
    }

    /**
     * 扫描总开关是否打开。
     *
     * <p>给<b>需要区分三态</b>的挂点用（目前只有附件挂点④）：它要把结果落成一列，
     * 「开关关闭」必须写 {@code null}（未扫描）而不是 {@code 0}（扫过没命中）——否则
     * 观察期数据里「关掉了」与「扫了干净」不可区分（判据 10「显式声明」）。</p>
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 带<b>对话级定位信息</b>的扫描记录（挂点④ 附件正文专用）。
     *
     * <p><b>为什么需要这个重载</b>：附件正文的命中事件没有 {@code taskId} / {@code subTaskId}
     * （对话还没拆解成任务），若只按 {@link #scanAndRecord} 记，事件落库后<b>永远查不到归属</b>
     * ——用户既不知道、也无从定位是哪份文件。这里把 {@code conversationId} 与
     * {@code attachmentId} 写进 payload，让「哪份文件、哪个对话」可在库里直接聚合。</p>
     *
     * <p>注意：{@code taskTimelinePort} 的 {@code taskId}/{@code subTaskId} 仍传 null
     * （该表两列可空，系统级 / 对话级事件本就无任务归属），定位信息只在 payload 里。</p>
     *
     * @param point          挂点（本重载专用于 {@link SensitiveScanPoint#ATTACHMENT}）
     * @param text           待扫文本（附件解析出的正文）
     * @param fieldName      命中所属字段名
     * @param conversationId 会话 ID（可空：平台产物登记时可能为空）
     * @param attachmentId   附件 ID（用于定位到具体文件）
     * @return 命中摘要（**无命中返回 {@link ScanSummary#none()}**）；调用方可据此向用户提示
     */
    public ScanSummary scanAttachmentAndRecord(SensitiveScanPoint point,
                                               String text,
                                               String fieldName,
                                               Long conversationId,
                                               Long attachmentId) {
        if (!properties.isEnabled() || text == null || text.isEmpty()) {
            return ScanSummary.none();
        }
        try {
            Set<SensitiveScanRule> disabled = resolveDisabledRules();
            // 附件用更大的扫描窗口：异步线程执行，且配置文件的关键行常在 8000 字符之后
            SensitiveContentScanner.Result result =
                    SensitiveContentScanner.scan(text, properties.getAttachmentMaxScanChars(), disabled);
            if (result.isEmpty()) {
                return ScanSummary.none();
            }
            log.warn("敏感内容命中（log-only，不阻断）: point={}, field={}, rules={}, hits={}, truncated={}, "
                            + "conversationId={}, attachmentId={}",
                    point.code(), fieldName, result.hitRules(), result.hits().size(),
                    result.truncated(), conversationId, attachmentId);
            Map<String, Object> payload = buildPayload(point, fieldName, result);
            // 对话级定位信息：该事件无 task/subTask 归属，靠这两个字段回溯到具体文件
            payload.put("conversationId", conversationId);
            payload.put("attachmentId", attachmentId);
            taskTimelinePort.recordEvent(
                    null,
                    null,
                    TimelineEventCode.SENSITIVE_CONTENT_DETECTED.code(),
                    AgentRole.PLANNER,
                    null,
                    payload);
            return new ScanSummary(result.hits().size(), result.hitRules(), result.truncated());
        } catch (Exception e) {
            log.warn("敏感内容扫描记录失败（不影响主链）: point={}, field={}, err={}",
                    point.code(), fieldName, e.getMessage());
            return ScanSummary.none();
        }
    }

    /**
     * 一次附件扫描的摘要（<b>供用户侧提示用</b>，不含原文）。
     *
     * <p>只暴露「命中几处、哪几类规则、是否截断」——这三点足以让用户判断「要不要把这份文件
     * 从他处挪走」，又不至于把密钥再复制一遍到界面上（那等于二次泄露）。</p>
     *
     * @param hitCount  命中条数
     * @param rules     命中的规则（转成人话标签由前端决定，后端只给稳定 code）
     * @param truncated 是否因上限截断（{@code true} 表示「后面还没扫到」，不能当作已扫完）
     */
    public record ScanSummary(int hitCount, Set<SensitiveScanRule> rules, boolean truncated) {

        private static final ScanSummary NONE = new ScanSummary(0, Set.of(), false);

        /**
         * 空摘要（无命中 / 未扫描）。
         *
         * <p><b>为什么是 public</b>：调用方（挂点④）在「扫描开关关闭」时需要一个确定的
         * 「什么都没有」值来参与三态判断；让外部各写 {@code new ScanSummary(0, Set.of(), false)}
         * 会造成多处字面量分叉（判据 4）。</p>
         */
        public static ScanSummary none() {
            return NONE;
        }

        /** 是否什么都没命中。 */
        public boolean isEmpty() {
            return hitCount <= 0;
        }

        /** 命中规则的稳定 code 列表（事件 / 接口传输用；顺序稳定便于前端比对）。 */
        public List<String> ruleCodes() {
            return rules.stream().map(SensitiveScanRule::code).sorted().toList();
        }
    }

    /**
     * 组装事件负载。
     *
     * <p><b>不含原文</b>：只有规则 id、字段名、偏移、SHA-256。蓝图 §4 `MA-8` ⑧ 验收第 4 条
     * 「事件里<b>没有</b>任何原文片段」即由本方法的结构保证（没有可放原文的字段）。</p>
     */
    private Map<String, Object> buildPayload(SensitiveScanPoint point, String fieldName,
                                             SensitiveContentScanner.Result result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("point", point.code());
        payload.put("field", fieldName);
        payload.put("hitCount", result.hits().size());
        payload.put("truncated", result.truncated());

        // 规则 → 命中条数（聚合主用，字段固定，便于按规则分组统计）
        Map<String, Object> ruleCounts = new LinkedHashMap<>();
        for (SensitiveScanRule rule : result.hitRules()) {
            int count = 0;
            for (SensitiveContentScanner.Hit hit : result.hits()) {
                if (hit.rule() == rule) {
                    count++;
                }
            }
            ruleCounts.put(rule.code(), count);
        }
        payload.put("ruleCounts", ruleCounts);

        // 明细：规则 + 偏移 + 指纹（截断后仅供定位，不含原文）
        List<Map<String, Object>> hits = result.hits().stream()
                .limit(MAX_HITS_IN_PAYLOAD)
                .map(hit -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rule", hit.rule().code());
                    item.put("offset", hit.offset());
                    item.put("sha256", hit.sha256());
                    return item;
                })
                .toList();
        payload.put("hits", hits);
        return payload;
    }

    /**
     * 解析「要关掉的规则」配置。
     *
     * <p>配置里写错规则名时记一条 warn 并<b>不静默忽略</b> —— 否则「配置写错 = 规则还在跑」
     * 与「配置生效」在库里长得一模一样（判据 10「显式声明」）。</p>
     */
    private Set<SensitiveScanRule> resolveDisabledRules() {
        SensitiveScanRule.ParseResult parsed =
                SensitiveScanRule.parseDisabled(properties.getDisabledRules());
        if (!parsed.unknown().isEmpty()) {
            log.warn("敏感扫描配置含无法识别的规则名（已忽略，规则仍在生效）: unknown={}", parsed.unknown());
        }
        if (parsed.disabled().isEmpty()) {
            return java.util.EnumSet.noneOf(SensitiveScanRule.class);
        }
        return java.util.EnumSet.copyOf(parsed.disabled());
    }
}
