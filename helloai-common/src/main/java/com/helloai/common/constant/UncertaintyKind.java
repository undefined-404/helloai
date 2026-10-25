package com.helloai.common.constant;

/**
 * 子任务不确定性申报类别（`G-011` 二值 → `TD-7` 四值）。
 *
 * <p><b>本枚举是 kind 值域的唯一定义源</b>：{@code task.entity.Uncertainty} 与
 * {@code task.port.UncertaintyDraft} 各自的 {@code KIND_*} 字符串常量均由此派生
 * （此前两处各写一份字面量、靠注释约束「必须逐字一致」，属「同一事实双写必分叉」的典型）。
 * 放在 {@code helloai-common} 是因为拆解侧（planner 域）必须识别 kind、
 * 而 planner 不能依赖 {@code task.entity}（反向依赖被架构守卫阻断）。</p>
 *
 * <p><b>四值语义（「无法确定」的程度分级）</b>：</p>
 * <ul>
 *   <li>{@link #ASSUMPTION}：已申报假设——拆解时做出的推断，执行者可自行验证 / 推翻，
 *       审查侧不因该假设存在而驳回（按「假设是否被产出尊重」核验）；</li>
 *   <li>{@link #UNCONFIRMED}：待确认缺口——现有信息无法证实，也**不知道该去哪儿查 / 怎么算**；
 *       执行者须先确认再动手，确认不了走既有 BLOCKED 链上报；</li>
 *   <li>{@link #TO_LOOK_UP}：待检索事实——**知道去哪儿查**（联网 / 附件 / 知识库），
 *       即 Magentic-One Task Ledger 的 {@code facts-to-look-up}；执行者应先检索再动手；</li>
 *   <li>{@link #TO_DERIVE}：待推导事实——**知道怎么算 / 怎么推**（口径计算、依赖分析、对照实验），
 *       即 {@code facts-to-derive}；执行者应先推导出中间结论再动手。</li>
 * </ul>
 *
 * <p><b>为什么要区分 {@code UNCONFIRMED} 与 {@code TO_LOOK_UP} / {@code TO_DERIVE}</b>：
 * 前者是「去问人」，后两者是「自己查 / 自己算」。统一标成 {@code UNCONFIRMED} 会让执行者
 * 把本可自助解决的缺口全部上报阻塞，是不必要的人工介入。</p>
 *
 * <p><b>非法值降级 {@code UNCONFIRMED}（fail-close，不丢弃）</b>：降级到更严语义比放松更安全；
 * {@code note} 是自由文本、标注本身有信息量，丢弃等于信息损失。</p>
 */
public enum UncertaintyKind {

    /** 已申报假设（执行者可自行验证 / 推翻；审查不因假设存在而驳回）。 */
    ASSUMPTION,

    /** 待确认缺口（不知去哪查 / 怎么算，须先确认再动手，确认不了走 BLOCKED 上报）。 */
    UNCONFIRMED,

    /** 待检索事实（知道去哪查：联网 / 附件 / 知识库；先检索再动手）。 */
    TO_LOOK_UP,

    /** 待推导事实（知道怎么算：口径计算 / 依赖分析 / 对照实验；先推导再动手）。 */
    TO_DERIVE;

    /** 降级目标：非法值统一归到此值（fail-close）。 */
    public static UncertaintyKind DEFAULT() {
        return UNCONFIRMED;
    }

    /** 是否为合法 kind（null / 空白 / 非法值均为 {@code false}）。 */
    public static boolean isKnown(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String upper = value.trim().toUpperCase();
        for (UncertaintyKind kind : values()) {
            if (kind.name().equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化：合法值返回其 {@code name()}，非法值回落 {@code UNCONFIRMED}。 */
    public static String normalize(String value) {
        return parse(value).name();
    }

    /** 解析：null / 空白 / 非法值回落 {@code UNCONFIRMED}。 */
    public static UncertaintyKind parse(String value) {
        if (value == null || value.isBlank()) {
            return UNCONFIRMED;
        }
        String upper = value.trim().toUpperCase();
        for (UncertaintyKind kind : values()) {
            if (kind.name().equals(upper)) {
                return kind;
            }
        }
        return UNCONFIRMED;
    }
}
