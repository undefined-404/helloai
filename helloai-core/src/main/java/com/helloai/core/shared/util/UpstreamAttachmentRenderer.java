package com.helloai.core.shared.util;

import java.util.List;

/**
 * 上游附件内容的「按预算渲染」工具（P-1 防御层 R2，2026-09-30 审计 §15.4 响应）。
 *
 * <p>背景：A1 修复后消费方拼接全部 ACTIVE 附件，但仍按单点 {@code DEP_CONTENT_MAX_CHARS}
 * 截断——拼接后总量（如 主文件 7809 + 附录 19294 ≈ 27K 字符）截到 4000 时，第二个及
 * 以后的附件整体落在截断线外，永远不可见。本工具把「拼接后单点截断」改为「逐附件配额
 * 渲染」：</p>
 * <ul>
 *     <li>主附件（列表首位，调用方按创建时间正序保证「主文件优先」）拿大头预算，
 *     其余附件各得最低配额（既不挤掉主文件、也不让靠后附件整体消失）；</li>
 *     <li>每附件独立按行边界截断（{@link TextTruncator}），被截时附结构化标注
 *     {@code [TRUNCATED] file=... shown=... total=... reason=...}（与核验侧同口径，
 *     下游可机读「哪个文件被截、缺了多少」）；</li>
 *     <li>预算分配时预留标题行 / 截断标注行 / 段分隔的最坏开销，保证输出总长不超过
 *     {@code totalBudget}——调用方渲染层的兜底截断因此不会被二次触发，
 *     逐附件标注不会被「顶层截断 + 顶层标注」叠加吃掉。</li>
 * </ul>
 *
 * <p>纯静态工具：无 Spring、无业务实体依赖，执行域两处消费方（Prompt 注入 /
 * getDepsSummary）共用，避免预算算法口径漂移。</p>
 */
public final class UpstreamAttachmentRenderer {

    /** 主附件最低正文配额：附件数多 / 文件名长导致预算被开销挤占时，主附件保底可读量。 */
    public static final int MAIN_MIN_BODY_CHARS = 1000;

    /** 次要附件最低正文配额：每个非主附件至少可读量；预算不足以覆盖时按剩余均分。 */
    public static final int MINOR_MIN_BODY_CHARS = 500;

    /** 每附件渲染开销预留（标题行 + 截断标注行 + 段分隔 + 数值位宽余量，保守上界）。
     *  2026-10-10 由 84 上调至 108：截断标注行新增 {@code id=<19 位雪花>}（约 23 字符）——
     *  预留必须覆盖最坏开销，否则「输出总长不超过 {@code totalBudget}」这条上界会破。 */
    private static final int PER_FILE_RESERVE_EXTRA = 108;

    /**
     * 单条前置产出（依赖产出）内容的字符上限 —— **执行域两处消费方的唯一口径来源**。
     *
     * <p>消费方：① 执行链 Prompt 注入（{@code AgentRuntimeContextAssembler}）；
     * ② 外部 Agent 的 {@code getDepsSummary}（{@code McpToolServiceImpl}）。
     * 两者都把本值作为 {@link #render(List, int) render} 的 {@code totalBudget}，
     * 并对非附件文本走同一上限截断 —— 故常量放本类（预算算法的宿主），
     * 不再各自复制一份（2026-10-10 `REF-5.2a`：此前两份 64000 各写一遍，
     * 且注释互相指认，属典型的口径漂移风险）。</p>
     *
     * <p>2026-10-03 上调 4000 → 64000：所有 LLM 走官方 api-key（DeepSeek 64K~1M 上下文），
     * 4000 对 64K 上下文是自我阉割 —— 下游 executor 读不到前置关键产出、诱发幻觉，属纯沉默成本。
     * 与核验侧 {@code AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT} 同值对齐，
     * 统一为「正常产出全量注入 + 极端超长兜底截断」；若需调整，先改这里再评估两处消费方影响。</p>
     */
    public static final int DEP_CONTENT_MAX_CHARS = 64000;

