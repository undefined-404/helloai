package com.helloai.core.task.adapter;

import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.spec.ExecutionRecordParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link TaskRunningSpecPort} 的提供方实现（task 域）。
 *
 * <p>读路径为纯薄委托：转发到 {@link TaskRunningSpecService#findRecord}，仅把
 * {@code ExecutionRecord} 收敛为消费方实际需要的摘要字段。写路径
 * （{@link #parseAndAppendExecutionRecord}）承接原 {@code ExecutionResultHandler}
 * 内联的「解析 + fallback + 回填」整块逻辑 —— 因其依赖的
 * {@link ExecutionRecordParser} / {@link ExecutionRecord} 均为 task 域协议类型，
 * 留在消费方会构成 {@code agent → task} 反向依赖。实现侧依赖 {@code task → agent.port} 属顺向合法。</p>
 *
 * <p><b>事务边界</b>：本适配器<b>不另开事务</b>（薄委托），写入随<b>调用方事务</b>
 * 提交/回滚 —— 与原实现在 {@code ExecutionResultHandler#handleReport} 事务内回填的边界一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunningSpecPortAdapter implements TaskRunningSpecPort {

    /** fallback summary 截断长度（与原内联实现逐字一致）。 */
    private static final int FALLBACK_SUMMARY_MAX_CHARS = 200;

    private final TaskRunningSpecService taskRunningSpecService;

    @Override
    public String findExecutionSummary(Long taskId, Long subTaskId) {
        ExecutionRecord record = taskRunningSpecService.findRecord(taskId, subTaskId);
        return record != null ? record.summary() : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>纯薄委托 —— 全局段文本的拼装口径（Baseline + Context Summary）整体在
     * {@link TaskRunningSpecService} 内，本层不解释、不裁剪。</p>
     */
    @Override
    public String buildExecutorPromptSection(Long taskId) {
        return taskRunningSpecService.buildExecutorPromptSection(taskId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>三步顺序与原内联块逐字一致：解析成功 ⇒ 用解析结果；解析失败但输出非空白 ⇒
     * 用「前 200 字符 + {@code ...}」fallback；输出空白 ⇒ 不落记录。
     * 异常<b>不在此吞掉</b>——「降级不阻断主链路」由调用方 try-catch 表达。</p>
     */
    @Override
    public void parseAndAppendExecutionRecord(Long taskId, Long subTaskId, String title,
                                             Long agentId, String rawOutput) {
        ExecutionRecord record = ExecutionRecordParser.parse(rawOutput, subTaskId, title, agentId);
        if (record != null) {
            taskRunningSpecService.appendExecutionRecord(taskId, record);
            return;
        }
        if (rawOutput == null || rawOutput.isBlank()) {
            return;
        }
        String fallbackSummary = rawOutput.length() > FALLBACK_SUMMARY_MAX_CHARS
                ? rawOutput.substring(0, FALLBACK_SUMMARY_MAX_CHARS) + "..." : rawOutput;
        log.warn("EXECUTION_RECORD 解析失败，使用 fallback summary: subTaskId={}", subTaskId);
        ExecutionRecord fallback = ExecutionRecord.builder()
                .subTaskId(subTaskId)
                .title(title)
                .agentId(agentId)
                .summary(fallbackSummary)
                .build();
        taskRunningSpecService.appendExecutionRecord(taskId, fallback);
    }
}
