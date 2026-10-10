package com.helloai.core.system.port;

/**
 * 「平台是否静默」的只读端口（system 域消费，task 域实现）—— REF-2.3b 在线恢复拒的判据之一。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方是 <b>system</b>、
 * 提供方是 <b>task</b>；按依赖链 {@code planner > review > task > agent > system > shared}，
 * system <b>低于</b> task ⇒ 属「消费方低于提供方」情形 ⇒ 端口落<b>消费方 {@code system.port}</b>、
 * 适配器落提供方 task 域，实现侧依赖 {@code task → system} 属<b>顺向合法</b>。
 * （与 {@link LlmProviderModelProfile} 同处；同构先例见 {@code agent.port.SubTaskStatsPort}。）</p>
 *
 * <p><b>为什么需要它</b>：恢复的真相前置之一是「平台确实停机了」。计划 `REF-2.4` 定性为
 * <b>停机恢复</b>，而"停机"是**人的约定**、不是机器事实 —— 恢复是破坏性操作，不能只靠调用方自觉：
 * 平台若仍在跑在飞任务，恢复会把它们正在写的库覆盖掉。故必须由**掌握在飞事实的域**来回答。</p>
 *
 * <p><b>为什么不直查</b>：在 {@code system} 域 import task 域的 Mapper / 实体，会同时撞两条
 * 冻结红线（{@code scripts/ci/arch-baseline.txt} 组 1、组 2a，均「严格拦截、目标恒为 0」）：
 * {@code system->task=0} 与 {@code system->task.mapper=0}。</p>
 *
 * <p><b>为什么只返回计数而非任务列表</b>：消费方只关心"有没有在飞"，不读任务任何字段。
 * 若返回 {@code task.entity.SubTask}，消费方仍要 import task 实体，红线计数不会下降
 * —— 只是把 mapper import 换成 entity import（同 {@code SubTaskStatsPort} 的取舍）。</p>
 */
public interface RestoreQuiescencePort {

    /**
     * 探测当前在飞（占用额度口径：{@code ASSIGNED / IN_PROGRESS / REWORK}）的子任务数。
     *
     * <p>口径与 {@code ConcurrencyQuotaService} / {@code InFlightDbQuotaService} 一致 ——
     * 用宽口径（如"未完成任务总数"）会让恢复在正常运行时也被拒，把守卫变成噪声。</p>
     *
     * @return 在飞子任务数；绝不返回负数
     */
    int inFlightSubTaskCount();
}
