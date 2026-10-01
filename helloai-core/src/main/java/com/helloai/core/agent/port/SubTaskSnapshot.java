package com.helloai.core.agent.port;

import com.helloai.common.constant.SubTaskStatus;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 子任务只读快照 —— agent 域自有的数据契约，**零 task 实体泄漏**。
 *
 * <p><b>为什么需要它</b>：agent 域多处需要子任务数据，此前直接 import
 * {@code com.helloai.core.task.entity.SubTask}，构成 CODE_STYLE §6 反向依赖
 * （链路 {@code planner > review > task > agent > system > shared}，agent 低于 task）。
 * 改为由 <b>task 域把实体映射为本快照</b>后经服务/端口契约传入，agent 侧不再持有 task 实体。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2）</b>：消费方 {@code agent} <b>低于</b>提供方 {@code task}，
 * 故契约落消费方 {@code agent.port}；映射（{@code SubTask → SubTaskSnapshot}）由提供方
 * {@code task} 侧完成，实现侧依赖 {@code task → agent} 属 <b>顺向合法</b>。</p>
 *
 * <p><b>字段按需增长（重要约定）</b>：只纳入 agent 侧实际使用到的字段，避免把 task 实体整体
 * 抄成快照（那只是换名不换耦合）；<b>新增字段一律追加到末尾</b>——record 构造器是位置参数，
 * 追加可保持既有调用点的实参位置稳定，降低误传风险（测试侧建议改用 {@code builder()} 构造）。</p>
 *
 * <p><b>2026-10-01 W7 扩为「全量读投影」</b>：MCP 外部执行通道（{@code McpToolServiceImpl}）
 * 需要下发子任务全文（内容 / 交付物 / 验收标准 / 约束 / 不确定性 / 优先级 / 契约位 / 依赖 /
 * 截止时间 / 返工计数 / 乐观锁版本），故本快照补齐为其**完整读投影**。约定：</p>
 * <ul>
 *     <li><b>纯投影</b>——所有字段原样来自实体，映射时<b>不做业务判定</b>（唯一例外是
 *         {@code dependsOn} 取 {@code SubTask#dependsOnIdList()} 的 <b>Long 归一化</b>结果，
 *         这是实体自身的既定读取口径，非本层新增语义）；</li>
 *     <li><b>消费方必须只读</b>——快照不可变，任何写意图须经 {@code SubTaskCommandPort}
 *         或专门的不透明命令端口表达，禁止「改快照再回写」；</li>
 *     <li>{@code uncertainties} 为 {@link UncertaintySnapshot} 投影，<b>不在 agent 侧定义
 *         kind 常量或做归一化</b>，避免与 task 域拆解/注入/核验链形成双源（见该类 javadoc）。</li>
 * </ul>
 *
 * @param id              子任务 ID
 * @param status          子任务当前状态（消费方常按状态判定；枚举在 {@code common}，无域耦合）
 * @param taskId          所属主任务 ID
 * @param assignedAgentId 已分配的 Agent ID（可空 —— 未分配时为 {@code null}）
 * @param context         子任务上下文（可空；依赖产出回退读取 {@code context.lastExecution.output}）
 * @param title           子任务标题（产出物化按标题生成文件名；W6 追加）
 * @param content         执行内容与边界（W7 追加）
 * @param deliverable     交付物要求（W7 追加）
 * @param acceptance      验收标准（W7 追加）
 * @param constraints     执行约束（W7 追加）
 * @param priority        优先级 HIGH / MEDIUM / LOW（W7 追加）
 * @param isContract      契约位原始值（{@code 1}=契约子任务；W7 追加，原样投影不做布尔化）
 * @param deadline        截止时间，可空（W7 追加）
 * @param reworkCount     已发生返工次数，可空（W7 追加）
 * @param attemptTotal    累计尝试次数，可空（W7 追加，事件轨迹坐标用）
 * @param version         乐观锁版本，可空（W7 追加）
 * @param dependsOn       前置子任务 ID 列表（**已做 Long 归一化**，绝非 null；W7 追加）
 * @param uncertainties   不确定性申报投影（绝不为 null；W7 追加）
 */
@Builder
public record SubTaskSnapshot(Long id, SubTaskStatus status, Long taskId, Long assignedAgentId,
                              Map<String, Object> context, String title,
                              String content, String deliverable, String acceptance, String constraints,
                              String priority, Integer isContract, OffsetDateTime deadline,
                              Integer reworkCount, Integer attemptTotal, Integer version,
                              List<Long> dependsOn, List<UncertaintySnapshot> uncertainties) {
}
