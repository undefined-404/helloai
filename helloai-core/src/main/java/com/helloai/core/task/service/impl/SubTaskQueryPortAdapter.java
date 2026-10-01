package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@link SubTaskQueryPort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：转发到 {@link SubTaskService}，<b>不改动任何 SQL、参数顺序与语义</b>；
 * 唯一附加动作是把返回的 task 实体映射为 {@link SubTaskSnapshot}
 * （见 {@link SubTaskSnapshotMapper}）。实现侧依赖 {@code task → agent.port} 属顺向合法。</p>
 */
@Service
@RequiredArgsConstructor
public class SubTaskQueryPortAdapter implements SubTaskQueryPort {

    private final SubTaskService subTaskService;

    @Override
    public List<SubTaskSnapshot> listRecentlyChanged(OffsetDateTime since, int limit) {
        return SubTaskSnapshotMapper.toSnapshots(subTaskService.listRecentlyChanged(since, limit));
    }
}
