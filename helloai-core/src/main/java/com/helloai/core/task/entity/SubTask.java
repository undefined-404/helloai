package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_task")
public class SubTask extends BaseEntity {

    private Long taskId;
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;

    @TableField("status")
    private SubTaskStatus status;

    private Long assignedAgentId;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scoreFactors;

    private Integer compositeScore;
    private String scoreGrade;
    private OffsetDateTime deadline;
    private Integer reworkCount;
    private OffsetDateTime completeTime;

    @Version
    private Integer version;

    private Integer timeoutCount;

    /**
     * N11 阈值回退：当前子任务已发生的"外部→LLM"回退次数。
     *
     * <p>每次 ExternalAgentFallbackTask 触发对当前子任务的重新分发，
     * 都会把该值 +1；可用于监控 / 限流（如回退 3 次后直接放弃或转人工）。</p>
     */
    private Integer externalFallbackCount;

    /**
     * 重分配尝试次数：所有类型的重分配（离线重派、超时回收、
     * N11回退、阻塞重试）都计数。
     *
     * <p>达到 {@code helloai.dispatch.max-reassign-attempts}（默认 5）后，
     * 子任务将被直接标记为 CANCELLED，不再进入重分配链，防止无限重试死循环。</p>
     */
    private Integer reassignAttemptCount;

    /**
     * 依赖的子任务 id 数组：同 Task 内的前置子任务，
     * 全部 DONE 后本任务才可被分发（ready 语义）；空数组=无依赖。
     *
     * <p>注意：JacksonTypeHandler 反序列化 JSON 数组数字默认是 Integer，
     * 读取依赖 id 时必须走 {@link #dependsOnIdList()} 做 Long 归一化，
     * 不要直接遍历本字段强转 Long。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> dependsOn;

    /**
     * 依赖 id 归一化读取：把 Jackson 反序列化出的 Integer/Long/String 统一转为 Long。
     * 永不返回 null（空依赖返回空列表）。
     *
     * <p>String 分支的来源：全局 ObjectMapper 注册了 Long→String 序列化
     * （JacksonConfig，防前端精度丢失），历史数据的 depends_on 可能存成
     * 字符串数组，必须兼容读取，否则 ready 守卫会把有依赖节点误判为就绪。</p>
     */
    public List<Long> dependsOnIdList() {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(dependsOn.size());
        for (Object item : (List<?>) (List<?>) dependsOn) {
            if (item instanceof Number) {
                ids.add(((Number) item).longValue());
            } else if (item instanceof String str && !str.isBlank()) {
                ids.add(Long.parseLong(str.trim()));
            }
        }
        return ids;
    }
}
