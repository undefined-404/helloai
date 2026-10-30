package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话内技能执行的运行参数（前缀 {@code helloai.skill-execution}）。
 *
 * <p>默认值的取法：技能执行实测一次 377s（LLM 两轮 + 注入 + 三段门禁 + 回读 + 落库），
 * 加上「重生成 spec 再重试」的最坏分支可到 170s，LLM 抖动再叠加，
 * 所以<b>服务端超时上限给到 600s</b>——它不是「预期耗时」，是「兜底不挂死」的闸。
 * 超时落 {@code FAILED}，用户能看见原因，不会永远转圈。</p>
 *
 * <p>线程池默认 2：沙箱按会话复用（槽键 = -conversationId），同一会话本就不该并发，
 * 多池位只用于「不同会话各跑各的」。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.skill-execution")
public class SkillExecutionProperties {

    /** 执行线程池大小（默认 2；沙箱按会话复用，池位不需大）。 */
    private int poolSize = 2;

    /** 执行队列容量：超出后由 CallerRuns 兜底（宁可提交请求慢一点，也不静默丢执行任务）。 */
    private int queueCapacity = 50;

    /** 服务端硬超时上限（秒），到点判失败。 */
    private int timeoutSeconds = 600;

    /**
     * 失败原因摘要的最大长度（与 {@code chat_skill_run.error_message} 列宽 1000 对齐，留余量）。
     */
    private int errorMaxLength = 900;
}
