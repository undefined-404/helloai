package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.PlanAttemptStatus;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.ConstraintRefKind;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.constant.UncertaintyKind;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.context.ArchetypeDimensionsSection;
import com.helloai.core.planner.context.DecomposeContextSnapshotRenderer;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.common.util.ExecutionEnv;
import com.helloai.core.planner.support.DraftCode;
import com.helloai.core.planner.support.PlanNodeId;
import com.helloai.core.planner.policy.AcceptanceSatisfiabilityChecker;
import com.helloai.core.planner.policy.PlannerGranularity;
import com.helloai.core.planner.policy.PlannerGranularityResolver;
import com.helloai.core.planner.policy.RequirementPackage;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlanDraftRoundService;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.support.ContractSlotNormalizer;
import com.helloai.core.planner.support.PlanDraftFindingsStore;
import com.helloai.core.planner.support.PlanDraftViewMapper;
import com.helloai.core.planner.support.PlanGateRunner;
import com.helloai.core.task.port.ConstraintRefDraft;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.port.PlannerAgentRef;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Planner 任务拆解异步执行服务实现。
 *
 * <p>承接拆解异步化改造前的 LLM 拆解段：选平台内 API_KEY_LLM Planner → 渲染 Prompt →
 * {@code executeSync} 调 LLM → 解析校验草案 → 写入暂存区 {@code task_plan_draft} →
 * 依赖序号回写真实 id → 记录 {@code task_plan_generated}。</p>
 *
 * <p>运行于专用线程池 {@code plannerDecomposeExecutor}（{@code @Async} 跨类代理生效，
 * 由 {@code PlannerAnalysisServiceImpl.decompose} 注入本接口提交，禁止同类自调用）。
 * 失败路径内部闭环：回退 PLANNING → PENDING + 记录 {@code task_plan_failed}，
 * LLM 调用失败记 ERROR、可恢复降级记 WARN（CODE_STYLE §14.2），日志均带 taskId 业务标识。</p>
 *
 * <p>新增 {@code task_plan_llm_call_end} timeline 事件（耗时毫秒、finishReason、tokenUsage），
 * 补齐原链路"只有 _start 没有 _end"的可观测缺口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerDecomposeAsyncServiceImpl implements PlannerDecomposeAsyncService {

    /** 单次拆解允许落库的草案数量上限（与 Prompt 模板的 3~10 约定对齐，服务端只做硬上限）。 */
    private static final int MAX_DRAFT_COUNT = 10;

    /** G-010 §5 防御：技能目录注入 Prompt 的条数上限（超限截断至前 N 项并提示，防提示词膨胀）。 */
    private static final int MAX_SKILL_CATALOG_SIZE = 20;

    /** timeline detail 中 LLM 原始输出摘要的截断长度。 */
    private static final int RAW_OUTPUT_SUMMARY_LIMIT = 500;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/planner-decompose.md";

    /** `TD-9` regenerate：单条草案同粒度重写专用模板。 */
    private static final String REGENERATE_PROMPT_TEMPLATE_PATH = "prompts/planner-regenerate.md";

    /**
     * 「执行环境未声明」的渲染标记。
     *
     * <p>措辞只说**缺什么**，不说**该怎么办**——处置口径唯一写在提示词第 12 条，
     * 两处都写会在下次改判据时只改一处（判据 4：同一事实双写必分叉）。</p>
     */
    private static final String ENV_UNDECLARED_MARKER =
            "- （未声明执行环境：代码仓库 / 运行时 / 数据源均未提供）";

    private static final Set<String> VALID_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlanGateRunner planGateRunner;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;
    private final AgentSkillSpecService agentSkillSpecService;
    private final DecomposeContextSnapshotRenderer contextSnapshotRenderer;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * 拆解尝试栅栏（`TD-16b`）：抢占 / 收尾 / 判悬挂。
     *
     * <p><b>它的所有调用都必须包 try-catch</b>：attempt 是**旁挂的可靠性设施**，
     * 它自己出错（表不存在、锁冲突、字段超长）绝不能把拆解主流程拖垮——
     * 主流程有它自己的回退与时间线，比 attempt 记录重要得多。</p>
     */
    private final PlanAttemptService planAttemptService;

    /**
     * 本次尝试的租约时长（分钟）。**必须小于 {@code planning-timeout-minutes}（默认 10）**——
     * 否则任务级巡检先动手回退，派发器的自愈永远轮不到。与 {@code PlanAttemptDispatcherImpl}
     * 用同一个配置键，两处默认值必须一致。
     */
    @Value("${helloai.planner.attempt-lease-minutes:7}")
    private int attemptLeaseMinutes;

    /** 拆解产出按 `code` 写入暂存区，为读侧切换做准备。 */
    private final PlanDraftRoundService planDraftRoundService;

    /** `TD-16d` `B4`：根层 code 的序号要取任务内已用过的最大值（跨轮不重置）。 */
    private final PlanDraftMapper planDraftMapper;

    /** `AD-5a-3b-2b`：暂存区是拆解产出的唯一落点，落库段直接读写它（不再写 `sub_task` 副本）。 */
    private final PlanDraftService planDraftService;

    /** 暂存区门禁结论写点（替代仅服务执行表的 `SubTaskGateFindingsStore`）。 */
    private final PlanDraftFindingsStore planDraftFindingsStore;

    /** 约束文档目录（`G-037`）：{{CONSTRAINT_DOC_CATALOG}} 的目录摘要来源（白名单 manifest）。 */
    private final com.helloai.core.shared.constraint.ConstraintDocCatalog constraintDocCatalog;

    /**
     * 拆解落库段的显式事务边界。
     *
     * <p><b>为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</b>：
     * {@code @Transactional} 加在 {@code doDecompose} 上会把 LLM 调用的十几分钟也圈进事务，
     * 长时间占用连接；拆成独立方法又会因同类自调用绕过代理而不生效。模板在这里是最直接的
     * 表达——「只把落库到状态推进这一段包起来」，边界写在代码里，不依赖 AOP 语义。</p>
     *
     * <p>与 {@code AssistantChatServiceImpl} 同构（那边也是「LLM 调用在事务外、
     * 会话落库在事务内」）。</p>
     */
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 取事务模板，未初始化时就地构造。
     *
     * <p>{@code @PostConstruct} 在生产环境一定会执行；这里的兜底是给**直接 new 的单元测试**
     * 用的（没有容器就没有生命周期回调）。两条路径构造的是同一种对象，不存在行为分叉。</p>
     */
    private TransactionTemplate txTemplate() {
        TransactionTemplate template = this.transactionTemplate;
        if (template == null) {
            template = new TransactionTemplate(transactionManager);
            this.transactionTemplate = template;
        }
        return template;
    }

    /**
     * 异步执行拆解（运行于 plannerDecomposeExecutor）。
     *
     * <p>入口幂等防御：仅当任务仍处 PLANNING 才执行——若已被 PlanningTimeoutTask 超时回收
     * 或用户已确认/拒绝，慢线程迟到时直接跳过，避免覆盖并发结果。</p>
     */
    @Async("plannerDecomposeExecutor")
    @Override
    public void executeDecompose(Long taskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null || task.status() != TaskStatus.PLANNING) {
            log.info("拆解异步执行跳过（任务不存在或已离开 PLANNING）: taskId={}, status={}",
                    taskId, task != null ? task.status() : null);
            return;
        }
        // attemptId 由拆解入口（beginDecompose）生成并落库，异步段只读不造。
        // 在这里现生成 UUID 等于「每次执行都是一件新事」，草案唯一索引永远不冲突，幂等失效。
        String attemptId = taskService.currentDecomposeAttempt(taskId);
        if (attemptId == null || attemptId.isBlank()) {
            // 入口必然写入 attemptId，走到这里说明是升级前的在途拆解或数据异常。
            // 不擅自补 UUID（会让幂等键失效），交给租约巡检回收后由用户重拆。
            log.warn("拆解跳过：PLANNING 任务缺少 attemptId，交由租约巡检回收: taskId={}", taskId);
            return;
        }
        // `TD-16b`：抢占本次尝试（Outbox 的执行侧）。旁挂设施，任何异常都不影响主流程。
        PlanAttempt attempt = claimAttemptQuietly(taskId, attemptId);

        try {
            doDecompose(task, attemptId, attempt);
            markAttemptSucceededQuietly(attempt, "decompose completed");
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底命中 = 这批草案已经落过（响应丢失后重投 / 补偿重跑 / 多节点并发）。
            // 这是**幂等语义的正常分支，不是失败**：绝不能走 rollbackToPending，否则会把
            // 那次已经成功的拆解回退成 PENDING，制造「草案在库 + task=PENDING」的死结。
            // 命中唯一索引**不等于**幂等命中。两种情形必须分开处置：
            //   ① 本批草案真的已落过（响应丢失后重投）⇒ 收敛状态，算成功；
            //   ② 冲突来自别处（暂存区残留行占位 / 上一轮未作废）⇒ **真实失败**，
            //      必须回退 PENDING 让用户能重试。
            // 原来①②一律走「不改状态」⇒ 任务永久停在 PLANNING，且用户看不到任何错误
            // （2026-11-08 23:13 真机复现：reject 后重拆，第一轮 9 条 ACTIVE 行占着
            //  `uk_plan_draft_code`，第二轮插入即冲突，任务卡死）。
            boolean idempotentHit = recoverAfterIdempotentHit(taskId, attemptId);
            if (!idempotentHit) {
                rollbackToPending(taskId, attemptId);
                // `TD-16e-2`：判定「不完整」之后必须清理，否则残留的那几条仍会被草案列表读出来，
                // 用户看到 3 条就以为这是全部（实际应有 7 条），一确认就把残缺批转正了。
                discardIncompleteBatch(taskId, attemptId);
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_FAILED.code(),
                        AgentRole.PLANNER, null, Map.of("error", "唯一索引冲突但非幂等命中（草案未落库）"));
                log.error("拆解落库冲突但库中无本批草案，判定为失败并回退 PENDING: taskId={}, attemptId={}",
                        taskId, attemptId, e);
                markAttemptFailedQuietly(attempt, e);
                return;
            }
            log.warn("拆解落库命中幂等唯一索引，判定为已落过（不视为失败）: taskId={}, attemptId={}",
                    taskId, attemptId);
            // 幂等命中 ⇒ 内容已在库里 ⇒ 本次尝试实质成功，不能标失败。
            markAttemptSucceededQuietly(attempt, "idempotent hit: drafts already persisted");
        } catch (Exception e) {
            rollbackToPending(taskId, attemptId);
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_FAILED.code(),
                    AgentRole.PLANNER, null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            log.error("任务拆解失败，已回退 PENDING: taskId={}, attemptId={}", taskId, attemptId, e);
            markAttemptFailedQuietly(attempt, e);
        }
    }

    // ── `TD-16b` 尝试栅栏的旁挂操作（全部静默失败，不干扰主流程）──────────────

    /** 单条 attempt 的自动重试上限：到顶转终态，否则稳定坏掉的 provider 会被无限重试白烧额度。 */
    private static final int MAX_ATTEMPT_NO = 3;

    /**
     * 抢占本次尝试。返回 {@code null} 表示「没有可用的 attempt 记录」——
     * 升级前的在途拆解就是这种形态，此时跳过栅栏、由既有租约巡检兜底。
     *
     * <p>**不补造记录**：补造会让「当前有效尝试」与真实执行脱节（补出来的行没人 claim，
     * 派发器会以为有一份活没人干，重复触发）。</p>
     */
    private PlanAttempt claimAttemptQuietly(Long taskId, String attemptId) {
        try {
            PlanAttempt a = planAttemptService.findLatestByBatchKey(attemptId).orElse(null);
            if (a == null) {
                log.debug("拆解尝试记录不存在（升级前在途或旁挂失败），跳过栅栏: taskId={}, attemptId={}",
                        taskId, attemptId);
                return null;
            }
            if (a.getStatus() == PlanAttemptStatus.PENDING) {
                boolean claimed = planAttemptService.claim(a.getId(), PlanNodeId.current(),
                        OffsetDateTime.now().plusMinutes(attemptLeaseMinutes));
                if (!claimed) {
                    // 已被派发器或另一节点抢走：本次执行仍然继续（草案落库有唯一索引兜底），
                    // 但收尾时会因 attempt_no 不匹配而 CAS 落空 —— 正是设计要的效果。
                    log.warn("拆解尝试已被他处抢占，本次仍继续但收尾将被栅栏拦截: taskId={}, attemptId={}",
                            taskId, attemptId);
                    return a;
                }
                a.setStatus(PlanAttemptStatus.RUNNING);
            }
            return a;
        } catch (Exception e) {
            log.warn("拆解尝试抢占异常（不影响主流程）: taskId={}, attemptId={}, err={}",
                    taskId, attemptId, e.getMessage());
            return null;
        }
    }

    private void markAttemptSucceededQuietly(PlanAttempt attempt, String note) {
        if (attempt == null || attempt.getAttemptNo() == null) {
            return;
        }
        try {
            if (!planAttemptService.succeed(attempt.getId(), attempt.getAttemptNo(), null, note)) {
                log.warn("拆解尝试收尾 CAS 落空（本次尝试先前已被判失效/被接管）: attemptId={}, attemptNo={}",
                        attempt.getId(), attempt.getAttemptNo());
            }
        } catch (Exception e) {
            log.warn("拆解尝试成功收尾异常（不影响主流程）: attemptId={}, err={}",
                    attempt.getId(), e.getMessage());
        }
    }

    private void markAttemptFailedQuietly(PlanAttempt attempt, Exception cause) {
        if (attempt == null || attempt.getAttemptNo() == null) {
            return;
        }
        try {
            String reason = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            if (reason.length() > 400) {
                reason = reason.substring(0, 400);
            }
            if (attempt.getAttemptNo() >= MAX_ATTEMPT_NO) {
                planAttemptService.failTerminal(attempt.getId(), attempt.getAttemptNo(),
                        "重试已达上限(" + MAX_ATTEMPT_NO + "): " + reason);
            } else {
                // 默认判可重试：解析失败与网络抖动在异常类型上常常长得一样，
                // 判错方向的代价不对称——多试一次只是多花钱，少试一次是任务永久卡住。
                // 由 MAX_ATTEMPT_NO 兜住「稳定失败」的无限重试。
                planAttemptService.failRetryable(attempt.getId(), attempt.getAttemptNo(),
                        OffsetDateTime.now(), reason);
            }
        } catch (Exception e) {
            log.warn("拆解尝试失败收尾异常（不影响主流程）: attemptId={}, err={}",
                    attempt.getId(), e.getMessage());
        }
    }

    /**
     * 幂等命中后的状态收敛：把「草案已在库但仍 PLANNING」推回待确认。
     *
     * <p>草案已经落过，说明上一次执行跑到了落库段之后，只是响应/状态推进没走完。
     * 此时唯一正确的动作是补一次状态推进，而不是重跑——重跑会再落一批草案（被唯一索引挡），
     * 或者在新 attempt 下再落一批（重复内容）。</p>
     */
    private boolean recoverAfterIdempotentHit(Long taskId, String attemptId) {
        try {
            // 粗筛按**本批**数：撞唯一索引证明的是「本批有行」，而任务内可能还躺着
            // 上一轮残留（别的 batchKey）。按任务数会把「本批一条没落、但上一轮有行」
            // 读成「本批已落过」（`TD-16e-2` 修）。
            long drafts = planDraftMapper.countByBatchKeyIncludeDeleted(attemptId);
            if (drafts <= 0) {
                // 冲突来自别处（暂存区残留 / 展开撞号等）⇒ **不是**幂等命中，
                // 调用方必须按失败处置。这里只判定，不替调用方改状态。
                log.warn("唯一索引冲突但库中无待确认草案 ⇒ 不是幂等命中: taskId={}, attemptId={}",
                        taskId, attemptId);
                return false;
            }
            // `TD-16e-2`：从「存在」升级为「完整」。有草案只证明落了东西，不证明落全了；
            // 判据是「已消费的充分条件是内容完整，不是存在」（设计 §5.6）。
            String incomplete = describeIncompleteBatch(attemptId);
            if (incomplete != null) {
                log.warn("唯一索引冲突且本批草案不完整 ⇒ 判失败（残缺批次不得当成已消费）: "
                        + "taskId={}, attemptId={}, {}", taskId, attemptId, incomplete);
                return false;
            }
            boolean advanced = taskService.casStatusForAttempt(taskId, TaskStatus.PLANNING,
                    TaskStatus.AWAITING_PLAN_CONFIRM, attemptId);
            log.info("幂等命中已收敛状态: taskId={}, attemptId={}, draftCount={}, advanced={}",
                    taskId, attemptId, drafts, advanced);
            if (advanced) {
                taskTimelineService.recordEvent(taskId, null,
                        TimelineEventCode.TASK_PLAN_GENERATED.code(),
                        AgentRole.PLANNER, null,
                        Map.of("draftCount", drafts, "idempotentHit", true, "attemptId", attemptId));
            }
            return true;
        } catch (Exception e) {
            // 判不出来就按失败处置：让用户能重试，好过静默停在 PLANNING
            log.error("幂等命中后收敛状态失败，按失败处置: taskId={}, attemptId={}",
                    taskId, attemptId, e);
            return false;
        }
    }

    /**
     * 判定「本批草案是否落全」（`TD-16e-2`，设计 §5.6）。
     *
     * <p><b>判据</b>：暂存区里该 {@code batch_key} 的行数 == {@code attempt.expected_count}。</p>
     *
     * <p><b>为什么数「含软删」而不是只数 {@code deleted=0}</b>：设计 §5.6 的伪代码写的是
     * {@code deleted=0}，那是按「撞键只可能发生在落库瞬间」写的。实际恢复路径还包含
     * 「落库成功 → 状态推进丢失 → 重投」这条序列，其间批次可能已被确认（{@code CONFIRMED}
     * + {@code deleted=1}）。只数存活行会把「落全了但已推进」读成残缺 ⇒ 反过来制造砖化，
     * 与判据的目的正好相反。故此处口径订正为**该事件累计写了几行**
     * （{@code countByBatchKeyIncludeDeleted}），与 {@code expected_count} 同维度。</p>
     *
     * <p><b>两处 fail-open</b>（判不了就放行，维持升级前的存在性语义）：</p>
     * <ol>
     *   <li>{@code expected_count} 为 {@code null}：V146 加列前登记的尝试行。升级当天所有
     *       在途任务都是这种形态，判不到位就把它们全判残缺会一次性砖化一批任务。</li>
     *   <li>暂存区行数为 0：V147 之前落库的批次未写暂存区。此时「暂存区 0 行」只能说明没写，
     *       不能说明没落库——拿它当判据会把每一个存量批次判成残缺。</li>
     * </ol>
     *
     * @return {@code null} = 完整或判不了（放行）；非 {@code null} = 残缺说明（用方据此判失败）
     */
    private String describeIncompleteBatch(String batchKey) {
        Integer expected = planAttemptService.findLatestByBatchKey(batchKey)
                .map(PlanAttempt::getExpectedCount)
                .orElse(null);
        if (expected == null || expected <= 0) {
            log.debug("本批无期望条数（V146 前的存量尝试 / 本次未写入），完整性判据退回存在性: batchKey={}",
                    batchKey);
            return null;
        }
        long mirrored = planDraftMapper.countByBatchKeyIncludeDeleted(batchKey);
        if (mirrored <= 0) {
            log.debug("本批暂存区为空（V147 前落库，未写暂存区），完整性判据退回存在性: batchKey={}", batchKey);
            return null;
        }
        if (mirrored == expected) {
            return null;
        }
        return "expectedCount=" + expected + ", mirroredCount=" + mirrored;
    }

    /**
     * 落库前记录本批期望条数（`TD-16e-2`）。任何异常都不影响主流程。
     *
     * <p>写在事务内、{@code saveDrafts} 之前：异常回滚时它与草案一起消失，不会留下
     * 「记了 7 条、一条没落」的错判依据。</p>
     */
    private void recordExpectedCountQuietly(PlanAttempt attempt, int expectedCount) {
        if (attempt == null || attempt.getId() == null || attempt.getAttemptNo() == null) {
            // 升级前的在途拆解没有 attempt 行：完整性判据本就会退回存在性，无需造记录。
            // 不补造行的理由同 {@link #claimAttemptQuietly}——补出来的行没人 claim。
            return;
        }
        try {
            if (!planAttemptService.recordExpectedCount(attempt.getId(), attempt.getAttemptNo(), expectedCount)) {
                log.warn("拆解期望条数未写入（本次尝试的 attempt_no 已失效，收尾将被栅栏拦截）: "
                                + "attemptId={}, attemptNo={}, expectedCount={}",
                        attempt.getId(), attempt.getAttemptNo(), expectedCount);
            }
        } catch (Exception e) {
            log.warn("拆解期望条数写入异常（不影响主流程，完整性判据退回存在性）: attemptId={}, err={}",
                    attempt.getId(), e.getMessage());
        }
    }

    /**
     * 作废某次尝试可能落下的残缺草案（`TD-16e-2`）。
     *
     * <p>`AD-5a-3b-2b` 起草案只存在暂存区，清理即「本批 {@code DISCARDED} + 软删」。</p>
     *
     * <p><b>失败只记 ERROR 不抛</b>：调用点已在失败分支（任务即将退回 {@code PENDING}），
     * 清理失败不该把「任务可重拆」这个更好的结果也弄丢。残留行不会挡住下一次拆解——
     * 根层 {@code code} 的序号取「任务内已用最大值 + 1」，会自然跳号。</p>
     */
    private void discardIncompleteBatch(Long taskId, String attemptId) {
        try {
            txTemplate().executeWithoutResult(status -> {
                int discarded = planDraftRoundService.discardBatch(attemptId);
                log.warn("已作废残缺草案批次: taskId={}, attemptId={}, discarded={}",
                        taskId, attemptId, discarded);
            });
        } catch (Exception e) {
            log.error("作废残缺草案批次失败（残留行不挡下次拆解，根层 code 会跳号）: "
                    + "taskId={}, attemptId={}", taskId, attemptId, e);
        }
    }


    /**
     * `AD-5a-3b-2b` 暂存区版重生成：草案没有 {@code sub_task} 副本时，四要素直接写暂存区。
     *
     * <p>与 {@link #regenerateById} 共用 LLM 段（{@link #generateRegenerated}），差异只在
     * 落点：这里调 {@code PlanDraftService.updateByCode}，写前跑同一条单草案门禁。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTaskView regenerateStaged(Long taskId, String code) {
        PlanDraftView staged = planDraftService.getByCode(taskId, code);
        if (staged == null) {
            throw new BizException(400, "草案不存在或已确认: taskId=" + taskId + ", code=" + code);
        }
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException(400, "草案所属任务不存在: " + taskId);
        }
        SubTaskView draft = PlanDraftViewMapper.toSubTaskViews(List.of(staged)).get(0);
        log.info("暂存区草案重生成开始: taskId={}, code={}, title={}", taskId, code, draft.title());
        PlannerAgentRef planner = plannerAgentPicker.pickForTask(taskId);
        Regenerated regenerated = generateRegenerated(task, draft, planner);
        planDraftService.updateByCode(taskId, code, regenerated.content(), regenerated.deliverable(),
                regenerated.acceptance(), null, null, null, regenerated.acceptanceKind());
        PlanDraftView updated = planDraftService.getByCode(taskId, code);
        taskTimelineService.recordEvent(taskId, null,
                TimelineEventCode.TASK_PLAN_DRAFT_REGENERATED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("draftCode", code, "title", draft.title()));
        log.info("暂存区草案重生成完成: taskId={}, code={}, title={}", taskId, code, draft.title());
        return PlanDraftViewMapper.toSubTaskViews(List.of(updated)).get(0);
    }

    /** 重生成的 LLM 段（两个落点共用）：渲染 → 调用 → 解析 → 不变量白名单。 */
    private Regenerated generateRegenerated(TaskView task, SubTaskView draft, PlannerAgentRef planner) {
        String prompt = renderRegeneratePrompt(task, draft, planner);
        AgentResult result = executeLlmForSingleDraft(planner, prompt, draft.taskId(), "planner_regenerate");
        // 输出为单条 JSON 对象（非数组），解析为单条 PlanDraftItem
        PlanDraftItem item = parseSingleRegenerateOutput(draft.taskId(), planner, result.getOutput());
        // 不变量白名单：只取四要素；title / dependsOn 用原草案值（LLM 输出被忽略）
        return new Regenerated(
                requireChanged(item.getContent(), draft.content(), "content"),
                requireChanged(item.getDeliverable(), draft.deliverable(), "deliverable"),
                requireChanged(item.getAcceptance(), draft.acceptance(), "acceptance"),
                item.getAcceptanceKind());
    }

    /** 重生成的四要素（不变量白名单之外一律不取）。 */
    private record Regenerated(String content, String deliverable, String acceptance,
                               String acceptanceKind) {
    }

    /**
     * `TD-11`：重生成改写了验收文本时，清掉按旧文本算出的 {@code acceptancePreconditions}。
     *
     * <p>为什么是「清」而不是「重算」：重算需要归一化后的 uncertainties（含 {@code ref}）与
     * dependsOn 的上游文本，而 {@code SubTaskView} 目前不带 {@code ref}；为一条低频路径扩
     * 快照契约的收益不足。**清掉 = 回落到「无标注」= 核验侧不豁免 = 保持现状严格**，
     * 这正是计划 §6 风险 8 定下的 fail-safe 方向。要恢复标注，重新拆解一次即可。</p>
     */
    private void clearStaleAcceptancePreconditions(Long subTaskId, SubTaskView draft, String newAcceptance) {
        Map<String, Object> ctx = draft.context();
        if (ctx == null || !ctx.containsKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS)) {
            return;
        }
        String oldAcceptance = draft.acceptance() == null ? "" : draft.acceptance().trim();
        if (oldAcceptance.equals(newAcceptance == null ? "" : newAcceptance.trim())) {
            return;
        }
        try {
            Map<String, Object> updated = new HashMap<>(ctx);
            updated.remove(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
            subTaskService.updateContext(subTaskId, updated);
            log.info("验收文本已改写，清除 TD-11 旧标注（回落为不豁免）: subTaskId={}", subTaskId);
        } catch (Exception e) {
            log.warn("清除 TD-11 旧标注失败（不阻断重生成）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * regenerate 不变量白名单：LLM 输出的四要素若为空/blank，保留原值（不因 LLM 少字段而清空）。
     */
    private static String requireChanged(String llmValue, String original, String field) {
        return llmValue != null && !llmValue.isBlank() ? llmValue : original;
    }

    /** 渲染 regenerate 提示词。 */
    private String renderRegeneratePrompt(TaskView task, SubTaskView draft, PlannerAgentRef planner) {
        ClassPathResource resource = new ClassPathResource(REGENERATE_PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到重生成 Prompt 模板: " + REGENERATE_PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取重生成 Prompt 模板失败: " + e.getMessage());
        }
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        String skills = draft.requiredSkills() != null && !draft.requiredSkills().isEmpty()
                ? String.join(", ", draft.requiredSkills()) : "（草案未指派技能）";
        String constraints = draft.constraints() != null && !draft.constraints().isBlank()
                ? draft.constraints() : "（草案无约束）";
        return template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_ARCHETYPE}}", archetype.name())
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述）")
                .replace("{{DRAFT_TITLE}}", draft.title() != null ? draft.title() : "")
                .replace("{{DRAFT_CONTENT}}", draft.content() != null ? draft.content() : "")
                .replace("{{DRAFT_DELIVERABLE}}", draft.deliverable() != null ? draft.deliverable() : "")
                .replace("{{DRAFT_ACCEPTANCE}}", draft.acceptance() != null ? draft.acceptance() : "")
                .replace("{{DRAFT_ACCEPTANCE_KIND}}",
                        draft.acceptanceKind() != null ? draft.acceptanceKind().name() : "AUTOMATED")
                .replace("{{DRAFT_PRIORITY}}", draft.priority() != null ? draft.priority() : "MEDIUM")
                .replace("{{DRAFT_SKILLS}}", skills)
                .replace("{{DRAFT_CONSTRAINTS}}", constraints)
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{CONSTRAINT_DOC_CATALOG}}", renderConstraintDocCatalog())
                .replace("{{CONSTRAINT_DEFAULT_SET}}", renderConstraintDefaultSet(archetype))
                .replace("{{TASK_CONTEXT_SNAPSHOT}}", renderSnapshotSafely(task))
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task))
                .replace("{{EXECUTION_ENV}}", renderExecutionEnv(task));
    }

    /** 单条草案 LLM 调用（regenerate 专用；不记 TASK_PLAN_LLM_CALL_START 事件，用轻量日志）。 */
    private AgentResult executeLlmForSingleDraft(PlannerAgentRef planner, String prompt, Long taskId,
                                                 String scene) {
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("taskId", taskId, "subTaskScene", scene))
                .requiredCapabilities(Map.of())
                .skills(List.of())
                .build();
        long startMs = System.currentTimeMillis();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        long costMs = System.currentTimeMillis() - startMs;
        if (!result.isSuccess()) {
            throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
        }
        log.info("单条草案 LLM 调用完成: taskId={}, scene={}, costMs={}, success={}",
                taskId, scene, costMs, result.isSuccess());
        return result;
    }

    /** regenerate 输出解析：单条 JSON 对象 → PlanDraftItem（必填四要素 + acceptanceKind 校验）。 */
    private PlanDraftItem parseSingleRegenerateOutput(Long taskId, PlannerAgentRef planner, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("Planner LLM 返回内容为空");
        }
        String cleaned = rawOutput.trim();
        // 兼容 markdown fence 包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        PlanDraftItem item;
        try {
            item = objectMapper.readValue(cleaned, PlanDraftItem.class);
        } catch (Exception e) {
            throw new BizException("Planner LLM 重生成输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (item == null) {
            throw new BizException("Planner LLM 未输出草案内容");
        }
        String missing = missingRequiredField(item);
        if (missing != null) {
            // 失败路径审计：regenerateById 带事务，共用事务会被随后的抛出回滚
            taskTimelineService.recordEventInNewTx(taskId, null,
                    TimelineEventCode.TASK_PLAN_DRAFT_FIELD_MISSING.code(),
                    AgentRole.PLANNER, planner.id(),
                    Map.of("draftSeq", 1, "field", missing,
                            "title", item.getTitle() != null ? item.getTitle() : "",
                            "rawOutputSummary", summarize(rawOutput)));
            throw new BizException("重生成结果缺少 " + missing + " 字段; 原始输出摘要: "
                    + summarize(rawOutput));
        }
        // acceptanceKind / 字符下限不再在此单独判定：写入走 updateDraftContent，
        // 那里用同一份 DraftGateChecker 判定（kind + 长度 + 空白），失败即回滚。
        return item;
    }

    /**
     * LLM 拆解主流程。**模型调用在事务外，落库到状态推进在同一事务内。**
     *
     * <p>为什么要这条边界：改前 {@code saveDrafts} 之后的五步（重加载 / 依赖回写 / 契约归一 /
     * 门禁落结论 / 状态推进）各自独立，任何一步抛异常都会留下「半批草案」——而失败分支只回退
     * 状态、不清草案，确定性地产出「草案在库 + task=PENDING」的死结（confirm 与 re-decompose
     * 双向拒绝）。事务化之后，「一半」这种状态在库里不可能存在，重跑因此天然安全。</p>
     *
     * <p>为什么不用 {@code @Transactional}：那会把上面十几分钟的模型调用也圈进事务。
     * 见 {@link #transactionTemplate} 字段注释。</p>
     */
    private void doDecompose(TaskView task, String attemptId, PlanAttempt attempt) {
        Long taskId = task.id();
        PlannerAgentRef planner = plannerAgentPicker.pickForTask(taskId);
        // G-010：粒度判定提前到主流程，renderPrompt 渲染与 buildDrafts 的 COARSE 校验共用一次判定
        PlannerGranularityResolver.GranularityDecision decision = granularityDecision(task);
        // `G-042`：附件清单与 prompt 必须同一次产出。清单写进 prompt 后 Planner 要跑十几分钟，
        // 若回来再查一次库把序号解析成附件，这期间用户新传的图会插到倒序结果头部，
        // 同一个序号就指向了另一份文件（判据 32：作用域与产出一次算完）。
        RenderedDecomposePrompt renderedPrompt = renderPrompt(task, decision);
        String prompt = renderedPrompt.prompt();

        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("taskId", taskId, "scene", "planner_decompose"))
                .requiredCapabilities(Map.of())
                // G-016 契约层技能注入：与任务级 required_skills 同源（null 防御为 List.of()）
                .skills(task.requiredSkills() != null ? task.requiredSkills() : List.of())
                .build();
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_LLM_CALL_START.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("agentId", planner.id(), "agentName", planner.name()));
        long startMs = System.currentTimeMillis();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
        long costMs = System.currentTimeMillis() - startMs;
        recordLlmCallEnd(taskId, planner, result, costMs);
        if (!result.isSuccess()) {
            throw new BizException("Planner LLM 调用失败: " + result.getErrorMessage());
        }

        List<PlanDraftItem> items = parseDraftItems(taskId, planner, result.getOutput());
        // `B4`：根层 code 的序号从「任务内已用过的最大根段序号 + 1」起，跨轮不重置。
        // 每轮从 0001 重起会与上一轮（reject/废除后可能仍软删在库）的行同号——
        // 撞 `uk_plan_draft_code`，且冲突会被上层误判成幂等命中。
        int rootSeqStart = planDraftMapper.findMaxRootSeq(taskId) + 1;
        List<SubTaskDraft> drafts = buildDrafts(task, items, planner, decision, attemptId, rootSeqStart,
                renderedPrompt.attachmentListing());

        // ══ 事务边界：从这里到状态推进，要么全成功要么全回滚 ══
        PersistOutcome outcome = txTemplate().execute(
                status -> persistPlan(taskId, attemptId, items, drafts, planner, task.createTime(),
                        attempt, task.taskArchetype()));
        if (outcome == null) {
            throw new BizException("拆解落库事务未返回结果（不应发生）: taskId=" + taskId);
        }

        // 以下是**审计与日志**：不参与事务。timeline 走 REQUIRES_NEW，放在事务内会额外占用一条
        // 连接（长事务 + 独立事务嵌套是连接池的常见打满方式）。
        if (!outcome.demotedCodes().isEmpty()) {
            taskTimelineService.recordEvent(taskId, null,
                    TimelineEventCode.TASK_PLAN_CONTRACT_SLOT_NORMALIZED.code(),
                    AgentRole.PLANNER, planner.id(), Map.of("demotedDraftCodes", outcome.demotedCodes()));
        }
        planGateRunner.recordTimeline(taskId, planner.id(), "decompose", outcome.drafts(), outcome.gate());
        if (outcome.gate().hasBlocking()) {
            log.warn("拆解草案已落库但未通过门禁（不报废，待修正后确认）: taskId={}, block={}, warn={}",
                    taskId, outcome.gate().blocking().size(), outcome.gate().warnings().size());
        }

        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_GENERATED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("agentId", planner.id(),
                        "agentName", planner.name(),
                        "draftCount", outcome.drafts().size(),
                        "gateBlockCount", outcome.gate().blocking().size(),
                        "attemptId", attemptId,
                        "rawOutputSummary", summarize(result.getOutput())));
        log.info("任务拆解草案生成: taskId={}, plannerAgentId={}, draftCount={}, gateBlock={}, attemptId={}",
                taskId, planner.id(), outcome.drafts().size(),
                outcome.gate().blocking().size(), attemptId);
    }

    /** 落库段产出（事务内算完、事务外只读，避免上下文外读取延迟加载）。 */
    private record PersistOutcome(List<SubTaskView> drafts,
                                  DraftGateChecker.Result gate,
                                  List<String> demotedCodes) {
    }

    /**
     * 事务体内的全部写动作：暂存区落库 → 依赖回写 → 契约归一 → 门禁结论 → 状态推进。
     *
     * <p><b>`AD-5a-3b-2b` 起不再写 `sub_task` 草案副本</b>：草案的唯一事实源是
     * `task_plan_draft`，门禁/依赖/契约归一全部作用在暂存区行上，执行行只在确认时
     * 由 `materializeFromStaging` 物化。这样「草案」与「执行任务」在库里彻底分开，
     * 确认前不再存在两套可能漂移的 id/内容。</p>
     *
     * <p><b>状态推进必须在事务内</b>：草案与「任务已进入待确认」是同一个事实的两面。
     * 分开提交就会出现「草案在库但任务仍 PLANNING」（用户看不到入口）或反过来
     * （任务待确认但草案没落成），两者都是死结。</p>
     */
    private PersistOutcome persistPlan(Long taskId, String attemptId, List<PlanDraftItem> items,
                                       List<SubTaskDraft> drafts, PlannerAgentRef planner,
                                       OffsetDateTime taskCreateTime, PlanAttempt attempt,
                                       TaskArchetype archetype) {
        // `TD-16e-2`：期望条数必须在**任何草案行写入之前**落库，且与草案同一事务
        // （回滚时一起消失，不会留下「记了 7 条、一条没落」的错判依据）。
        recordExpectedCountQuietly(attempt, drafts.size());
        planDraftRoundService.writeDrafts(taskId, attemptId, taskCreateTime, drafts);
        List<PlanDraftView> staged = planDraftService.listByTask(taskId);
        // 幂等重投时 writeDrafts 整批跳过，这里读到的是同一批已落行；条数必须一致，
        // 否则说明「本轮暂存区混入了别的批次」，带着错位输入跑门禁比失败更危险。
        if (staged.size() != drafts.size()) {
            throw new BizException("拆解草案落库条数不符: expected=" + drafts.size()
                    + ", actual=" + staged.size() + ", taskId=" + taskId);
        }
        List<DraftGateChecker.Finding> depAnomalies = applyDependsOn(taskId, staged, items);
        staged = planDraftService.listByTask(taskId);
        List<String> demotedCodes = normalizeContractSlots(taskId, staged);
        staged = planDraftService.listByTask(taskId);
        List<SubTaskView> views = PlanDraftViewMapper.toSubTaskViews(staged);
        DraftGateChecker.Result gate = planGateRunner.merge(
                planGateRunner.evaluate(views, archetype), depAnomalies);
        planDraftFindingsStore.persist(taskId, staged, gate);
        advanceAfterDecompose(taskId, attemptId);
        return new PersistOutcome(views, gate, demotedCodes);
    }

    /**
     * 拆解完成后的状态推进，含**竞态分流**。
     *
     * <p>CAS 失败有四种现实原因，处置各不相同：</p>
     * <ul>
     *   <li><b>已被租约巡检回收成 PENDING</b>（模型跑得比租约久）⇒ 草案完整可用，
     *       纠正回待确认。<b>不能作废</b>——用户等了十几分钟，AI 成果不该白扔；</li>
     *   <li><b>已被新一轮拆解取代</b>（attemptId 变了，状态仍是 PLANNING）⇒ 抛异常回滚，
     *       否则本批草案会与新一轮那批同时入库；</li>
     *   <li><b>用户点了停止任务 → CANCELLED</b> ⇒ 抛异常让整批草案回滚（用户已放弃）；</li>
     *   <li><b>已经是待确认</b>（重投/并发）⇒ 幂等返回。</li>
     * </ul>
     *
     * <p><b>两次 CAS 都带 attemptId 比对</b>（{@link TaskService#casStatusForAttempt}）：
     * 只比状态时，上一轮拆解苏醒后的推进能命中下一轮写的 PLANNING。唯一索引管不到跨 attempt，
     * 这一步是唯一闸门。</p>
     *
     * <p>抛异常 = 回滚整个事务 = 草案一条不剩，这是刻意的：宁可让用户重拆，也不留下
     * 「任务已取消/已被取代但草案还在」的孤儿数据。</p>
     */
    private void advanceAfterDecompose(Long taskId, String attemptId) {
        if (taskService.casStatusForAttempt(taskId, TaskStatus.PLANNING,
                TaskStatus.AWAITING_PLAN_CONFIRM, attemptId)) {
            return;
        }
        TaskView now = taskService.getView(taskId);
        if (now == null) {
            throw new BizException("拆解完成但任务不存在，回滚本批草案: taskId=" + taskId);
        }
        switch (now.status()) {
            case AWAITING_PLAN_CONFIRM -> {
                // 幂等：另一个执行者已经推进过
            }
            case PENDING -> {
                if (!taskService.casStatusForAttempt(taskId, TaskStatus.PENDING,
                        TaskStatus.AWAITING_PLAN_CONFIRM, attemptId)) {
                    throw new BizException("拆解完成但任务状态并发变化，回滚本批草案: taskId=" + taskId);
                }
                log.warn("拆解完成时任务已被回收为 PENDING，草案完整 ⇒ 推回待确认（不报废 AI 成果）: taskId={}",
                        taskId);
            }
            case CANCELLED -> throw new BizException("任务已取消，本批拆解草案作废: taskId=" + taskId);
            case PLANNING -> throw new BizException("本轮拆解已被新一轮拆解取代（attemptId="
                    + attemptId + "），回滚本批草案: taskId=" + taskId);
            default -> throw new BizException("拆解完成但任务处于非预期状态 " + now.status()
                    + "，回滚本批草案: taskId=" + taskId);
        }
    }

    /**
     * 契约位确定性归一：同一任务至多 1 条（判据见 {@link ContractSlotNormalizer}）。
     *
     * <p>放在门禁之前：契约位不是质量问题的候选，而是数据不变量；先归一，门禁看到的才是
     * 最终形态。返回被降级的 id 供**事务外**写时间线——归一本身在事务内，时间线在事务外，
     * 避免长事务里再开独立连接。</p>
     */
    private List<String> normalizeContractSlots(Long taskId, List<PlanDraftView> staged) {
        List<SubTaskView> views = PlanDraftViewMapper.toSubTaskViews(staged);
        List<Long> demotedIds = ContractSlotNormalizer.demotions(views);
        if (demotedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> idToCode = new HashMap<>();
        for (PlanDraftView d : staged) {
            if (d.id() != null && d.code() != null) {
                idToCode.put(d.id(), d.code());
            }
        }
        List<String> demotedCodes = demotedIds.stream()
                .map(idToCode::get).filter(java.util.Objects::nonNull).toList();
        planDraftService.demoteContracts(taskId, demotedCodes);
        log.warn("契约位归一：任务契约是单值资源，保留拓扑最前一条，其余降级: taskId={}, demoted={}",
                taskId, demotedCodes);
        return demotedCodes;
    }

    /** 记录 LLM 调用结束事件（耗时毫秒、finishReason、tokenUsage），补齐可观测缺口。 */
    private void recordLlmCallEnd(Long taskId, PlannerAgentRef planner, AgentResult result, long costMs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("agentId", planner.id());
            payload.put("agentName", planner.name());
            payload.put("costMs", costMs);
            payload.put("success", result.isSuccess());
            payload.put("finishReason", result.getFinishReason());
            payload.put("tokenUsage", result.getTokenUsage());
            taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_LLM_CALL_END.code(),
                    AgentRole.PLANNER, planner.id(), payload);
        } catch (Exception e) {
            // timeline 记录失败不阻断拆解主流程
            log.warn("记录 task_plan_llm_call_end 失败（不阻断拆解）: taskId={}, err={}",
                    taskId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /** 加载 classpath 模板并替换占位符。G-004 增量 B：注入任务技能要求（task.required_skills，
     * 与执行侧 resolve 命中注入、审查侧核验同一清单——拆解规划须与技能规范对齐）。
     * G-011：注入需求包段（{{REQUIREMENT_PACKAGE}}，防御式读取 task.context.requirementPackage）。
     * `TD-3`：注入 {{TASK_ARCHETYPE}} 与 {{SUBTASK_COUNT_RULE}}，并按原型择一拆分维度小节。
     * `TD-6`：注入 {{TASK_CONTEXT_SNAPSHOT}}（附件 / 知识库 / 历史任务骨架，只给「有什么」）。
     * `G-042`：<b>返回 prompt 与附件清单一对</b>——清单是 prompt 里那段「附件 1/2/3」的
     * 实体对照表，调用方拿它把 LLM 回的序号解析成附件 ID。 */
    private RenderedDecomposePrompt renderPrompt(TaskView task,
                                                 PlannerGranularityResolver.GranularityDecision decision) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到拆解 Prompt 模板: " + PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取拆解 Prompt 模板失败: " + e.getMessage());
        }
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        // 清单只渲染一次：既进 prompt 的 {{TASK_CONTEXT_SNAPSHOT}}，又原样带回去做序号解析
        DecomposeContextSnapshotRenderer.AttachmentListing listing =
                renderAttachmentListingSafely(task);
        String rendered = template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述，请依据标题拆解）")
                .replace("{{TASK_ARCHETYPE}}", archetype.name())
                .replace("{{TASK_REQUIRED_SKILLS}}", renderRequiredSkills(task.requiredSkills()))
                .replace("{{SKILL_CATALOG}}", renderSkillCatalog())
                .replace("{{CONSTRAINT_DOC_CATALOG}}", renderConstraintDocCatalog())
                .replace("{{CONSTRAINT_DEFAULT_SET}}", renderConstraintDefaultSet(archetype))
                .replace("{{EXECUTOR_PROFILE}}", decision.profile())
                .replace("{{TASK_DIFFICULTY}}", TaskAgentPolicy.difficulty(task.agentPolicy()).name())
                .replace("{{GRANULARITY}}", decision.granularity().name())
                .replace("{{REQUIREMENT_PACKAGE}}", renderRequirementPackage(task))
                .replace("{{TASK_CONTEXT_SNAPSHOT}}", renderSnapshotSafely(task, listing))
                .replace("{{EXECUTION_ENV}}", renderExecutionEnv(task))
                .replace("{{SUBTASK_COUNT_RULE}}", renderSubtaskCountRule(task));
        // TD-3：六个拆分维度块全在模板内，此处只保留当前原型那一块（缺块即抛，不静默降级）
        return new RenderedDecomposePrompt(
                ArchetypeDimensionsSection.select(rendered, archetype), listing);
    }

    /**
     * 拆解 prompt 与它引用的附件清单（`G-042`）：两者是**同一次渲染的两个产物**。
     *
     * <p>分开返回而不是「各自再算一次」，是因为清单序号与 prompt 里的文字必须逐字对应；
     * 各算一次就会分叉（判据 32）。</p>
     */
    private record RenderedDecomposePrompt(
            String prompt, DecomposeContextSnapshotRenderer.AttachmentListing attachmentListing) {
    }

    /** G-011：需求包段渲染——task.context.requirementPackage 经防御式解析后五字段逐项列表；
     * 无需求包（非澄清链路任务）→ 渲染占位文案，行为等于现状。 */
    private String renderRequirementPackage(TaskView task) {
        return RequirementPackageParser.render(
                RequirementPackageParser.fromContext(task.context()));
    }

    /**
     * 执行环境段渲染（占位符 {@code {{EXECUTION_ENV}}}）：
     * {@code task.context.executionEnv} 经 {@link ExecutionEnv} 防御式解析后渲染为 Markdown 列表。
     *
     * <p><b>未声明时渲染「标记」而不是空串</b>：提示词第 12 条要靠这一段判断「该不该申报环境缺口」。
     * 渲染成空串会让模型看到「这一节下面什么都没有」，既不申报也不追问；给出显式标记
     * 「未声明」才是可判定的信号。</p>
     *
     * <p><b>异常降级为「未声明」</b>：与 {@link #renderSnapshotSafely} 同策略——附加价值不该有
     * 否决权，取不到只是拆得糙一点；但降级方向必须是「当作没有」而不是「当作有」，
     * 否则一句解析异常就会让模型把不存在的路径当事实写进 content。</p>
     */
    private String renderExecutionEnv(TaskView task) {
        try {
            ExecutionEnv env = ExecutionEnv.parse(
                    task.context() == null ? null : task.context().get(TaskContextKeys.EXECUTION_ENV));
            String rendered = env.render();
            return rendered.isBlank() ? ENV_UNDECLARED_MARKER : rendered;
        } catch (Exception e) {
            log.warn("执行环境段渲染失败，已降级为「未声明」（不阻断拆解）: taskId={}, err={}",
                    task.id(), e.getMessage());
            return ENV_UNDECLARED_MARKER;
        }
    }

    /**
     * `TD-6`：上下文快照渲染，**任何异常只降级为空快照，绝不击穿拆解**。
     *
     * <p>渲染器内部已对三类取数各自 try/catch，这里再包一层是因为「附加价值」不该有否决权：
     * 快照取不到只是拆得糙一点，拆解失败则整件事做不了——代价不对等。</p>
     */
    private String renderSnapshotSafely(TaskView task) {
        return renderSnapshotSafely(task, null);
    }

    /** 同上，附件段使用调用方已渲染好的清单（`G-042`；{@code null} = 自行渲染）。 */
    private String renderSnapshotSafely(TaskView task,
                                       DecomposeContextSnapshotRenderer.AttachmentListing listing) {
        try {
            String snapshot = contextSnapshotRenderer.render(task, listing);
            return snapshot != null && !snapshot.isBlank()
                    ? snapshot : "- （本次无可用上下文快照）";
        } catch (Exception e) {
            log.warn("拆解期上下文快照渲染失败，已降级为空快照（不阻断拆解）: taskId={}, err={}",
                    task.id(), e.getMessage());
            return "- （上下文快照渲染失败，按无额外材料处理）";
        }
    }

    /**
     * `G-042`：附件清单渲染，**异常一律降级为空清单**（同 {@link #renderSnapshotSafely} 的理由）。
     *
     * <p>降级方向必须是「当作没有附件」而不是「当作有」：若返回一份臆造的清单，模型给出的序号
     * 会全部解析失败；返回空清单则模型填 `[]`，执行期回落会话全量——后者是改造前的行为，安全。</p>
     */
    private DecomposeContextSnapshotRenderer.AttachmentListing renderAttachmentListingSafely(TaskView task) {
        try {
            DecomposeContextSnapshotRenderer.AttachmentListing listing =
                    contextSnapshotRenderer.renderAttachmentListing(task);
            return listing != null ? listing
                    : DecomposeContextSnapshotRenderer.AttachmentListing.empty("- （附件清单不可用）");
        } catch (Exception e) {
            log.warn("拆解期附件清单渲染失败，已降级为空清单（不阻断拆解）: taskId={}, err={}",
                    task.id(), e.getMessage());
            return DecomposeContextSnapshotRenderer.AttachmentListing.empty("- （附件清单渲染失败，按无附件处理）");
        }
    }

    /**
     * `TD-3`：数量规则句——**门槛与数量分离**（设计 §3.3，借鉴 task-master 的四级回退链）。
     *
     * <p>本方法<b>只做渲染层</b>：{@code task.context.subtaskCount} 有人工显式传入就渲染成
     * 「恰好 N 个」，没传则沿用改造前的 3~10 区间。数量决策链路（谁该传、传多少）不在本项范围
     * ——`GRANULARITY` 仍是唯一的粒度档位来源（`G-010`）。</p>
     */
    private String renderSubtaskCountRule(TaskView task) {
        Integer pinned = pinnedSubtaskCount(task);
        if (pinned == null) {
            return "子任务数量必须在 3~10 个之间；任务简单时宁少勿滥，不要为凑数而拆。";
        }
        return "子任务数量必须恰好 " + pinned + " 个（上游已钉死数量，不得增减）；"
                + "若确有必要偏离该数量，请在对应条目的 uncertainties 中说明理由，不要自行改数量。";
    }

    /**
     * 读取人工钉死的子任务数量。
     *
     * <p>越界钳制到 {@link #MAX_DRAFT_COUNT}：提示词要求「恰好 N 个」而服务端硬上限是 10 时，
     * 若 N &gt; 10 则模型无论如何都会触发落库前拒绝——那是必然失败，不是校验。钳制并告警
     * 比让整批拆解失败更有用（真实的意图是「多拆一些」，10 已经是能落库的上限）。</p>
     */
    private Integer pinnedSubtaskCount(TaskView task) {
        Map<String, Object> context = task.context();
        if (context == null) {
            return null;
        }
        Object raw = context.get(TaskContextKeys.SUBTASK_COUNT);
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String str && !str.isBlank()) {
            try {
                value = Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value == null || value <= 0) {
            return null;
        }
        if (value > MAX_DRAFT_COUNT) {
            log.warn("子任务数量钉死值超服务端上限，已钳制: taskId={}, pinned={}, capped={}",
                    task.id(), value, MAX_DRAFT_COUNT);
            return MAX_DRAFT_COUNT;
        }
        return value;
    }

    /** G-010：按任务执行者画像 + 难度定位拆解粒度；白名单内 accessType 一次批量查询，避免 N+1。 */
    private PlannerGranularityResolver.GranularityDecision granularityDecision(TaskView task) {
        List<Long> executorIds = TaskAgentPolicy.executorAgentIds(task.agentPolicy());
        List<AgentAccessType> accessTypes;
        if (executorIds.isEmpty()) {
            accessTypes = List.of();
        } else {
            accessTypes = agentService.listProfilesByIds(executorIds).stream()
                    .map(AgentProfileSnapshot::accessType).toList();
        }
        return PlannerGranularityResolver.resolve(task.agentPolicy(), accessTypes);
    }

    /** 技能目录渲染：每技能一行「标签 v版本 — 描述（依赖工具: a, b）」；空目录降级文案。超 20 项截断至前 20。 */
    private String renderSkillCatalog() {
        List<SkillPackage> packages = agentSkillSpecService.listPackages();
        if (packages == null || packages.isEmpty()) {
            return "（平台暂无已登记技能包）";
        }
        List<SkillPackage> visible = packages.size() > MAX_SKILL_CATALOG_SIZE
                ? packages.subList(0, MAX_SKILL_CATALOG_SIZE)
                : packages;
        StringBuilder sb = new StringBuilder();
        for (SkillPackage pkg : visible) {
            sb.append("- ").append(pkg.name()).append(" v").append(pkg.version())
              .append(" — ").append(pkg.description());
            if (pkg.requiredTools() != null && !pkg.requiredTools().isEmpty()) {
                sb.append("（依赖工具: ").append(String.join(", ", pkg.requiredTools())).append("）");
            }
            sb.append('\n');
        }
        if (packages.size() > MAX_SKILL_CATALOG_SIZE) {
            sb.append("（技能目录共 ").append(packages.size())
              .append(" 项，仅展示前 ").append(MAX_SKILL_CATALOG_SIZE)
              .append(" 项；未展示技能不参与指派）");
        }
        return sb.toString().trim();
    }

    /**
     * 约束文档目录渲染（`G-037`）：每文档一行「ref — title — summary（章节: id(id title), ...）」；
     * 空目录降级文案。目录不含正文，正文由执行侧 {@code internal_load_constraint_doc} 按需取。
     */
    private String renderConstraintDocCatalog() {
        List<com.helloai.core.shared.constraint.ConstraintDocCatalog.ConstraintDocEntry> entries =
                constraintDocCatalog.list();
        if (entries == null || entries.isEmpty()) {
            return "（平台暂无登记约束文档）";
        }
        StringBuilder sb = new StringBuilder();
        for (com.helloai.core.shared.constraint.ConstraintDocCatalog.ConstraintDocEntry e : entries) {
            sb.append("- ref=").append(e.ref()).append(" — ").append(e.title())
              .append("：").append(e.summary());
            if (e.sections() != null && !e.sections().isEmpty()) {
                sb.append("；可引用章节: ");
                sb.append(e.sections().stream()
                        .map(s -> s.id() + "(" + s.title() + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 默认契约集渲染（`G-037` 正式引入 §2）：按任务类型预选骨架——
     * BUILD（交付代码 / 产物质量判定）→ 默认注入已登记 {@code code-style} 的章节全集，LLM 只做 scope 裁剪；
     * 其它原型 → 提示无默认契约（若实际产出仍涉及代码 / 产物质量，须按「平台约束文档目录」声明引用）。
     * 未登记 code-style / 空目录 → 降级文案（fail-open，不抛异常）。
     */
    private String renderConstraintDefaultSet(TaskArchetype archetype) {
        if (archetype != TaskArchetype.BUILD) {
            return "（本任务类型无默认契约集；如实际产出涉及代码 / 产物质量，仍须按「平台约束文档目录」声明引用，不得输出空数组）";
        }
        List<com.helloai.core.shared.constraint.ConstraintDocCatalog.ConstraintDocEntry> entries =
                constraintDocCatalog.list();
        if (entries == null || entries.isEmpty()) {
            return "（平台暂无登记约束文档）";
        }
        for (com.helloai.core.shared.constraint.ConstraintDocCatalog.ConstraintDocEntry e : entries) {
            if ("code-style".equals(e.ref())) {
                String scope = (e.sections() == null || e.sections().isEmpty())
                        ? "（整文档）"
                        : "[" + e.sections().stream()
                                .map(s -> "\"" + s.id() + "\"")
                                .collect(java.util.stream.Collectors.joining(", ")) + "]";
                return "默认注入 {kind: DOCUMENT, ref: code-style, scope: " + scope + "}（" + e.title() + "）——"
                        + "按下文骨架为**每条子任务**声明引用，scope 按子任务实际内容裁剪（可增删章节、可追加其它已登记文档）；"
                        + "确不适用时须在 constraints 写明不适用理由。";
            }
        }
        return "（未登记默认契约文档 code-style；如实际涉及代码仍须按「平台约束文档目录」声明引用，不得输出空数组）";
    }

    /** 技能要求占位符渲染：保持声明序逗号拼接；null/空 → 显式降级文案（行为零变化）。 */
    private static String renderRequiredSkills(List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return "（任务未声明技能要求）";
        }
        return String.join(", ", requiredSkills);
    }

    /**
     * 解析 LLM 输出为草案条目：strip markdown fence 容错 + 逐条校验必填字段与数量上限。
     *
     * <p>必填校验（P1）覆盖 title / content / deliverable / acceptance 四字段，任一缺失
     * 即 fail-close：落库残缺草案（acceptance=null）会让执行侧失去验收依据、审查侧无标准
     * 可核，代价远高于拆解失败——失败已可经 republish / planById 重触发（入口既有）。
     * 与提示词 planner-decompose.md「字段全部必填」对齐，把提示词约定落到代码兜底。</p>
     */
    private List<PlanDraftItem> parseDraftItems(Long taskId, PlannerAgentRef planner, String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BizException("Planner LLM 返回内容为空");
        }
        String cleaned = stripToJsonArray(rawOutput);
        List<PlanDraftItem> items;
        try {
            items = objectMapper.readValue(cleaned, new TypeReference<List<PlanDraftItem>>() {});
        } catch (Exception e) {
            throw new BizException("Planner LLM 输出 JSON 解析失败: " + e.getMessage()
                    + "; 原始输出摘要: " + summarize(rawOutput));
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("Planner LLM 未拆解出任何子任务");
        }
        if (items.size() > MAX_DRAFT_COUNT) {
            throw new BizException("拆解结果超过数量上限 " + MAX_DRAFT_COUNT + ": 实际 " + items.size());
        }
        // 此方法只做「能不能解析成草案」——结构/字段/kind 三类判据统一由
        // DraftGateChecker 在**落库之后**判定，结论写进每条草案的 context.gateFindings。
        // 不再在解析期抛异常：一条草案的写法问题不该让整批草案拿不到（那正是「重试必复现」的来源）。
        return items;
    }

    /**
     * 显式声明的 acceptanceKind 原文（合法枚举才返回）：缺失/非法值一律返回 {@code null}。
     *
     * <p>判据同 `LOG-20261024-040` 的踩坑结论——「是否显式声明」必须看原始字符串，
     * 不能用 {@link AcceptanceKind#parse} 的结果（它对 null/非法值回落 {@code AUTOMATED}）。</p>
     */
    private static String explicitAcceptanceKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AcceptanceKind.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 草案必填字段探测：title / content / deliverable / acceptance 四字段任一为 null 或
     * 空白即视为缺失（LLM 常以空串或纯空白占位，需按内容为空处理）。
     *
     * @return 首个缺失字段名；四字段齐全返回 {@code null}
     */
    private static String missingRequiredField(PlanDraftItem item) {
        if (item.getTitle() == null || item.getTitle().isBlank()) {
            return "title";
        }
        if (item.getContent() == null || item.getContent().isBlank()) {
            return "content";
        }
        if (item.getDeliverable() == null || item.getDeliverable().isBlank()) {
            return "deliverable";
        }
        if (item.getAcceptance() == null || item.getAcceptance().isBlank()) {
            return "acceptance";
        }
        return null;
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾方括号之间的 JSON 数组。 */
    private String stripToJsonArray(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("[")) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    /** 草案实体装配：status=PENDING（执行行；草案本身只落暂存区），context 记录拆解来源审计信息。
     * G-011：uncertainties 落库（kind 降级审计）+ COARSE constraints 缺失 WARN（G-010 缺口④清偿）
     * + D5 兜底审计（openQuestions 无 UNCONFIRMED 继承 WARN）。 */
    private List<SubTaskDraft> buildDrafts(TaskView task, List<PlanDraftItem> items, PlannerAgentRef planner,
                                           PlannerGranularityResolver.GranularityDecision decision,
                                           String attemptId, int rootSeqStart,
                                           DecomposeContextSnapshotRenderer.AttachmentListing attachmentListing) {
        Long taskId = task.id();
        // `ADR-008` §19.2：位置身份在**这一处**算出来（单段 <日期><任务内序号>），
        // 写进 SubTaskDraft，由 task 域原样落库。派生已改为独立 Task，不再产生子段编号。
        LocalDate taskDate = DraftCode.dateOf(task.createTime());
        List<SubTaskDraft> drafts = new ArrayList<>(items.size());
        String generatedAt = OffsetDateTime.now().toString();
        // G-022 拆解侧前置校验：在岗 Agent 技能集合只查一次（避免逐草案 N+1）
        List<List<String>> activeAgentSkillSets = activeAgentSkillSets();
        for (int idx = 0; idx < items.size(); idx++) {
            PlanDraftItem item = items.get(idx);
            List<UncertaintyDraft> uncertainties =
                    normalizeUncertainties(taskId, resolveUncertainties(item), idx + 1, items.size(), planner);
            // G-010 缺口④清偿：COARSE 粒度 constraints 缺失记 timeline WARN（不阻断落库）
            if (decision.granularity() == PlannerGranularity.COARSE
                    && (item.getConstraints() == null || item.getConstraints().isBlank())) {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_CONSTRAINTS_MISSING.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", drafts.size() + 1, "title", item.getTitle()));
            }
            List<String> keptSkills = filterRequiredSkills(taskId, item.getRequiredSkills(), planner);
            // G-022 拆解侧前置校验（告警而非阻断）：声明技能须有在岗 Agent 可承接
            auditSkillCoverage(taskId, drafts.size() + 1, item.getTitle(), keptSkills, activeAgentSkillSets, planner);
            // G-037：类型化约束引用归一（kind 白名单 + DOCUMENT ref 必填 + scope 数组校验，非法元素丢弃）
            List<ConstraintRefDraft> constraintRefs = normalizeConstraintRefs(taskId, planner, item);
            Map<String, Object> context = new HashMap<>();
            context.put("plannerAgentId", planner.id());
            context.put("plannerAgentName", planner.name());
            context.put("planGeneratedAt", generatedAt);
            // `G-042`：把 LLM 声明的附件序号解析成 chat_attachment.id 落进 context。
            // 用渲染期冻结的那一份清单解析（`attachmentListing`），**不重新查库**——
            // 清单进 prompt 后 Planner 要跑十几分钟，这期间新传的附件会插到倒序结果头部，
            // 重查一次就会让「附件 2」指向另一张图（判据 32）。
            List<Long> mediaRefIds = resolveAttachmentRefs(taskId, idx, item, attachmentListing);
            if (!mediaRefIds.isEmpty()) {
                context.put(SubTaskContextKeys.MEDIA_REF_IDS, mediaRefIds);
            }
            // 显式声明的 kind 原文必须随草案保存：acceptance_kind 列经 parse 后无法区分
            // 「显式声明 AUTOMATED」与「未声明」，而门禁对两者的处置不同（block vs warn）。
            String declaredKind = explicitAcceptanceKind(item.getAcceptanceKind());
            if (declaredKind != null) {
                context.put(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED, declaredKind);
            }
            // TD-11：验收契约可满足性标注（**只标注，不改任何既有字段、不阻断落库**）。
            // 挂在此处 = 「uncertainties 已归一化、dependsOn 已可解析」的时点。TD-5 的校验器
            // 物理上看不到 uncertainties（签名里就没有），所以「验收要求取回外部实证」×
            // 「该外部事实仍未确认」这个死锁只能在这一层发现。
            AcceptanceSatisfiabilityChecker.Result satisfiability =
                    checkAcceptanceSatisfiability(taskId, planner, items, idx, item, uncertainties);
            if (satisfiability.unmet()) {
                // TD-13 S1：引用必须在**持有它的草案**内解析到，解析不到即不落标注（回落为无豁免）。
                if (AcceptanceSatisfiabilityChecker.allRefsResolvable(
                        satisfiability.preconditionRefs(), uncertainties)) {
                    context.put(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS,
                            satisfiability.preconditionRefs());
                } else {
                    taskTimelineService.recordEvent(taskId, null,
                            TimelineEventCode.TASK_PLAN_ACCEPTANCE_PRECONDITION_UNRESOLVABLE.code(),
                            AgentRole.PLANNER, planner.id(),
                            Map.of("draftSeq", idx + 1,
                                    "preconditionRefs", satisfiability.preconditionRefs()));
                }
            }
            drafts.add(new SubTaskDraft(taskId, item.getTitle().trim(), item.getContent(),
                    item.getDeliverable(), item.getAcceptance(),
                    resolveSubTaskPriority(item.getPriority(), task.priority()),
                    Boolean.TRUE.equals(item.getContract()) ? 1 : 0,
                    keptSkills,
                    item.getConstraints(), SubTaskStatus.PENDING, context, uncertainties,
                    // TD-3 / TD-2：LLM 未给或给了非法值一律回落 AUTOMATED（= 改造前唯一语义）
                    AcceptanceKind.parse(item.getAcceptanceKind()),
                    // V146 幂等键：attemptId 来自入口（不是这里现生成），draftSeq 即 dependsOn 用的序号。
                    // **draftSeq 永远是批内 1..N**——`dependsOn` 里的整数引用它，
                    // `uk_sub_task_decompose_attempt` 也用它区分同批草案，不能改成全局号。
                    idx + 1,
                    // 位置身份：<日期><任务内全局序号>
                    //
                    // 根层的 seq 用 `rootSeqStart + idx` 而**不是** `idx + 1`：code 是「任务内位置」，
                    // 设计 §3.1 定死「跨轮不重置」，否则第二轮从 0001 起会与第一轮残留行同号。
                    DraftCode.root(taskDate, rootSeqStart + idx).value(),
                    // G-037：类型化约束引用（原样透传已归一化的列表，空 = 无契约引用）
                    constraintRefs));
        }
        // D5 兜底审计：任务 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → WARN（不阻断）
        auditOpenQuestionsInheritance(task, drafts, planner);
        return drafts;
    }

    /**
     * `G-042`：把 LLM 声明的 `attachmentRefs`（1-based 清单序号）解析成 `chat_attachment.id`。
     *
     * <p><b>解析基准是渲染期冻结的清单</b>（{@code listing.attachments()}），不是重新查库：
     * 清单写进 prompt 后 Planner 要跑十几分钟，期间用户新传的附件会插到
     * {@code listLatestByConversation} 倒序结果的头部，重查一次会让同一个序号指向不同文件。
     * 这类错误不报错、不告警，只是默默送错图（判据 32）。</p>
     *
     * <p><b>逐元素防御</b>：模型输出的形态不可控（字符串 / 浮点 / 嵌套对象 / 越界 / 重复），
     * 每个坏元素只丢自己——一条草案的附件声明写坏不该让整批拆解失败。这与
     * {@code uncertainties} / {@code constraintRefs} 的既有处置同口径。</p>
     *
     * <p><b>去重且保序</b>：用 {@link LinkedHashSet} 而非 {@code List}，因为下游按此清单取图，
     * 同一张图重复送两遍既浪费预算（5 张上限按条数算）也没有语义。</p>
     *
     * @param taskId  任务 ID（仅用于审计日志）
     * @param idx     草案下标（0-based，仅用于审计日志）
     * @param item    LLM 单条输出
     * @param listing 渲染 prompt 时冻结的附件清单
     * @return 解析出的附件 ID（去重、按声明顺序）；无声明 / 全部无效时为空表
     */
    private List<Long> resolveAttachmentRefs(Long taskId, int idx, PlanDraftItem item,
                                             DecomposeContextSnapshotRenderer.AttachmentListing listing) {
        JsonNode node = item.getAttachmentRefsNode();
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<ChatAttachment> available = listing == null ? List.of() : listing.attachments();
        if (available.isEmpty()) {
            // 清单为空却声明了序号 ⇒ 模型凭空编造。不记 warn（拆解链路上「空清单」是常态，
            // 而模型偶尔仍会填一个 1），只 debug；解析结果为空自然回落会话全量。
            log.debug("附件作用域声明无对应清单，已忽略: taskId={}, draftSeq={}, refs={}",
                    taskId, idx + 1, node);
            return List.of();
        }
        Set<Long> resolved = new LinkedHashSet<>();
        int dropped = 0;
        for (JsonNode element : node) {
            Integer seq = intOf(element);
            // 序号 1-based；越界（<=0 或 > N）直接丢弃——「越界等于没填」，不猜意图也不钳制到边界，
            // 因为钳制会把「模型以为有第 9 张」静默变成「用第 5 张」，比丢弃更偏离原意。
            if (seq == null || seq < 1 || seq > available.size()) {
                dropped++;
                continue;
            }
            ChatAttachment attachment = available.get(seq - 1);
            if (attachment != null && attachment.getId() != null) {
                resolved.add(attachment.getId());
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.warn("附件作用域声明含无效元素（已丢弃，不影响其余）: taskId={}, draftSeq={}, dropped={}, size={}",
                    taskId, idx + 1, dropped, available.size());
        }
        return List.copyOf(resolved);
    }

    /**
     * 从 JSON 节点取整数（容忍数字与纯数字字符串，其余一律 {@code null}）。
     *
     * <p>不像 {@code uncertainties} 那样容忍浮点截断：附件序号是离散位置，{@code 2.7} 既不是
     * 第 2 张也不是第 3 张，静默截断等于替模型猜——宁可丢弃并计数。</p>
     */
    private static Integer intOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.textValue().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * `TD-11`：验收契约可满足性标注（**只标注，不阻断**）。
     *
     * <p>判据与取舍见 {@link AcceptanceSatisfiabilityChecker} 类注释；本方法只负责
     * 「取上游文本 → 调纯函数 → 命中则记一条 WARN 级时间线事件」。写进
     * {@code sub_task.context.acceptancePreconditions} 的动作在 {@code buildDrafts} 里
     * （那里才有 context 变量），本方法只返回结果。</p>
     *
     * <p><b>异常一律吞掉</b>：标注是纯增量观测，任何异常都不得让整批拆解失败——
     * 那是 `TD-5` 风险 2 的反面教训（校验过严会阻塞主链路）。</p>
     */
    private AcceptanceSatisfiabilityChecker.Result checkAcceptanceSatisfiability(
            Long taskId, PlannerAgentRef planner, List<PlanDraftItem> items, int idx,
            PlanDraftItem item, List<UncertaintyDraft> uncertainties) {
        try {
            AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                    item.getAcceptance(), uncertainties, upstreamTexts(items, item));
            if (result.unmet()) {
                taskTimelineService.recordEvent(taskId, null,
                        TimelineEventCode.TASK_PLAN_ACCEPTANCE_PRECONDITION_UNMET.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("draftSeq", idx + 1,
                                "title", item.getTitle() == null ? "" : item.getTitle(),
                                "preconditionRefs", result.preconditionRefs(),
                                "uncertaintyNotes", result.uncertaintyNotes()));
            }
            return result;
        } catch (Exception e) {
            log.warn("验收可满足性标注失败（不阻断拆解）: taskId={}, draftSeq={}, err={}",
                    taskId, idx + 1, e.getMessage());
            return AcceptanceSatisfiabilityChecker.Result.NONE;
        }
    }

    /**
     * 取该条草案 {@code dependsOn} 指向的草案标题 + 正文（供判「缺输入是否已由 DAG 表达」）。
     *
     * <p>{@code dependsOn} 存的是**本批草案的 1-based 序号**（不是 id），越界序号直接跳过——
     * 越界属于解析期问题，由 {@link #applyDependsOn} 在「序号 → id」映射时兜底并留痕。</p>
     */
    private static List<String> upstreamTexts(List<PlanDraftItem> items, PlanDraftItem item) {
        if (item.getDependsOn() == null || item.getDependsOn().isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Integer seq : item.getDependsOn()) {
            if (seq == null || seq < 1 || seq > items.size()) {
                continue;
            }
            PlanDraftItem up = items.get(seq - 1);
            texts.add((up.getTitle() == null ? "" : up.getTitle()) + " "
                    + (up.getContent() == null ? "" : up.getContent()));
        }
        return texts;
    }

    /**
     * G-011：LLM uncertainties 字段（JsonNode，防御承接）→ 元素列表。
     * 缺失 / 非数组 / 转换失败 → 空列表（不阻断拆解）；数组元素级 kind 非法值
     * 由 {@link #normalizeUncertainties} 逐个降级审计。
     */
    private List<UncertaintyDraft> resolveUncertainties(PlanDraftItem item) {
        JsonNode node = item.getUncertaintiesNode();
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<List<UncertaintyDraft>>() { });
        } catch (Exception e) {
            log.warn("拆解 uncertainties 解析失败，回落空列表（不阻断）: title={}, err={}",
                    item.getTitle(), e.getMessage());
            return List.of();
        }
    }

    /** G-037：拆解 {@code constraintRefs} 原始解析——JsonNode 防御式承接，非法形态回落空列表（不阻断）。 */
    private List<ConstraintRefDraft> resolveConstraintRefs(PlanDraftItem item) {
        JsonNode node = item.getConstraintRefsNode();
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<List<ConstraintRefDraft>>() { });
        } catch (Exception e) {
            log.warn("拆解 constraintRefs 解析失败，回落空列表（不阻断）: title={}, err={}",
                    item.getTitle(), e.getMessage());
            return List.of();
        }
    }

    /**
     * G-037：约束引用归一——kind 白名单（{@link ConstraintRefKind}）+ {@code DOCUMENT} ref 必填 +
     * scope 数组元素非空。非法元素<b>丢弃</b>（fail-safe：宁可少一条引用，不把一份文档误判成另一份），
     * 丢弃记 WARN 日志、不阻断落库。
     */
    private List<ConstraintRefDraft> normalizeConstraintRefs(Long taskId, PlannerAgentRef planner,
                                                             PlanDraftItem item) {
        List<ConstraintRefDraft> raw = resolveConstraintRefs(item);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ConstraintRefDraft> kept = new ArrayList<>(raw.size());
        for (ConstraintRefDraft r : raw) {
            if (r == null) {
                continue;
            }
            String kind = r.kind();
            if (!ConstraintRefKind.isKnown(kind)) {
                log.warn("拆解 constraintRefs 丢弃非法元素（非法 kind）: taskId={}, title={}, rawKind={}",
                        taskId, item.getTitle(), String.valueOf(kind));
                continue;
            }
            if (ConstraintRefKind.DOCUMENT.name().equals(kind)
                    && (r.ref() == null || r.ref().isBlank())) {
                log.warn("拆解 constraintRefs 丢弃非法元素（DOCUMENT ref 为空）: taskId={}, title={}",
                        taskId, item.getTitle());
                continue;
            }
            if (r.scope() != null && r.scope().stream().anyMatch(s -> s == null || s.isBlank())) {
                log.warn("拆解 constraintRefs 丢弃非法元素（scope 含空项）: taskId={}, title={}",
                        taskId, item.getTitle());
                continue;
            }
            kept.add(r);
        }
        return kept;
    }

    /**
     * G-011 D3：kind 合法性归一 + 降级审计——非 ASSUMPTION / UNCONFIRMED / TO_LOOK_UP /
     * TO_DERIVE 的值降级 UNCONFIRMED（不丢弃：降级到更严语义符合 fail-close，与 G-010 幻觉标签
     * 丢弃模式差异理由见设计 D3）；note 空白条目丢弃（无信息量，消费侧无法使用）。
     *
     * <p>`TD-13 S2`：同时归一 {@code blockedBy}（越界 / 自引用 / 重复），见
     * {@link #normalizeBlockedBy}。</p>
     *
     * <p>每批 uncertainties 只归一化一次（`TD-13 S1` 删除了任务级汇总的第二次归一化）——
     * 降级审计是副作用，重复归一化会把 {@code task_plan_uncertainty_degraded} 记两次。
     * `S2` 的 {@code blockedBy} 校验也**只能挂在这唯一一次归一化里**：另立一个入口各跑一遍，
     * 就是 `LOG-20261026-025` 的双重归一化事故（当年两个入口各补一次 ref，编号互相覆盖）。</p>
     *
     * @param ownSeq    当前草案的 1-based 序号（判 `blockedBy` 自引用）
     * @param batchSize 本批草案总数（判 `blockedBy` 越界）
     */
    private List<UncertaintyDraft> normalizeUncertainties(Long taskId, List<UncertaintyDraft> raw,
                                                          int ownSeq, int batchSize,
                                                          PlannerAgentRef planner) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UncertaintyDraft> kept = new ArrayList<>(raw.size());
        for (UncertaintyDraft u : raw) {
            if (u.note() == null || u.note().isBlank()) {
                continue;
            }
            String kind = u.kind();
            // TD-7：值域白名单单一来源 UncertaintyKind（四值），非法值仍降级 UNCONFIRMED
            if (!UncertaintyKind.isKnown(kind)) {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_UNCERTAINTY_DEGRADED.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("note", u.note(), "rawKind", String.valueOf(kind),
                                "degradedTo", UncertaintyDraft.KIND_UNCONFIRMED));
                kind = UncertaintyDraft.KIND_UNCONFIRMED;
            }
            List<Integer> blockedBy = normalizeBlockedBy(taskId, ownSeq, batchSize, u, planner);
            // ref 原样透传：`ensureRefs` 的契约是「模型给了非空且未重复的 ref ⇒ 原样保留」，
            // 这里先丢掉再让 ensureRefs 重编号，等于把「模型可以起有语义的标签」这条能力作废。
            kept.add(new UncertaintyDraft(kind, u.note(), u.ref(), blockedBy));
        }
        // TD-11：ref 补齐走 `AcceptanceSatisfiabilityChecker#ensureRefs`（全项目唯一实现，
        // 判据侧读 ref 用的也是它——两处各写一份编号逻辑必然分叉）
        return AcceptanceSatisfiabilityChecker.ensureRefs(kept);
    }

    /**
     * `TD-13 S2`：缺口影响面归一——越界序号丢弃、自引用丢弃、重复去重，各记一条 WARN。
     *
     * <p><b>为什么必须归到 {@code 1..batchSize}</b>：{@code blockedBy} 存的是本批 1-based 草案序号，
     * 与 {@code dependsOn} 同一坐标系（落库时 {@code applyDependsOn} 才把它换成真实 id）。
     * 越界序号若不丢，消费侧按序号取草案就会拿到 {@code null} 或错位——静默错位比丢弃更难查。</p>
     *
     * <p><b>为什么自引用要丢</b>：一条草案的缺口卡住它自己，等于「这条草案因为自己而未确认」，
     * 是循环论证，不是信息。这也不是执行顺序（{@code dependsOn} 明确禁止自引用），
     * 故不能靠「反正 dependsOn 会校验」兜住。</p>
     *
     * <p><b>一条事件而不是一条一事件</b>：同一草案的 {@code blockedBy} 被丢 3 项就记 3 条事件，
     * 10 条草案能刷出 30 条噪音（`s4-2` 的 {@code task_plan_gate_warned} 踩过同一坑）。
     * 故按「本条 uncertainty」聚合，payload 里带 {@code dropped} 明细。</p>
     *
     * @return 归一后的序号列表；{@code null} = 模型未申报（与「申报为空」语义不同，不得合并）
     */
    private List<Integer> normalizeBlockedBy(Long taskId, int ownSeq, int batchSize,
                                             UncertaintyDraft u, PlannerAgentRef planner) {
        List<Integer> raw = u.blockedBy();
        if (raw == null) {
            return null;
        }
        List<Integer> kept = new ArrayList<>(raw.size());
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (Integer seq : raw) {
            if (seq == null) {
                continue;
            }
            String reason = null;
            if (seq == ownSeq) {
                reason = "self";
            } else if (seq < 1 || seq > batchSize) {
                reason = "out_of_range";
            } else if (kept.contains(seq)) {
                reason = "duplicate";
            }
            if (reason == null) {
                kept.add(seq);
            } else {
                dropped.add(Map.of("seq", seq, "reason", reason));
            }
        }
        if (!dropped.isEmpty()) {
            taskTimelineService.recordEvent(taskId, null,
                    TimelineEventCode.TASK_PLAN_UNCERTAINTY_BLOCKED_BY_NORMALIZED.code(),
                    AgentRole.PLANNER, planner.id(),
                    Map.of("draftSeq", ownSeq,
                            "ref", String.valueOf(u.ref()),
                            "rawBlockedBy", raw,
                            "keptBlockedBy", kept,
                            "dropped", dropped));
        }
        return List.copyOf(kept);
    }

    /**
     * G-011 D5 兜底审计：任务需求包 openQuestions 非空，但拆解产物无任何子任务携带
     * UNCONFIRMED 继承 → timeline 记 WARN 级 {@code task_plan_uncertainty_missing}
     * （仅审计 openQuestions → UNCONFIRMED，不审计 assumptions；不阻断落库）。
     */
    private void auditOpenQuestionsInheritance(TaskView task, List<SubTaskDraft> drafts, PlannerAgentRef planner) {
        RequirementPackage pkg = RequirementPackageParser.fromContext(task.context());
        if (pkg.openQuestions().isEmpty()) {
            return;
        }
        boolean hasUnconfirmed = drafts.stream()
                .flatMap(d -> d.uncertainties() == null
                        ? java.util.stream.Stream.<UncertaintyDraft>empty() : d.uncertainties().stream())
                .anyMatch(u -> UncertaintyDraft.KIND_UNCONFIRMED.equals(u.kind()));
        if (!hasUnconfirmed) {
            taskTimelineService.recordEvent(task.id(), null, TimelineEventCode.TASK_PLAN_UNCERTAINTY_MISSING.code(),
                    AgentRole.PLANNER, planner.id(),
                    Map.of("openQuestions", pkg.openQuestions()));
        }
    }

    /**
     * G-010：子任务级技能目录过滤。仅保留目录内命中的标签（归一化后精确命中），
     * 未命中（幻觉 / 未登记）标签丢弃并记 timeline {@code task_plan_skill_filtered}；
     * 空指派返回空列表，不阻断拆解。
     */
    private List<String> filterRequiredSkills(Long taskId, List<String> assigned, PlannerAgentRef planner) {
        if (assigned == null || assigned.isEmpty()) {
            return List.of();
        }
        Set<String> known = agentSkillSpecService.listPackages().stream()
                .map(SkillPackage::name).collect(Collectors.toSet());
        List<String> kept = new ArrayList<>();
        for (String raw : assigned) {
            String normalized = SkillNormalizer.normalize(raw);
            if (normalized != null && known.contains(normalized)) {
                kept.add(normalized);
            } else {
                taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_SKILL_FILTERED.code(),
                        AgentRole.PLANNER, planner.id(),
                        Map.of("skill", raw, "reason", "未命中平台技能目录"));
            }
        }
        return kept;
    }

    /**
     * G-022 拆解侧前置校验：草案声明技能后，校验当前在岗 Agent 中是否存在可承接者
     * —— {@code agent.skills ⊇ required_skills}（AND 语义，走 {@link SkillNormalizer#matches}，
     * 与选人门 {@code AgentSelector} 同一匹配器，防规则漂移）。
     *
     * <p>无承接者 ⇒ 记 timeline {@code task_plan_skill_unmatched}。<b>告警而非阻断</b>：
     * ① Agent 可能在拆解后被注册 / 重注册（引用边回填在岗技能）；② 阻断会让整批拆解失败，
     * 损失远大于「保留草案 + 可观测告警」；③ 与 {@link #filterRequiredSkills} 的「有疑问不阻断」口径一致。
     * 本校验消除的正是「静默等待」——原状态下子任务永久 PENDING，仅事后由
     * {@code SubTaskPendingOrphanTask} 记 {@code sub_task_no_candidate} 才在时间线可见。</p>
     */
    private void auditSkillCoverage(Long taskId, int draftSeq, String title, List<String> requiredSkills,
                                    List<List<String>> activeAgentSkillSets, PlannerAgentRef planner) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return;
        }
        boolean covered = activeAgentSkillSets.stream()
                .anyMatch(agentSkills -> SkillNormalizer.matches(agentSkills, requiredSkills));
        if (covered) {
            return;
        }
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_SKILL_UNMATCHED.code(),
                AgentRole.PLANNER, planner.id(),
                Map.of("draftSeq", draftSeq,
                        "title", title != null ? title : "",
                        "requiredSkills", requiredSkills,
                        "activeAgentCount", activeAgentSkillSets.size()));
    }

    /** 在岗 Agent 的技能标签集合（G-022 承接者校验专用；一次查询避免逐草案 N+1）。
     * 经 {@link AgentService} 投影方法获取，不 import agent 实体（§7.2 端口反转）。 */
    private List<List<String>> activeAgentSkillSets() {
        List<List<String>> sets = agentService.listActiveSkillSets();
        return sets != null ? sets : List.of();
    }

    private String normalizePriority(String priority) {
        if (priority == null) {
            return "MEDIUM";
        }
        String upper = priority.trim().toUpperCase();
        return VALID_PRIORITIES.contains(upper) ? upper : "MEDIUM";
    }

    /**
     * 子任务优先级解析（N-006，C4-S1 优先级继承）：LLM 显式合法值优先；
     * 未给 / 空白 / 非法 → 继承 task.priority（task 缺失时回落 MEDIUM）。
     */
    private String resolveSubTaskPriority(String itemPriority, String taskPriority) {
        if (itemPriority != null && !itemPriority.isBlank()
                && VALID_PRIORITIES.contains(itemPriority.trim().toUpperCase())) {
            return itemPriority.trim().toUpperCase();
        }
        return TaskPriority.normalize(taskPriority);
    }

    /**
     * 序号 → 位置身份（code）映射回写：`dependsOnCodes` 落暂存区。
     *
     * <p>暂存区行按 {@code code} 升序返回，与 LLM 输出顺序一一对应（`writeDrafts`
     * 按同一顺序插入）。序号越界不整批报废：丢掉该边并记一条 block finding，
     * 用户在草案页改完即可确认——与门 1 同口径。</p>
     *
     * <p>不再做「依赖 id 是否真实存在」的二次查询：边引用的是同一批暂存区行的 code，
     * 由构造保证存在；确认物化时再按 code → 新执行行 id 翻译。</p>
     */
    private List<DraftGateChecker.Finding> applyDependsOn(Long taskId, List<PlanDraftView> staged,
                                                          List<PlanDraftItem> items) {
        if (staged.size() != items.size()) {
            throw new BizException("拆解草案数量与 LLM 输出不一致: drafts=" + staged.size()
                    + " items=" + items.size() + ", taskId=" + taskId + "；需取消现有草案后重新拆解");
        }
        List<DraftGateChecker.Finding> anomalies = new ArrayList<>();
        Map<String, List<String>> edits = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            List<Integer> deps = items.get(i).getDependsOn();
            if (deps == null || deps.isEmpty()) {
                continue;
            }
            PlanDraftView draft = staged.get(i);
            if (draft.code() == null || draft.code().isBlank()) {
                throw new BizException("拆解结果第 " + (i + 1) + " 条缺少位置编号（code），请重试拆解");
            }
            List<String> depCodes = new ArrayList<>(deps.size());
            for (Integer dep : deps) {
                if (dep == null || dep < 1 || dep > staged.size()) {
                    anomalies.add(DraftGateChecker.finding(
                            DraftGateChecker.GATE_STRUCTURE, DraftGateChecker.G1_DEP_OUT_OF_RANGE,
                            DraftGateChecker.SEVERITY_BLOCK, draft.code(), "dependsOn",
                            "依赖序号非法: " + dep + "（合法范围 1~" + staged.size() + "）",
                            DraftGateChecker.FIX_A));
                    continue;
                }
                depCodes.add(staged.get(dep - 1).code());
            }
            if (depCodes.isEmpty()) {
                continue;
            }
            edits.put(draft.code(), depCodes.stream().distinct().toList());
            log.info("applyDependsOn: taskId={}, code={}, seqDeps={}, depCodes={}",
                    taskId, draft.code(), deps, depCodes);
        }
        planDraftService.applyDependencies(taskId, edits);
        return anomalies;
    }

    /**
     * 失败回退：仅当 Task 仍处<b>本轮</b>拆解（状态 PLANNING 且 attemptId 未被新一轮取代）时
     * 回退 PENDING。
     *
     * <p>带 attemptId 而不只带状态：本轮拆解跑超租约被巡检回收、用户重新拆解后，本轮才失败返回，
     * 只比状态的 CAS 会把<b>新一轮</b>的 PLANNING 推回 PENDING，让新一轮白跑。</p>
     */
    private void rollbackToPending(Long taskId, String attemptId) {
        try {
            taskService.casStatusForAttempt(taskId, TaskStatus.PLANNING, TaskStatus.PENDING, attemptId);
        } catch (Exception e) {
            log.error("任务拆解失败后回退 PENDING 异常: taskId={}, attemptId={}", taskId, attemptId, e);
        }
    }

    private String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= RAW_OUTPUT_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, RAW_OUTPUT_SUMMARY_LIMIT) + "...";
    }
}
