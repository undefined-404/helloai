package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.agent.port.MediaRef;
import com.helloai.core.agent.support.AttachmentMediaAssembler;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.shared.util.VerificationFence;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自动核验证据装配器：把子任务产出证据装配为核验 Prompt 可消费的内容（自 {@code SubTaskReviewServiceImpl} 拆分）。
 *
 * <p>三块职责：证据硬检查 {@link #checkEvidence(SubTaskView)}（fail-close，含物化竞态补偿）、
 * 产出提取与围栏信号、附件族装配 {@link #buildEvidenceContent(SubTaskView)}（清单 + 文本限额注入 + 媒体可见性标注）。</p>
 *
 * <p>纯装配无状态：不触发状态变更，只读附件/子任务数据并渲染文本。</p>
 */
@Slf4j
@Component
public class ReviewEvidenceAssembler {

    private final AttachmentService attachmentService;
    private final AgentDispatchProperties dispatchProperties;

    /**
     * 驳回快照冻结证据总字符上限：超过则不冻结（复判退回漂移跳过）。
     * 证据五段本身已按核验口径截断（附件总限额 200000 字符），本上限只防异常数据把 JSONB 撑大。
     */
    static final int FROZEN_EVIDENCE_MAX_CHARS = 300_000;

    /** 每文件保底注入字符数（`RV-9`：头 3/4 + 尾 1/4），防「先到的分片吃光预算」。 */
    static final int EVIDENCE_FILE_FLOOR_CHARS = 4_096;

    /** 区间注入时头尾切分比例（头 3 / 尾 1）。 */
    private static final int EVIDENCE_HEAD_PARTS = 3;

    /** 区间注入时尾部最小字符数（低于此值不再切尾，避免正文只剩几个字符）。 */
    private static final int EVIDENCE_MIN_TAIL_CHARS = 32;

    /**
     * 证据正文装配结果（`RV-8` / `RV-9` / `RV-10` 的同源产物）。
     *
     * <p><b>`VU-2` 增设 {@link #mediaVisibility} 与 {@link #mediaRefs}</b>：核验要能「看图」，
     * 需要两样东西同时到位——{@code mediaRefs} 是送进模型消息的<b>图片引用</b>（装载在请求
     * 装配层），{@code mediaVisibility} 是告诉模型的<b>说明文本</b>。两者必须是<b>同一次判定</b>
     * 的产物（判据 32）：分开算就会出现「说明说已带来图、实际一张没送」或反过来的自相矛盾，
     * 而那种矛盾在 prompt 里完全看不出来。</p>
     *
     * @param attachmentContent 注入正文（按族配额截断；`VU-2` 起<b>不再</b>含媒体说明）
     * @param evidenceLedger    注入台账（与正文同一次分配循环产出）
     * @param mechanicalCheck   表格类机械预检结论（无表格附件时为空串）
     * @param mediaVisibility   媒体可见性说明（无媒体附件时为空串）
     * @param mediaRefs         随证据送模型的图片引用（无图时为空表，绝不为 {@code null}）
     */
    public record EvidenceContent(String attachmentContent, String evidenceLedger, String mechanicalCheck,
                                  String mediaVisibility, List<MediaRef> mediaRefs) {

        public EvidenceContent {
            attachmentContent = attachmentContent == null ? "" : attachmentContent;
            evidenceLedger = evidenceLedger == null ? "" : evidenceLedger;
            mechanicalCheck = mechanicalCheck == null ? "" : mechanicalCheck;
            mediaVisibility = mediaVisibility == null ? "" : mediaVisibility;
            mediaRefs = mediaRefs == null ? List.of() : List.copyOf(mediaRefs);
        }

        /** 兼容构造：`VU-2` 之前的调用点（无媒体语义，行为与改造前一致）。 */
        public EvidenceContent(String attachmentContent, String evidenceLedger, String mechanicalCheck) {
            this(attachmentContent, evidenceLedger, mechanicalCheck, "", List.of());
        }
    }

    /**
     * 媒体作用域装配结果：说明文本 + 图片引用，<b>一次判定产出</b>（`VU-2` / 判据 32）。
     *
     * @param note 注入「随证据提供的图片」段的说明（空串 = 本提交无媒体附件）
     * @param refs 送模型的图片引用（空表 = 不送图）
     */
    private record MediaScope(String note, List<MediaRef> refs) {
        static final MediaScope EMPTY = new MediaScope("", List.of());
    }

    /** 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在 IDE 增量编译里漏抓新增
     * final 字段的坑：显式列为 Spring DI 唯一依据）。 */
    @Autowired
    public ReviewEvidenceAssembler(AttachmentService attachmentService,
                                   AgentDispatchProperties dispatchProperties) {
        this.attachmentService = attachmentService;
        this.dispatchProperties = dispatchProperties;
    }

    /**  证据检查结果。 */
    public record EvidenceCheckResult(boolean ok, String reason, int attachmentCount, boolean outputPresent) {
    }

    /**
     *  证据硬检查：子任务声称的交付物必须有物化附件/可读产出支撑（fail-close）。
     *
     * <p>判定规则：</p>
     * <ul>
     *   <li>无可读附件且执行产出为空 → {@code no_output_no_attachment}：连产出本体
     *       都没有的编造提交，直接拦截；</li>
     *   <li>执行密集任务（交付物声明为脚本/程序/文件）无可读物化附件 →
     *       {@code execution_dense_no_attachment}：产出文本仅为描述性文字，无真实
     *       物化产物支撑，拦截（fail-close——宁可人工介入，不放行存疑产出）；</li>
     *   <li>其余（可读附件存在，或非执行密集任务有文本产出）→ 放行，附件清单注入
     *       核验 Prompt 由 LLM 核对声称交付物与附件的对应关系。</li>
     * </ul>
     *
     * <p>物化在结果回报事务 afterCommit 同步执行、自动核验异步启动，两者存在毫秒级
     * 竞态；执行密集任务未发现可读附件时等待 {@code reviewEvidenceCheckWaitMs} 后重查
     * 一次，避免物化未完成被误判为无证据。</p>
     */
    public EvidenceCheckResult checkEvidence(SubTaskView subTask) {
        List<AttachmentView> readable = readableAttachments(subTask.id());
        String output = SubTaskOutputExtractor.extractExecutionOutput(subTask.context());
        boolean hasOutput = output != null && !output.isBlank();
        boolean isDense = SubTaskDispatchService.isExecutionDense(subTask);

        if (readable.isEmpty()) {
            // 竞态补偿：执行密集 + 有产出文本时等待窗口重查（物化在 afterCommit 同步完成）
            if (isDense && hasOutput) {
                int waitMs = dispatchProperties.getReviewEvidenceCheckWaitMs();
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    readable = readableAttachments(subTask.id());
                }
            }
            if (readable.isEmpty()) {
                if (!hasOutput) {
                    return new EvidenceCheckResult(false, "no_output_no_attachment", 0, false);
                }
                if (isDense) {
                    return new EvidenceCheckResult(false, "execution_dense_no_attachment",
                            readable.size(), true);
                }
            }
        }
        return new EvidenceCheckResult(true, null, readable.size(), hasOutput);
    }

    /** 子任务可读附件列表（local:// 平台直读产物；仅 ACTIVE 有效版本——同名多版本在
     * {@link com.helloai.core.task.service.impl.AttachmentServiceImpl#register} 时
     * 已自动去活，核验只认当前最新上传，避免旧版本冲突污染判定；list 返回 null 防御按空处理）。 */
    private List<AttachmentView> readableAttachments(Long subTaskId) {
        List<AttachmentView> attachments = attachmentService.listActiveViews(subTaskId);
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .filter(AttachmentView::contentLoadable)
                .toList();
    }

    /**
     * 构建判定快照（`RV-5`）：与核验证据同一来源（可读物化附件 + 执行产出），
     * 落库时与 `review_record` 同事务（见 `ReviewService#recordAutoReview`）。
     *
     * <p>体积超阈值只告警不阻断——快照是观测/复判依据，不得因为它自身的大小改变判定。</p>
     */
    public Map<String, Object> buildReviewSnapshot(SubTaskView subTask) {
        return buildReviewSnapshot(subTask, null, null);
    }

    /**
     * 构建判定快照，并在**驳回时可冻结原判证据**（`RV-6`）。
     *
     * <p>驳回记录返工后附件失效、产出被覆盖，只存引用/摘要无法复判；这里把核验 Prompt 实际
     * 消费的七段证据文本（见 {@link ReviewEvidenceView}）原样冻结进 JSONB，复判直接复用。
     * 仅在 {@code result == REJECTED} 且证据非空时冻结——通过记录原对象不会失效，不需要额外存储。</p>
     *
     * <p>冻结体积超过 {@link #FROZEN_EVIDENCE_MAX_CHARS} 时不冻结（快照仍落，复判按
     * {@code snapshot_drift} 跳过）——不拿不完整的原判对象做误伤率度量。</p>
     *
     * @param result   本次核验结论；null / 非 REJECTED 不冻结
     * @param evidence 判定当时实际渲染的证据；null 不冻结
     */
    public Map<String, Object> buildReviewSnapshot(SubTaskView subTask, ReviewResult result,
                                                   ReviewEvidenceView evidence) {
        Map<String, Object> frozen = result == ReviewResult.REJECTED ? freezeEvidence(subTask, evidence) : null;
        Map<String, Object> snapshot = ReviewSnapshotBuilder.build(
                readableAttachments(subTask.id()),
                SubTaskOutputExtractor.extractExecutionOutput(subTask.context()),
                frozen);
        int bytes = ReviewSnapshotBuilder.estimatedBytes(snapshot);
        if (bytes > ReviewSnapshotBuilder.WARN_BYTES) {
            log.warn("核验快照体积超阈值（存储增长观测）: subTaskId={}, bytes={}, threshold={}",
                    subTask.id(), bytes, ReviewSnapshotBuilder.WARN_BYTES);
        }
        return snapshot;
    }

    /**
     * 读取驳回快照里冻结的原判证据；没有冻结（历史记录 / 通过记录 / 超限）时返回 null，
     * 调用方应退回「快照漂移对账」路径。
     */
    public ReviewEvidenceView frozenEvidenceView(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object node = snapshot.get("frozenEvidence");
        if (!(node instanceof Map<?, ?> frozen)) {
            return null;
        }
        Object executionOutput = frozen.get("executionOutput");
        Object attachmentList = frozen.get("attachmentList");
        Object attachmentContent = frozen.get("attachmentContent");
        if (executionOutput == null && attachmentList == null && attachmentContent == null) {
            return null;
        }
        return new ReviewEvidenceView(text(executionOutput), text(attachmentList), text(attachmentContent),
                text(frozen.get("verificationSignal")), text(frozen.get("selfReviewSignal")),
                text(frozen.get("mechanicalCheck")), text(frozen.get("evidenceLedger")),
                // `VU-2` 之前冻结的历史快照无此键 ⇒ text(null) = ""，复判 prompt 与旧行为一致
                text(frozen.get("mediaVisibility")));
    }

    private Map<String, Object> freezeEvidence(SubTaskView subTask, ReviewEvidenceView evidence) {
        if (evidence == null) {
            return null;
        }
        int chars = evidence.totalChars();
        if (chars > FROZEN_EVIDENCE_MAX_CHARS) {
            log.warn("驳回快照冻结证据超限，退回漂移跳过（不拿不完整对象度量误伤率）: "
                            + "subTaskId={}, chars={}, limit={}",
                    subTask.id(), chars, FROZEN_EVIDENCE_MAX_CHARS);
            return null;
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("executionOutput", evidence.executionOutput());
        frozen.put("attachmentList", evidence.attachmentList());
        frozen.put("attachmentContent", evidence.attachmentContent());
        frozen.put("verificationSignal", evidence.verificationSignal());
        frozen.put("selfReviewSignal", evidence.selfReviewSignal());
        // `RV-10` M1 决策① / `RV-8`：机械段与台账随原判证据一起冻结，复判直接复用
        frozen.put("mechanicalCheck", evidence.mechanicalCheck());
        frozen.put("evidenceLedger", evidence.evidenceLedger());
        // `VU-2`：媒体说明随证据冻结——复判重放的是「原判看到的措辞」，
        // 若复判时现算，模型能力或附件一变就会出现「同一份证据、两种说法」
        frozen.put("mediaVisibility", evidence.mediaVisibility());
        frozen.put("chars", chars);
        return frozen;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 抽检前对账：当前证据是否仍是原判定对象（`RV-5`，见 {@link ReviewSnapshotMatcher}）。 */
    public ReviewSnapshotMatcher.Drift checkSnapshotDrift(Map<String, Object> snapshot, SubTaskView current) {
        return ReviewSnapshotMatcher.check(snapshot, readableAttachments(current.id()),
                SubTaskOutputExtractor.extractExecutionOutput(current.context()));
    }

    /**
     *  附件清单：核验 Prompt 注入子任务全部附件（可读 local:// 产物标注平台直读，
     * 外部存储标注不可直读），供核验 LLM 核对"声称交付物 ↔ 真实附件"的对应关系——
     * 声称"文件 203 行 errors=0"但附件清单无对应文件时判不达标。
     */
    public String buildAttachmentList(SubTaskView subTask) {
        List<AttachmentView> attachments = attachmentService.listActiveViews(subTask.id());
        if (attachments == null || attachments.isEmpty()) {
            return "（无物化附件）";
        }
        StringBuilder sb = new StringBuilder();
        for (AttachmentView att : attachments) {
            String size = att.fileSize() != null ? att.fileSize() + " bytes" : "?";
            String readable = att.contentLoadable()
                    ? "平台可直读" : "外部存储（平台不可直读）";
            String type = att.fileType() != null ? att.fileType() : "other";
            sb.append("- ").append(att.fileName())
                    .append("（").append(type).append(", ").append(size).append(", ")
                    .append(readable).append("）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 核验侧附件内容注入（方案3 F2）：把可直读物化附件（local:// 与 minio://）正文截断后
     * 注入核验 Prompt，让 Reviewer 基于真实文件内容核对"声称交付物 ↔ 文件正文 ↔ 验收标准"，
     * 而非仅凭文件名猜测（消除"Reviewer 审查靠摘要+文件名"的幻觉缺口）。
     *
     * <p>限额策略（`RV-9` 起）：单文件 64000、总计 200000（常量单源见
     * {@link AttachmentContentPolicy}）为<b>硬上限</b>，分配顺序为「每文件保底 4096（保底无法
     * 普惠时降为等额分享 total/N）→ 剩余按证据族均分 → 族内均分 → 余量按容量再分配」，避免
     * 靠前的分片吃光预算、交付物主件与后续分片消失；N>195 时保底自动降级，保证注入总量
     * 永不超过预算上限（超限会击穿冻结证据上限 {@link #FROZEN_EVIDENCE_MAX_CHARS}）。
     * 超限文件按表格/非表格分别做「头尾区间」或「行边界前缀」注入，并带结构化
     * {@code [TRUNCATED]} 标注行；不可直读/读取失败/空内容附件不注入正文（清单仍全量展示）；
     * 开关 {@code helloai.dispatch.attachment-content-enabled} 关闭时退化为仅清单
     * （与开关引入前行为一致）。</p>
     *
     * <p>文本硬化：仅对文本类附件注入正文，图片/音频/视频等二进制附件绝不按文本读取
     * （避免二进制乱码进 Prompt 并吞占限额）。</p>
     *
     * <p><b>`VU-2` 起媒体与文本分家</b>：媒体可见性说明不再拼进 {@code attachmentContent}
     * （那一段的语义是「平台直读的<b>文本</b>，超限要截断/续读」），改为随
     * {@link EvidenceContent#mediaVisibility()} 独立返回；同时按同一次附件读取产出
     * {@link EvidenceContent#mediaRefs()}（判据 32：作用域与产出一次算完）。</p>
     *
     * @param subTask 待核验子任务
     * @param mediaVisible 本次核验所用模型是否声明可读图片（`VU-2`：决定说明措辞与是否送图）
     */
    public EvidenceContent buildEvidenceContent(SubTaskView subTask, boolean mediaVisible) {
        // 附件清单只读一次：媒体说明、媒体引用、文本注入台账三处共用（判据 4 / 判据 32）
        List<AttachmentView> all = attachmentService.listActiveViews(subTask.id());
        if (all == null) {
            all = List.of();
        }
        MediaScope media = resolveMediaScope(all, mediaVisible);
        if (!dispatchProperties.isAttachmentContentEnabled()) {
            return new EvidenceContent("（附件内容注入已关闭，仅见清单）",
                    ReviewEvidenceLedger.render(List.of(), 0), "", media.note(), media.refs());
        }
        List<AttachmentView> ordered = priorityOrder(all);
        Map<Long, String> contents = new HashMap<>();
        List<ReviewMechanicalCheck.Source> sources = new ArrayList<>();
        for (AttachmentView att : ordered) {
            if (!att.contentLoadable()
                    || !AttachmentContentPolicy.isTextual(att.mimeType(), att.fileName())) {
                continue;
            }
            String content = readAttachmentContent(att);
            if (content != null) {
                contents.put(att.id(), content);
                sources.add(new ReviewMechanicalCheck.Source(att.id(), att.fileName(), content));
            }
        }
        String mechanicalCheck = ReviewMechanicalCheck.build(sources);
        List<AttachmentView> textFiles = ordered.stream()
                .filter(att -> contents.containsKey(att.id()))
                .toList();
        if (textFiles.isEmpty()) {
            String text = ordered.isEmpty()
                    ? "（无物化附件）" : "（无平台可直读附件，无法核对文件正文）";
            return new EvidenceContent(text, ledgerForNoContent(ordered), mechanicalCheck,
                    media.note(), media.refs());
        }

        Map<Long, Integer> shown = allocate(textFiles, contents);

        StringBuilder sb = new StringBuilder();
        List<ReviewEvidenceLedger.Entry> entries = new ArrayList<>();
        int totalShown = 0;
        boolean anyTruncated = false;
        boolean quotaTruncated = false;
        for (AttachmentView att : ordered) {
            String attName = att.fileName() != null ? att.fileName() : "unknown";
            if (!AttachmentContentPolicy.isTextual(att.mimeType(), att.fileName())) {
                entries.add(new ReviewEvidenceLedger.Entry(att.id(), attName, 0,
                        ReviewEvidenceLedger.STATUS_NOT_TEXTUAL, "", 0, ""));
                continue;
            }
            String content = contents.get(att.id());
            if (content == null) {
                entries.add(new ReviewEvidenceLedger.Entry(att.id(), attName, 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable"));
                continue;
            }
            int originalChars = content.length();
            int want = shown.getOrDefault(att.id(), 0);
            String range = ReviewEvidenceLedger.RANGE_FULL;
            String status = ReviewEvidenceLedger.STATUS_INJECTED;
            appendAttachmentContentHeader(sb, att);
            if (want >= originalChars) {
                sb.append(content).append('\n');
                totalShown += originalChars;
            } else {
                anyTruncated = true;
                String reason = truncationReason(originalChars, want);
                quotaTruncated |= "evidence_budget".equals(reason);
                boolean tabular = ReviewMechanicalCheck.isTabular(att.mimeType(), att.fileName());
                if (tabular && want > EVIDENCE_MIN_TAIL_CHARS) {
                    range = ReviewEvidenceLedger.RANGE_HEAD_TAIL;
                    int head = (want * EVIDENCE_HEAD_PARTS + 3) / 4;
                    int tail = want - head;
                    sb.append(content, 0, head).append('\n');
                    appendOmission(sb, att.id(), originalChars - head - tail, head);
                    sb.append(content, originalChars - tail, originalChars).append('\n');
                    totalShown += want;
                } else {
                    range = ReviewEvidenceLedger.RANGE_HEAD;
                    String partial = TextTruncator.truncateAtLineBoundary(content, want);
                    sb.append(partial).append('\n');
                    if (!tabular) {
                        String structureOutline = buildStructureOutline(content);
                        if (structureOutline != null) {
                            sb.append(structureOutline);
                        }
                    }
                    appendOmission(sb, att.id(), originalChars - partial.length(), partial.length());
                    totalShown += partial.length();
                    want = partial.length();
                }
                sb.append("[TRUNCATED] file=").append(attName)
                        .append(" shown=").append(want)
                        .append(" total=").append(originalChars)
                        .append(" reason=").append(reason)
                        .append(" range=").append(range).append('\n');
                status = ReviewEvidenceLedger.STATUS_TRUNCATED;
            }
            entries.add(new ReviewEvidenceLedger.Entry(att.id(), attName, originalChars,
                    status, range, want, ""));
        }
        if (anyTruncated) {
            sb.append("（部分附件内容已截断至限额）\n");
        }
        if (quotaTruncated) {
            sb.append("（附件内容按证据族配额分配，部分附件仅注入头尾区间）\n");
        }
        String attachmentContent = sb.toString().trim();
        return new EvidenceContent(attachmentContent,
                ReviewEvidenceLedger.render(entries, totalShown), mechanicalCheck,
                media.note(), media.refs());
    }

    /**
     * 证据装配（**无视觉通道**口径）：等价 {@link #buildEvidenceContent(SubTaskView, boolean)}
     * 传 {@code mediaVisible=false}。
     *
     * <p>保留此入口是「保守默认」：不知道本次用哪个模型时，一律按<b>看不到图</b>处理——
     * 那正是 `VU-2` 之前的行为，老调用点（含冻结快照复算路径）不改即零变化。
     * 想启用看图必须显式传能力判定结果，不允许「碰巧为真」。</p>
     */
    public EvidenceContent buildEvidenceContent(SubTaskView subTask) {
        return buildEvidenceContent(subTask, false);
    }

    /** 无正文注入时的台账：二进制标 not_textual，文本但不可读标 absent。 */
    private static String ledgerForNoContent(List<AttachmentView> attachments) {
        List<ReviewEvidenceLedger.Entry> entries = new ArrayList<>();
        for (AttachmentView att : attachments) {
            String name = att.fileName() != null ? att.fileName() : "unknown";
            if (!AttachmentContentPolicy.isTextual(att.mimeType(), att.fileName())) {
                entries.add(new ReviewEvidenceLedger.Entry(att.id(), name, 0,
                        ReviewEvidenceLedger.STATUS_NOT_TEXTUAL, "", 0, ""));
            } else {
                entries.add(new ReviewEvidenceLedger.Entry(att.id(), name, 0,
                        ReviewEvidenceLedger.STATUS_ABSENT, "", 0, "unreadable"));
            }
        }
        return ReviewEvidenceLedger.render(entries, 0);
    }

    /**
     * 预算分配（`RV-9`）：每文件保底 → 族均分剩余 → 族内均分 → 余量按容量再分配。
     *
     * <p>族键来自 {@link AttachmentFamilies}（与 `RV-10` 同一事实源）；顺序与正文/台账一致。</p>
     */
    private static Map<Long, Integer> allocate(List<AttachmentView> textFiles,
                                               Map<Long, String> contents) {
        Map<Long, Integer> shown = new HashMap<>();
        if (textFiles.isEmpty()) {
            return shown;
        }
        int fileCount = textFiles.size();
        int totalBudget = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT;
        // 总预算为硬上限：保底无法普惠（4,096×N 超越 200k）时降为等额分享 totalBudget/N，
        // 不再额外设 1_024 下限，否则 N>195 时仅保底即超过 200k（FROZEN_EVIDENCE_MAX_CHARS 被击穿）。
        int floor = fileCount * EVIDENCE_FILE_FLOOR_CHARS <= totalBudget
                ? EVIDENCE_FILE_FLOOR_CHARS : totalBudget / fileCount;
        int used = 0;
        for (AttachmentView att : textFiles) {
            int cap = Math.min(contents.get(att.id()).length(),
                    AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT);
            int initial = Math.min(cap, floor);
            shown.put(att.id(), initial);
            used += initial;
        }
        int pool = totalBudget - used;
        Map<String, List<AttachmentView>> groups = new LinkedHashMap<>();
        for (AttachmentView att : textFiles) {
            groups.computeIfAbsent(AttachmentFamilies.familyKeyOf(att.fileName()),
                    ignored -> new ArrayList<>()).add(att);
        }
        if (groups.isEmpty() || pool <= 0) {
            return shown;
        }
        int familyShare = pool / groups.size();
        for (List<AttachmentView> group : groups.values()) {
            int base = familyShare / group.size();
            int remainder = familyShare % group.size();
            for (int i = 0; i < group.size(); i++) {
                AttachmentView att = group.get(i);
                int want = base + (i < remainder ? 1 : 0);
                pool -= addUpTo(att, want, contents, shown);
            }
        }
        while (pool > 0) {
            int capacityCount = 0;
            for (AttachmentView att : textFiles) {
                if (capacityOf(att, contents, shown) > 0) {
                    capacityCount++;
                }
            }
            if (capacityCount == 0) {
                break;
            }
            int chunk = Math.max(1, pool / capacityCount);
            int added = 0;
            for (AttachmentView att : textFiles) {
                added += addUpTo(att, chunk, contents, shown);
            }
            pool -= added;
            if (added == 0) {
                break;
            }
        }
        return shown;
    }

    private static int addUpTo(AttachmentView att, int want, Map<Long, String> contents,
                              Map<Long, Integer> shown) {
        if (want <= 0) {
            return 0;
        }
        int added = Math.min(want, capacityOf(att, contents, shown));
        if (added > 0) {
            shown.put(att.id(), shown.get(att.id()) + added);
        }
        return added;
    }

    private static int capacityOf(AttachmentView att, Map<Long, String> contents,
                                  Map<Long, Integer> shown) {
        int cap = Math.min(contents.get(att.id()).length(),
                AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT);
        return Math.max(0, cap - shown.getOrDefault(att.id(), 0));
    }

    private static String truncationReason(int originalChars, int shown) {
        if (originalChars > AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT
                && shown >= AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT) {
            return "per_file_limit";
        }
        if (originalChars <= AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT) {
            return "total_limit";
        }
        return "evidence_budget";
    }

    private static void appendOmission(StringBuilder sb, Long attachmentId, int omitted, int offset) {
        sb.append("[... 剩余 ").append(Math.max(0, omitted))
                .append(" 字符未注入；可用 internal_read_attachment_range id=").append(attachmentId)
                .append(" offset=").append(offset)
                .append(" length=4096 读取 ...]\n");
    }

    /**
     * 证据顺序（`RV-9`）：族级排序键 `!hasMain → maxPart 降序 → 族名升序`（含主件的族整体排前，
     * 族间仍按分片数降序）；族内按主件优先、分片编号升序、文件名升序。
     *
     * <p>与实施计划 §`RV-9`「顺序与台账」逐字同源，改这里必须同批改计划（已于  分叉过一次）。</p>
     */
    private static List<AttachmentView> priorityOrder(List<AttachmentView> attachments) {
        Map<String, List<AttachmentView>> groups = new LinkedHashMap<>();
        for (AttachmentView att : attachments) {
            groups.computeIfAbsent(AttachmentFamilies.familyKeyOf(att.fileName()),
                    ignored -> new ArrayList<>()).add(att);
        }
        List<FamilyGroup> families = new ArrayList<>();
        for (Map.Entry<String, List<AttachmentView>> group : groups.entrySet()) {
            List<AttachmentView> files = new ArrayList<>(group.getValue());
            boolean hasMain = false;
            int maxPart = 0;
            for (AttachmentView att : files) {
                AttachmentFamilies.Membership membership = AttachmentFamilies.parse(att.fileName());
                hasMain |= membership.main();
                maxPart = Math.max(maxPart, membership.partIndex());
            }
            files.sort(Comparator
                    .comparing((AttachmentView att) -> !AttachmentFamilies.parse(att.fileName()).main())
                    .thenComparingInt(att -> AttachmentFamilies.parse(att.fileName()).partIndex())
                    .thenComparing(att -> safeName(att)));
            families.add(new FamilyGroup(group.getKey(), files, hasMain, maxPart));
        }
        families.sort(Comparator
                .comparing((FamilyGroup group) -> !group.hasMain())
                .thenComparing(Comparator.comparingInt(FamilyGroup::maxPart).reversed())
                .thenComparing(FamilyGroup::key));
        List<AttachmentView> ordered = new ArrayList<>();
        for (FamilyGroup family : families) {
            ordered.addAll(family.files());
        }
        return ordered;
    }

    private static String safeName(AttachmentView att) {
        return att.fileName() == null ? "" : att.fileName();
    }

    private record FamilyGroup(String key, List<AttachmentView> files, boolean hasMain, int maxPart) {
    }

    /** 附件段标题行。 */
    private void appendAttachmentContentHeader(StringBuilder sb, AttachmentView att) {
        String size = att.fileSize() != null ? att.fileSize() + " bytes" : "?";
        String type = att.fileType() != null ? att.fileType() : "other";
        sb.append("### ").append(att.fileName())
                .append("（").append(type).append("，").append(size).append("）\n");
    }

    /**
     * 提取 Markdown 章节结构大纲（R2 修复）：扫描全文的行首 {@code #} 标题，
     * 生成缩进的「章节树」，供超限截断后追加，让 Reviewer 知道后半段还有哪些章节。
     *
     * <p>仅提取标题行本身（每行极短），整体开销可控（通常几十~上百字符），
     * 不占用附件正文限额之外的实质预算；无标题的纯文本/代码文件返回 {@code null}
     * （保持原「从头截断」行为不变）。</p>
     *
     * @param fullContent 附件全文（截断前）
     * @return 结构大纲块（含前导空行），无可提取标题时返回 {@code null}
     */
    private String buildStructureOutline(String fullContent) {
        if (fullContent == null || fullContent.isEmpty()) {
            return null;
        }
        StringBuilder outline = new StringBuilder();
        int headingCount = 0;
        for (String line : fullContent.split("\n", -1)) {
            int level = 0;
            int idx = 0;
            while (idx < line.length() && line.charAt(idx) == '#') {
                level++;
                idx++;
            }
            // 仅认行首连续 # 后跟空格的 ATX 标题（# 空格 标题），忽略 # 之后非空格的（如 #include）
            if (level >= 1 && level <= 6 && idx < line.length() && line.charAt(idx) == ' ') {
                String title = line.substring(idx).trim();
                if (!title.isEmpty()) {
                    outline.append("\n").append("  ".repeat(level - 1))
                            .append("- ").append(title);
                    headingCount++;
                }
            }
        }
        if (headingCount == 0) {
            return null;
        }
        return "\n\n---\n（该文件后续章节结构，正文已截断）\n" + outline + "\n";
    }

    /** 读取可直读附件正文；不可读/为空返回 null（注入"内容不可读"标注，不中断整体注入）。 */
    private String readAttachmentContent(AttachmentView att) {
        try {
            byte[] bytes = attachmentService.loadContent(att.id());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("附件内容读取失败，仅注入清单: attachmentId={}, err={}", att.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 媒体作用域：<b>说明文本 + 图片引用一次算完</b>（`VU-2`）。
     *
     * <p><b>为什么是四态而不是两态</b>：把「有媒体附件」与「有能送进消息的图片」当成同一件事，
     * 会在「提交只有音频/视频」时说出「已带来图、可以直接看」——模型于是声称自己看过一段
     * 它根本没收到的东西，而这种假通过从 prompt 侧完全看不出来（不变量 I-4 禁假装看过）。</p>
     *
     * <ol>
     *   <li>无媒体附件 ⇒ 空串（「本来就没图」不需要任何说明，加了只是噪声）；</li>
     *   <li>有媒体但模型不支持图片 ⇒ <b>保留</b>「无法查看、从严核验」原文案：这是无视觉
     *       能力时唯一正确的行为，属**必须留着的降级路径**，不是可以删掉的历史遗留；</li>
     *   <li>有媒体、模型支持、但无一张可内联图片 ⇒ 明说「无可随消息提供的图片」；</li>
     *   <li>有媒体、模型支持、有可内联图片 ⇒ 「已随消息提供 N 张图，直接看，不要用工具读」。</li>
     * </ol>
     *
     * <p><b>「哪些算图片」复用 {@link AttachmentMediaAssembler#isInlineImage} 静态判据</b>，
     * 不在此另写一份扩展名表：装载器与说明若各判一套，漂移那天就是「说明说送了、实际没送」
     * （判据 4：同一事实双写必分叉）。</p>
     *
     * @param attachments  本次子任务的有效附件（非 null）
     * @param mediaVisible 本次核验所用模型是否声明可读图片
     */
    private MediaScope resolveMediaScope(List<AttachmentView> attachments, boolean mediaVisible) {
        if (attachments.isEmpty()) {
            return MediaScope.EMPTY;
        }
        List<AttachmentView> media = attachments.stream()
                .filter(att -> AttachmentContentPolicy.isMedia(att.mimeType(), att.fileName()))
                .toList();
        if (media.isEmpty()) {
            return MediaScope.EMPTY;
        }
        String names = media.stream()
                .map(att -> att.fileName() == null ? "unknown" : att.fileName())
                .collect(Collectors.joining("、"));
        String head = "本提交含 " + media.size() + " 个媒体附件（" + names + "）。";
        if (!mediaVisible) {
            return new MediaScope(head + "当前核验链路无法查看其原始内容；"
                    + "与之相关的文字声称请从严核验、评分保守。", List.of());
        }
        List<MediaRef> refs = media.stream()
                .filter(att -> AttachmentMediaAssembler.isInlineImage(att.mimeType(), att.fileName()))
                .map(att -> MediaRef.ofTaskAttachment(att.id(), att.fileName(), att.mimeType(), att.fileSize()))
                .toList();
        if (refs.isEmpty()) {
            return new MediaScope(head + "其中没有可随消息提供的图片（当前链路只支持图片输入，"
                    + "音频/视频不随消息发送）；相关声称按「未提供可核证据」从严判定。", List.of());
        }
        return new MediaScope(head + "平台已把其中 " + refs.size()
                + " 张图片作为多模态输入随本条消息一并提供，你可以直接查看图片内容，"
                + "不需要用任何工具读取。", refs);
    }

    /**
     * 从 context.lastExecution.output 提取执行产出，缺失时给出占位说明。
     */
    public String extractExecutionOutput(SubTaskView subTask) {
        String raw = extractRawOutput(subTask);
        if (!raw.isBlank()) {
            return summarize(raw, AttachmentContentPolicy.OUTPUT_SUMMARY_LIMIT);
        }
        return "（执行产出为空或缺失，请据交付物/验收标准审慎判定）";
    }

    /** 取执行产出原文（不截断），供围栏证据信号检测使用。 */
    public String extractRawOutput(SubTaskView subTask) {
        Map<String, Object> ctx = subTask.context();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output != null) {
                return output.toString();
            }
        }
        return "";
    }

    /**
     * 围栏证据信号：检测提交是否携带 VERIFICATION 段（基于截断前原文）。
     *
     * <p>仅检测不拦截——无证据提交不拒收，但注入"从严核验"指令，
     * 与 executor SKILL 的 fail-close 条款形成闭环。</p>
     */
    public String verificationSignal(String rawOutput) {
        // REF-8.4 ③：匹配器与 VerificationFence 共用单一来源，防「两份围栏判定」漂移
        boolean hasEvidence = VerificationFence.containsMarker(rawOutput);
        return hasEvidence
                ? "该提交携带验证证据（VERIFICATION 段）：请核对证据中命令/输出/结论与交付物的一致性，"
                        + "证据与结论矛盾或明显伪造的按不达标处理。"
                : "该提交未携带验证证据（无 VERIFICATION 段）：请从严核验、评分保守；"
                        + "仅凭产出文本无法确认满足验收标准时不得判 pass=true。";
    }

    /** 长文本摘要：超限截断并加省略号。 */
    private static String summarize(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "...";
    }
}
