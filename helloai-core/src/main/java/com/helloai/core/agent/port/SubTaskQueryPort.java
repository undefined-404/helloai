package com.helloai.core.agent.port;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 子任务只读查询端口（agent 域消费，task 域实现）。
 *
 * <p>端口归属（{@code CODE_STYLE §7.2}）：消费方 agent 低于提供方 task（依赖链 planner>review>task>agent>system>shared），
 * 属「消费方低于提供方」⇒ 端口落消费方 {@code agent.port}、适配器落 task 域，实现侧 {@code task→agent} 顺向合法。
 * 一律经 {@link SubTaskSnapshot} 值对象传递而非 task 实体——返回实体只是把 service import 换成 entity import，
 * {@code agent→task} 反向依赖计数不下降；快照字段按需增长。实现见 {@code SubTaskQueryPortAdapter}（薄委托，不改 SQL/参数顺序/语义）。</p>
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
     * 按子任务主键加行级锁读取快照（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>用于「读现状 + 紧接写入」需原子化的路径（如执行命令创建二次判重），避免并发重复发命令。
     * 语义同 {@code SubTaskService#getByIdForUpdate}：不存在返回 {@code null}。
     * <b>必须在已开启的事务内调用</b>：提供方只发 {@code SELECT ... FOR UPDATE}、不自开事务（适配器不带 {@code @Transactional}），
     * 行锁随调用方事务存续；若另开独立事务或方法返回即提交，锁立刻释放、失去互斥意义。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 加锁读取到的子任务快照；不存在返回 {@code null}
     */
    SubTaskSnapshot findByIdForUpdate(Long subTaskId);

    /**
     * 列出最近有变更的子任务（事件对账候选源）。
     *
     * <p>语义同 {@code SubTaskService#listRecentlyChanged}，返回类型收敛为 {@link SubTaskSnapshot}（对账只用 id/status）。
     * 对账必须以业务表为候选源：事件是业务状态的投影，只扫最近变更子任务校验其是否发出匹配终态事件；
     * 若以事件流为候选源，埋点失败/缺失的子任务永不进对账视野。</p>
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
     * 判断子任务的前置是否全部就绪（前置均 DONE；无前置恒就绪）。
     *
     * <p>形参是 ID 而非快照：判定除 {@code dependsOn} 外还要查前置状态计数（{@code count(status=DONE)}），
     * 属提供方口径（{@code SubTaskService#isReady} 被内部分发链复用）；拆成「消费方拿快照自行统计」会把就绪口径复制进消费方，
     * 故整体不透明，代价是提供方多一次主键读（认领低频可接受）。</p>
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
     * 判定子任务是否属「执行密集」（内容/验收/交付物含本机操作信号词）。
     *
     * <p>形参是 ID 而非快照：判定口径单源在提供方（{@code SubTaskDispatchService.isExecutionDense} 的 §6.52 信号词表），
     * 消费方不得自行复现（历史教训：各入口各自实现导致判定不一致），故只问结论、词表与匹配逻辑不外泄，
     * 代价是提供方多一次主键读（仅走执行密集预检分支时）。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 命中执行密集信号返回 {@code true}；子任务不存在返回 {@code false}
     */
    boolean isExecutionDense(Long subTaskId);

    /**
     * 判定子任务是否「需读原始附件」（`LOG-20261011-007` A 能力闸信号）。
     *
     * <p>语义：内容/验收/交付物两侧信号共现——「原始文件/原始字段」源侧措辞 <b>且</b>「逐项核对/逐项标注」动作侧措辞
     * ⇒ 必须打开用户提供的结构化文件本体（xlsx/docx/pdf）逐项比对，仅凭预内联依赖文本摘要完不成。
     * 形参是 ID 而非快照：口径单源在提供方（{@code SubTaskDispatchService.isAttachmentReadDense} 词表），消费方不得复现，
     * 故只问结论、词表不外泄，代价是提供方多一次主键读（仅走需读原始附件预检分支时）。
     * 与 {@link #isExecutionDense(Long)} 是不同信号类、不可互替：后者治「需在本机跑东西」，本判定治「需打开结构化文件本体」
     * （只解码 UTF-8 的内部读附件工具读不出二进制列），同一子任务可能两者都命中。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 命中「需读原始附件」信号返回 {@code true}；子任务不存在返回 {@code false}
     */
    boolean requiresOriginalAttachment(Long subTaskId);

    /**
     * 取「认领准入」所需的白名单 + 必需技能约束（MCP 认领闸门 P1）。
     *
     * <p>整体不透明：白名单（任务 {@code agent_policy.executorAgentIds}）与技能（子任务级 ∪ 任务级 {@code required_skills}）
     * 口径单源在提供方 task 域，与 {@link #mergeSkills(Long)} 同源（复用派发链同款合并），消费方不复制规则。
     * 返回 {@link SubTaskClaimConstraint} 而非白名单/技能两平行方法：二者是同一次准入判定的两维度，
     * 合并成值对象让「不约束」以 {@code null} 单点表达，避免消费方分别判空、口径漂移。</p>
     *
     * @param subTaskId 子任务 ID
     * @return 认领约束；无约束（白名单与技能均空）或子任务不存在时返回 {@code null}
     */
    SubTaskClaimConstraint claimConstraint(Long subTaskId);
}
