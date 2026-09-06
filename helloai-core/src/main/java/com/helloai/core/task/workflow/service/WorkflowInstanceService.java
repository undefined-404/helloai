package com.helloai.core.task.workflow.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.task.workflow.domain.WorkflowInstanceStatusView;
import com.helloai.core.task.workflow.entity.WorkflowInstance;

import java.util.Map;

/**
 * Workflow 实例服务（N-001，C1-S2：模板 + 参数 → 一次性物化 task/sub_task）。
 *
 * <p>实例化后脱离 Workflow 层（D1）：运行期由现有调度/执行/收敛链接管，
 * 本服务不再干预。实例状态纯查询聚合（D6-2，S3 提供聚合查询）。</p>
 */
public interface WorkflowInstanceService extends IService<WorkflowInstance> {

    /**
     * 实例化：取模板当前激活版本 → params 校验 → 参数渲染 → 物化 task/sub_task
     * （节点 → sub_task，节点依赖 → depends_on，context 单顶级键 workflow）→
     * 创建实例（绑定 task_id）→ 触发分发（ready 守卫拦依赖未就绪）→ task IN_PROGRESS。
     *
     * @param templateId 模板 ID（须 ACTIVE 且已发布版本）
     * @param params     实例化参数（占位符渲染输入）
     * @return 已落库实例（含 taskId）
     */
    WorkflowInstance createWorkflowInstance(Long templateId, Map<String, Object> params);

    /**
     * 聚合实例状态（纯查询投影，不落权威列，D6-2 方案 A）：
     * 由物化出的 task + 全部 sub_task 状态现算 RUNNING/DONE/FAILED/CANCELLED + 进度。
     * 展示用，无状态机、无约束力，绝不反向约束 task/sub_task 操作（D6-3）。
     *
     * @param instanceId 实例 ID
     */
    WorkflowInstanceStatusView aggregateStatus(Long instanceId);
}
