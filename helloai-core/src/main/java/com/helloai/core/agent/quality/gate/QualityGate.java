package com.helloai.core.agent.quality.gate;

/**
 * 质量判定闸门统一契约（目标架构 B4「Quality Gate 泛化」/ RM12）。
 *
 * <p><b>为什么放 agent 域</b>：消费方为 {@code review} / {@code agent} / {@code planner} 三域，
 * 其中最低位是 {@code agent}（依赖链 {@code planner > review > task > agent > system > shared}，
 * 顺向 = 高层依赖低层）⇒ 契约放 agent 域即可被三者<b>顺向</b>实现，
 * 无需下沉到叶子域 {@code shared}（§7.2：不默认放 shared）。</p>
 *
 * <p><b>为何用泛型输入</b>：各闸门的判定输入天然不同（报告正文 / 核验历史 / 检索结果…），
 * 故以 {@code I} 声明各自输入类型，避免引入弱类型的「万能上下文」对象。
 * 本契约统一的只是<b>结论契约</b>（{@link GateDecision}）与<b>可观测标识</b>（{@link #name()}）——
 * 拦截型（{@link GateOutcome#BLOCK}）与建议型（{@link GateOutcome#ADVISE}）由结论表达，
 * <b>不强迫语义不同的判定共用一个方法签名</b>（§7.3 禁止无谓 Adapter）。</p>
 *
 * <p><b>实现纪律</b>：收口期一律<b>保持既有判定逻辑逐字不动</b>；
 * 输入缺失或不适用时返回 {@link GateDecision#skip()}，异常自行降级、<b>不得外抛</b>。
 *
 * @param <I> 判定输入类型
 */
public interface QualityGate<I> {

    /** 闸门标识（日志 / 事件归因用；全局唯一、小写下划线）。 */
    String name();

    /** 执行判定；输入缺失 / 不适用返回 {@link GateDecision#skip()}。 */
    GateDecision evaluate(I input);
}