    private UpstreamAttachmentRenderer() {
    }

    /** 已成功读取的附件（名称 + 正文）；读取失败 / 不可加载的附件由调用方先过滤。
     *
     *  <p>{@code attachmentId} 用于被截断时在标注行里给出**可寻址的 ref**（2026-10-10）：
     *  消费侧（外部 Agent）拿到它才能用既有
     *  {@code GET /api/attachments/downloadById/{attachmentId}} 取回被截掉的部分 ——
     *  此前标注行只有文件名，agent「知道被截了、也不知道拿什么去取」。
     *  非附件文本（无对应附件行）传 {@code null}，标注行会省略该字段。</p> */
    public record LoadedAttachment(String name, String content, Long attachmentId) {

        /** 无附件 id 时的便捷构造（非附件文本 / 单元测试）。 */
        public LoadedAttachment(String name, String content) {
            this(name, content, null);
        }
    }

    /**
     * 按预算渲染附件列表（调用方保证列表首位 = 主附件）。
     *
     * @param files       已读取成功的附件（null / 空列表 → 返回空串）
     * @param totalBudget 总字符预算（≤0 → 返回空串）
     * @return 渲染文本（{@code 【文件：xxx】} 标题行 + 逐附件正文/截断标注），总长 ≤ totalBudget
     */
    public static String render(List<LoadedAttachment> files, int totalBudget) {
        if (files == null || files.isEmpty() || totalBudget <= 0) {
            return "";
        }
        int n = files.size();
        // 预留每附件最坏开销（保守上界），从总预算中先行扣除后再分配正文配额
        int reserve = 0;
        for (LoadedAttachment file : files) {
            int nameChars = file.name() != null ? file.name().length() : 0;
            reserve += 2 * nameChars + PER_FILE_RESERVE_EXTRA;
        }
        int[] budgets = allocate(n, Math.max(0, totalBudget - reserve));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            LoadedAttachment file = files.get(i);
            String name = file.name() != null && !file.name().isBlank()
                    ? file.name() : "attachment-" + (i + 1);
            String body = file.content() != null ? file.content() : "";
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("【文件：").append(name).append("】\n");
            int budget = budgets[i];
            if (body.length() > budget) {
                // 行边界回退截断；配额被开销挤到 0 时输出空（极端防御，保证总长上界）
                String shown = budget > 0 ? TextTruncator.truncateAtLineBoundary(body, budget) : "";
                sb.append(shown).append('\n');
                sb.append("[TRUNCATED] file=").append(name);
                if (file.attachmentId() != null) {
                    // 可寻址 ref：消费侧据此可经既有 REST 端点回取全文（见 LoadedAttachment javadoc）
                    sb.append(" id=").append(file.attachmentId());
                }
                sb.append(" shown=").append(shown.length())
                        .append(" total=").append(body.length())
                        .append(" reason=dep_content_limit");
            } else {
                sb.append(body);
            }
        }
        return sb.toString();
    }

    /**
     * 预算分配：主附件保底 + 次要最低配额。
     *
     * <p>n=1 时唯一附件独占全部预算；n≥2 时主附件 = max({@link #MAIN_MIN_BODY_CHARS},
     * pool − 次要最低×（n−1)），次要附件均分剩余（整除余数并回主附件）。
     * pool 不足以覆盖主附件保底时（文件名极长等极端场景）主附件退化为独占可用预算，
     * 保证 Σ配额 ≤ pool、渲染总长上界恒成立。</p>
     */
    private static int[] allocate(int n, int pool) {
        int[] budgets = new int[n];
        if (n == 1) {
            budgets[0] = pool;
            return budgets;
        }
        int main = Math.min(pool, Math.max(MAIN_MIN_BODY_CHARS, pool - MINOR_MIN_BODY_CHARS * (n - 1)));
        int rest = Math.max(0, pool - main);
        int minor = rest / (n - 1);
        budgets[0] = main + (rest - minor * (n - 1));
        for (int i = 1; i < n; i++) {
            budgets[i] = minor;
        }
        return budgets;
    }
}
