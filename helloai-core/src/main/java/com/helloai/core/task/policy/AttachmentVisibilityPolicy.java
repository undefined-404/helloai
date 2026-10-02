package com.helloai.core.task.policy;

import com.helloai.common.constant.AttachmentVisibility;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskAgentMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 附件可见性判据 —— <b>所有"agent 能读哪些附件"的唯一入口</b>。
 *
 * <p>设计依据：{@code doc/design/HelloAI Task-Team 与附件可见性设计.md} §4。
 * 任何读附件的通道（REST 附件端点、将来的 MCP 读取工具）都必须经本类判定，
 * <b>不得各自查表或比对任务 ID</b>——判据分散即漂移风险。</p>
 *
 * <p><b>为什么"不写死层级"</b>：判据只引用抽象 —— {@link #rootTaskIdOf} 解析出的「根任务」
 * + {@code TaskAgentMemberService.isMember} 的成员关系 + {@code visibility} 枚举，
 * <b>绝不出现具体的 taskId / subTaskId 常量比对</b>。故未来「二次分解（子任务的子任务）」
 * 「一个需求拆多个主任务」等层级演进，只需扩展 {@link #rootTaskIdOf}，本类其余逻辑零改动。</p>
 *
 * <p><b>为什么可见性不绑"角色"</b>：角色（PLANNER/EXECUTOR/REVIEWER）是 agent 的<b>全局属性</b>
 * （同一 agent 可同时是多个任务的 REVIEWER），用角色作判据等同于跨任务过度授权——
 * 与"team 不能当授权边界"是同一个坑。判据必须落在<b>任务级成员关系</b>上。</p>
 *
 * <p><b>安全底线</b>：本类不得回退到 G-014 T04b 之前的状态（当时"任意有效 Agent API Key
 * 可凭 attachmentId 读取任意子任务附件正文"）。判定恒为"声明范围 × 成员关系"，不放行全局读。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentVisibilityPolicy {

    private final TaskAgentMemberService taskAgentMemberService;
    private final SubTaskService subTaskService;

    /**
     * 判某 agent 是否可读某附件。
     *
     * @param agentId    Agent ID（agent 通道的 {@code _authId}）；{@code null} 恒不可读
     * @param attachment 附件实体（需已加载 {@code subTaskId} / {@code uploaderAgentId} / {@code visibility}）
     * @return 可读返回 {@code true}
     */
    public boolean canRead(Long agentId, Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        return canRead(agentId, attachment.getSubTaskId(),
                attachment.getUploaderAgentId(), attachment.getVisibility());
    }

    /**
     * 可见性判据核心（按已解析出的三元组判定，供按 attachmentId 与按 subTaskId 两条入口复用）。
     *
     * <p>判定顺序：① 上传者恒可读自传 → ② PERSONAL 到此为止 → ③ PUBLIC（预留，fail-closed）
     * → ④ TASK 走根任务成员关系。</p>
     *
     * @param visibility      可见范围；{@code null} 按 TASK 处理（与 DB 列 {@code NOT NULL DEFAULT 'TASK'} 对齐）
     * @param uploaderAgentId 上传者 Agent ID，可为 {@code null}（平台侧/人工上传）
     */
    public boolean canRead(Long agentId, Long subTaskId, Long uploaderAgentId,
                           AttachmentVisibility visibility) {
        if (agentId == null) {
            return false;
        }
        // ① 上传者恒可读自传：覆盖 PERSONAL 语义，也覆盖 TASK 下"被改派换下后仍能读自己旧产出"
        if (uploaderAgentId != null && uploaderAgentId.equals(agentId)) {
            return true;
        }
        AttachmentVisibility vis = visibility != null ? visibility : AttachmentVisibility.TASK;
        // ② 仅上传者：非上传者到此为止
        if (vis == AttachmentVisibility.PERSONAL) {
            return false;
        }
        // ③ PUBLIC 当前仅预留枚举与 DB CHECK，**未启用**：fail-closed 拒绝并告警，
        //    避免"数据库被手工改成 PUBLIC 即全局可读"的隐式越权。启用时须在此补 agent ACTIVE 校验。
        if (vis == AttachmentVisibility.PUBLIC) {
            log.warn("附件 visibility=PUBLIC 未启用（fail-closed 拒绝读取）: agentId={}, subTaskId={}",
                    agentId, subTaskId);
            return false;
        }
        // ④ TASK：请求者须为附件所属「根任务」的 Task-Team 在队成员
        Long taskId = rootTaskIdOf(subTaskId);
        return taskId != null && taskAgentMemberService.isMember(taskId, agentId);
    }

    /**
     * 解析附件所属的<b>根任务</b>——判据中唯一的"层级相关"逻辑，刻意收敛在此一处。
     *
     * <p>当前层级固定为「task → sub_task」，故为 {@code subTask.taskId}；将来若出现
     * 「子任务的子任务」（二次分解）或「一个需求拆多个主任务」，只需改本方法
     * （例如沿 parent 链上溯到根、或解析为"需求域/任务组"），<b>调用方零改动</b>。</p>
     *
     * @return 根任务 ID；子任务不存在返回 {@code null}
     */
    public Long rootTaskIdOf(Long subTaskId) {
        if (subTaskId == null) {
            return null;
        }
        SubTask subTask = subTaskService.getById(subTaskId);
        return subTask != null ? subTask.getTaskId() : null;
    }

    /** 便捷重载：直接按附件解析根任务。 */
    public Long rootTaskIdOf(Attachment attachment) {
        return attachment != null ? rootTaskIdOf(attachment.getSubTaskId()) : null;
    }
}
