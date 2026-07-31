package com.helloai.core.shared.util;

import com.helloai.core.task.entity.SubTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务依赖拓扑排序工具（稳定 Kahn 入度法），从 PlannerAnalysisService
 * 私有实现提炼为公共工具：草案审阅/确认与主任务交付物 zip 聚合共用同一排序语义。
 *
 * <p>无前置依赖的根节点排在前，依赖项总在其依赖之后；dependsOn 存真实
 * sub_task id，仅按本批次内部依赖排序，批外/悬挂 id 视为无约束；同层节点
 * 保持入参原有相对顺序。对残留成环兜底：无法出队的节点按原顺序追加到
 * 末尾，绝不丢条目。</p>
 *
 * <p>依赖 id 统一走 {@link SubTask#dependsOnIdList()} 归一化读取
 * （JacksonTypeHandler 反序列化数字默认 Integer，直接遍历原字段会错失匹配）。</p>
 */
public final class SubTaskDependencyOrder {

    private SubTaskDependencyOrder() {
    }

    /** 按依赖拓扑排序，返回新列表（入参 size<=1 时原样返回）。 */
    public static List<SubTask> orderByDependency(List<SubTask> subTasks) {
        int n = subTasks.size();
        if (n <= 1) {
            return subTasks;
        }
        Map<Long, Integer> indexById = new HashMap<>();
        for (int i = 0; i < n; i++) {
            indexById.put(subTasks.get(i).getId(), i);
        }
        int[] inDegree = new int[n];
        List<List<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (Long depId : subTasks.get(i).dependsOnIdList()) {
                Integer depIdx = indexById.get(depId); // 仅统计本批次内部依赖
                if (depIdx != null) {
                    adjacency.get(depIdx).add(i); // 前置 → 后继
                    inDegree[i]++;
                }
            }
        }
        // 稳定 Kahn：按原下标升序将入度为 0 的节点入队
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                queue.add(i);
            }
        }
        List<SubTask> ordered = new ArrayList<>(n);
        boolean[] emitted = new boolean[n];
        while (!queue.isEmpty()) {
            int node = queue.poll();
            ordered.add(subTasks.get(node));
            emitted[node] = true;
            for (int next : adjacency.get(node)) {
                if (--inDegree[next] == 0) {
                    queue.add(next);
                }
            }
        }
        // 兜底：残留（异常成环/脏依赖）按原顺序补齐，绝不丢条目
        if (ordered.size() < n) {
            for (int i = 0; i < n; i++) {
                if (!emitted[i]) {
                    ordered.add(subTasks.get(i));
                }
            }
        }
        return ordered;
    }
}
