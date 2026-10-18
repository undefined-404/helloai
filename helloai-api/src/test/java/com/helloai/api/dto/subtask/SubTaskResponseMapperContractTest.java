package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link SubTaskResponseMapper} 契约守卫（2026-10-18，`LOG-20261018-018` 的结构性防复发）。
 *
 * <p><b>守的是什么</b>：手写映射最大的风险不是写错，而是<b>漏写</b>——实体加了一个字段，
 * 映射没跟上，接口就少一个字段，而<b>编译器一句都不说</b>。
 * 本轮 `composite_score / score_grade / score_factors` 就是这样漏的：表里有值、
 * 列表接口没有，用户只能看到一个恒为 `-` 的评分列。</p>
 *
 * <p><b>怎么守</b>：把「实体字段」与「响应字段 ∪ 显式不暴露清单」做全等检查。
 * 实体一加字段而没做选择，本用例立刻红，并在断言消息里写清两条出路。</p>
 */
@DisplayName("SubTaskResponseMapper 字段覆盖契约")
class SubTaskResponseMapperContractTest {

    /**
     * 响应里存在、但实体没有对应字段的**冗余展示字段**：由 Controller 批量回填
     * （{@code attachTaskTitles} / {@code attachAgentNames}），不在映射器职责内。
     */
    private static final Set<String> BACKFILLED_BY_CONTROLLER = Set.of("taskTitle", "assignedAgentName");

    @Test
    @DisplayName("实体每个字段都必须「映射出去」或「登记为不暴露」，不允许悄悄漏掉")
    void everyEntityFieldIsMappedOrHidden() {
        Set<String> entityFields = fieldNames(SubTask.class);
        Set<String> responseFields = fieldNames(SubTaskResponse.class);

        Set<String> unmapped = new LinkedHashSet<>();
        for (String field : entityFields) {
            if (SubTaskResponseMapper.NOT_EXPOSED.contains(field)) {
                continue;
            }
            String target = SubTaskResponseMapper.RENAMED.getOrDefault(field, field);
            if (!responseFields.contains(target)) {
                unmapped.add(field);
            }
        }

        if (!unmapped.isEmpty()) {
            fail("""
                    以下 SubTask 实体字段既没有映射到 SubTaskResponse，也没有登记在 NOT_EXPOSED：%s
                    二选一：
                      ① 需要对外 → 在 SubTaskResponse 加字段 + 在 SubTaskResponseMapper.toResponse 补一行
                         （若字段名不同，同时登记到 RENAMED）；
                      ② 不对外   → 加入 SubTaskResponseMapper.NOT_EXPOSED，并写明为什么不该暴露。
                    注意：这类遗漏编译器不会报错，正是 LOG-20261018-018（评分列恒空）的成因。
                    """.formatted(unmapped));
        }
    }

    @Test
    @DisplayName("NOT_EXPOSED 不得留有已不存在的实体字段（防清单腐化）")
    void hiddenRegistryHasNoStaleEntries() {
        Set<String> entityFields = fieldNames(SubTask.class);
        Set<String> stale = new LinkedHashSet<>(SubTaskResponseMapper.NOT_EXPOSED);
        stale.removeAll(entityFields);

        assertThat(stale)
                .as("NOT_EXPOSED 里存在实体已没有的字段，请删除这些登记项")
                .isEmpty();
    }

    @Test
    @DisplayName("RENAMED 的实体侧字段必须真实存在，且目标字段在响应里存在")
    void renamedEntriesAreValid() {
        Set<String> entityFields = fieldNames(SubTask.class);
        Set<String> responseFields = fieldNames(SubTaskResponse.class);

        SubTaskResponseMapper.RENAMED.forEach((entityField, responseField) -> {
            assertThat(entityFields).as("RENAMED 源字段不存在: %s", entityField).contains(entityField);
            assertThat(responseFields).as("RENAMED 目标字段不存在: %s", responseField).contains(responseField);
        });
    }

    @Test
    @DisplayName("响应里多出的字段只能是 Controller 回填项（防拼错字段名）")
    void responseFieldsWithoutEntitySourceAreBackfilledOnly() {
        Set<String> entityFields = fieldNames(SubTask.class);
        Set<String> responseFields = fieldNames(SubTaskResponse.class);
        Map<String, String> reversed = SubTaskResponseMapper.responseToEntityField();

        Set<String> orphans = new LinkedHashSet<>();
        for (String field : responseFields) {
            String entityField = reversed.getOrDefault(field, field);
            if (!entityFields.contains(entityField) && !BACKFILLED_BY_CONTROLLER.contains(field)) {
                orphans.add(field);
            }
        }

        assertThat(orphans)
                .as("这些响应字段既不是实体字段，也不在回填清单里（多为拼写错误）：%s", orphans)
                .isEmpty();
    }

    @Test
    @DisplayName("值搬运：评分三件套 + 改名映射 + 依赖归一化")
    void carriesValuesIncludingScoreAndRenames() {
        SubTask entity = new SubTask();
        entity.setId(11L);
        entity.setTaskId(22L);
        entity.setTitle("子任务");
        entity.setStatus(SubTaskStatus.DONE);
        entity.setAssignedAgentId(33L);
        entity.setCompleteTime(OffsetDateTime.parse("2026-10-18T10:00:00+08:00"));
        entity.setCompositeScore(90);
        entity.setScoreGrade("S");
        entity.setScoreFactors(Map.of("timeScore", 88, "qualityScore", 75));
        entity.setDependsOn(List.of(1L, 2L));

        SubTaskResponse response = SubTaskResponseMapper.toResponse(entity);

        // 改名映射：实体 assignedAgentId → 响应 assignedAgent；completeTime → completedAt
        assertThat(response.getAssignedAgent()).isEqualTo(33L);
        assertThat(response.getCompletedAt()).isEqualTo(entity.getCompleteTime());
        // 评分三件套（本次回归的直接对象）
        assertThat(response.getCompositeScore()).isEqualTo(90);
        assertThat(response.getScoreGrade()).isEqualTo("S");
        assertThat(response.getScoreFactors()).containsEntry("timeScore", 88);
        // 计算型字段：依赖列表走 dependsOnIdList() 归一化
        assertThat(response.getDependsOn()).containsExactly(1L, 2L);
        // 冗余展示字段由 Controller 回填，映射器不得伪造
        assertThat(response.getTaskTitle()).isNull();
        assertThat(response.getAssignedAgentName()).isNull();
    }

    /** 收集类及其父类声明的实例字段名（跳过 static / synthetic）。 */
    private static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                names.add(field.getName());
            }
        }
        return names;
    }
}
