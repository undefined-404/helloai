package com.helloai.core.task.adapter;

import com.helloai.core.system.port.RestoreQuiescencePort;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link RestoreQuiescencePort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：转发到 {@link SubTaskService#countInFlightAll()}，<b>不改动任何 SQL、
 * 参数顺序与语义</b>（委托层不做转换、不吞异常）。实现侧依赖 {@code task → system.port}
 * 属 §6 顺向合法（task 高于 system）；端口落消费方 {@code system.port}，符合 §7.2 归属判据。</p>
 *
 * <p>同构先例：{@code task.adapter.SubTaskStatsPortAdapter}（实现 {@code agent.port.SubTaskStatsPort}）。</p>
 */
@Service
@RequiredArgsConstructor
public class RestoreQuiescencePortAdapter implements RestoreQuiescencePort {

    private final SubTaskService subTaskService;

    @Override
    public int inFlightSubTaskCount() {
        return subTaskService.countInFlightAll();
    }
}
