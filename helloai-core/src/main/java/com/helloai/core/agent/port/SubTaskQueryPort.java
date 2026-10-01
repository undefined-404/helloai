package com.helloai.core.agent.port;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 子任务只读查询端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方是 <b>agent</b>、
 * 提供方是 <b>task</b>；按依赖链 {@code planner > review > task > agent > system > shared}，
 * agent <b>低于</b> task ⇒ 属「消费方低于提供方」情形 ⇒ 端口落<b>消费方 {@code agent.port}</b>、
 * 适配器落提供方 task 域，实现侧依赖 {@code task → agent} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么返回快照而不是实体</b>：若返回 {@code task.entity.SubTask}，消费方仍要
 * import task 实体，{@code agent → task} 反向依赖计数<b>不会下降</b>（只是把 service import
 * 换成 entity import）。故一律经 {@link SubTaskSnapshot} 值对象传递；快照字段<b>按需增长</b>。</p>
 *
 * <p>实现见 {@code task.service.impl.SubTaskQueryPortAdapter}（薄委托 {@code SubTaskService}，
 * <b>不改动任何 SQL、参数顺序与语义</b>）。</p>
 */
public interface SubTaskQueryPort {

    /**
     * 按 ID 读取单个子任务快照。
     *
     * <p>语义与 {@code SubTaskService#getById(Long)} 一致：不存在返回 {@code null}
     * （消费方原本就是「取实体后判空」，故保持 null 语义而非 {@code Optional}）。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 子任务快照；不存在返回 {@code null}
     */
    SubTaskSnapshot findById(Long subTaskId);

    /**
     * 按子任务主键<b>加行级锁</b>读取快照（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>用于「读现状 + 紧接写入」需要原子化的路径（如执行命令创建时的二次判重），
     * 避免同一子任务上并发重复发命令。语义与 {@code SubTaskService#getByIdForUpdate(Long)}
     * 逐字一致：不存在返回 {@code null}。</p>
     *
     * <p><b>⚠️ 必须在已开启的事务内调用</b>：提供方实现<b>只发 {@code SELECT ... FOR UPDATE}、
     * 不自行开启事务</b>（适配器方法亦<b>不带</b> {@code @Transactional}）——行锁随<b>调用方事务</b>
     * 的存续而保持；若在提供方另开 REQUIRED 之外的独立事务或方法返回即提交，锁会立刻释放、
     * 失去互斥意义。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 加锁读取到的子任务快照；不存在返回 {@code null}
     */
    SubTaskSnapshot findByIdForUpdate(Long subTaskId);

    /**
     * 列出最近有变更的子任务（Phase 0 B3 事件对账候选源）。
     *
     * <p>语义与 {@code SubTaskService#listRecentlyChanged(OffsetDateTime, int)} 逐字一致，
     * 仅把返回类型由 task 实体收敛为 {@link SubTaskSnapshot}（对账消费方实际只用
     * {@code id} 与 {@code status} 两个字段）。</p>
     *
     * <p>事件是业务状态的投影，对账必须<b>以业务表为候选源</b>：只扫描最近变更的子任务，
     * 校验其是否发出了与当前状态匹配的终态事件；若以事件流为候选源，埋点失败/缺失的
     * 子任务永远不会进入对账视野。</p>
     *
     * @param since 只统计 update_time &gt;= since 的子任务（对账窗口）
     * @param limit 返回上限（窗口内变更量超限时本轮截断，下一轮继续）
     * @return 最近变更子任务快照列表（按更新时间倒序，绝不返回 null）
     */
    List<SubTaskSnapshot> listRecentlyChanged(OffsetDateTime since, int limit);

    /**
     * 按 ID 集合批量读取子任务快照（W7 新增，供前置产出装载）。
     *
     * <p>语义与 {@code SubTaskService#listByIds(Collection)} 一致：入参为空/为 {@code null}
     * 时返回空列表（绝不返回 {@code null}）；命中不到的 ID 直接缺席（不补空位），
     * 由消费方按原 ID 顺序自行对号。</p>
     *
     * @param subTaskIds 子任务 ID 集合
     * @return 命中的子任务快照列表（绝不返回 {@code null}）
     */
    List<SubTaskSnapshot> listByIds(Collection<Long> subTaskIds);

    /**
     * 判断子任务的前置是否<b>全部就绪</b>（前置均 DONE；无前置恒为就绪）。
     *
     * <p><b>为什么形参是 ID 而不是快照</b>：该判定除依赖 {@code dependsOn} 外还要查一次
     * 前置状态计数（{@code count(status=DONE)}），属**提供方持有的口径**
     * （{@code SubTaskService#isReady(SubTask)} 同时被内部分发链复用）。若把它拆成
     * 「消费方拿快照里的 dependsOn → 自行统计」会把就绪口径复制进消费方，故保持
     * **整体不透明**：消费方只问「这个子任务是否就绪」，提供方内部自行取数判定。
     * 代价是提供方多一次主键读（认领路径低频，可接受）。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 前置全部 DONE（或无前置）返回 {@code true}；子任务不存在返回 {@code false}
     */
    boolean isReady(Long subTaskId);

    /**
     * 取「子任务级 ∪ 任务级」技能标签合并清单（去重保序，子任务级在前）。
     *
     * <p>与 {@link #isReady(Long)} 同理：合并需读任务级技能（另一张表/另一个查询），
     * 属提供方口径，故整体不透明暴露，消费方不做任何合并。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 合并后的技能清单（绝不返回 {@code null}）
     */
    List<String> mergeSkills(Long subTaskId);

    /**
     * 判定子任务是否属「<b>执行密集</b>」（内容 / 验收 / 交付物含本机操作信号词）。
     *
     * <p><b>为什么形参是 ID 而不是快照</b>：判定口径**单源在提供方**
     * （{@code SubTaskDispatchService.isExecutionDense} 的 §6.52 信号词表），
     * 消费方不得自行复现——历史教训正是「各入口各自实现导致判定不一致」。
     * 故消费方只问结论，提供方自读文本判定（信号词表与匹配逻辑都不外泄）。
     * 代价是提供方多一次主键读（仅走「执行密集预检」分支时发生）。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 命中执行密集信号返回 {@code true}；子任务不存在返回 {@code false}
     */
    boolean isExecutionDense(Long subTaskId);
}
