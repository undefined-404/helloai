package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collection;
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
    public SubTaskSnapshot findById(Long subTaskId) {
        return SubTaskSnapshotMapper.toSnapshot(subTaskService.getById(subTaskId));
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：本方法只转发 {@code SELECT ... FOR UPDATE}，
     * 行锁须随<b>调用方事务</b>存续；在此另开事务或提前提交会立刻释放锁、失去互斥意义
     * （与 {@code SubTaskService#getByIdForUpdate} 的既有约定一致）。</p>
     */
    @Override
    public SubTaskSnapshot findByIdForUpdate(Long subTaskId) {
        return SubTaskSnapshotMapper.toSnapshot(subTaskService.getByIdForUpdate(subTaskId));
    }

    @Override
    public List<SubTaskSnapshot> listRecentlyChanged(OffsetDateTime since, int limit) {
        return SubTaskSnapshotMapper.toSnapshots(subTaskService.listRecentlyChanged(since, limit));
    }

    @Override
    public List<SubTaskSnapshot> listByIds(Collection<Long> subTaskIds) {
        if (subTaskIds == null || subTaskIds.isEmpty()) {
            return List.of();
        }
        return SubTaskSnapshotMapper.toSnapshots(subTaskService.listByIds(subTaskIds));
    }

    /**
     * {@inheritDoc}
     *
     * <p>整体不透明：就绪口径（前置全 DONE / 空依赖恒就绪）由 {@link SubTaskService#isReady}
     * 单源持有，本适配器不多加任何判定，仅补一次主键读把实体交给它。</p>
     */
    @Override
    public boolean isReady(Long subTaskId) {
        return subTaskService.isReady(subTaskService.getById(subTaskId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>整体不透明：合并规则（子任务级在前 ∪ 任务级、去重保序）由
     * {@link SubTaskService#mergeSkills} 单源持有，本适配器不做任何合并。</p>
     */
    @Override
    public List<String> mergeSkills(Long subTaskId) {
        List<String> merged = subTaskService.mergeSkills(subTaskService.getById(subTaskId));
        return merged != null ? merged : List.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>整体不透明：执行密集信号词表与匹配逻辑由
     * {@link SubTaskDispatchService#isExecutionDense} 单源持有，
     * 本适配器只把「ID → 实体」这一步补上，判定口径零复制。子任务不存在时返回 {@code false}
     * （与「无信号」同义，避免消费方再判空）。</p>
     */
    @Override
    public boolean isExecutionDense(Long subTaskId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        return subTask != null && SubTaskDispatchService.isExecutionDense(subTask);
    }
}
