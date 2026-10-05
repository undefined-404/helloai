package com.helloai.core.task.service.impl;

import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.core.task.entity.TaskAgentMember;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TaskAgentMemberServiceImpl} 单测。
 *
 * <p><b>为什么补这条</b>：本服务是「谁是某任务团队成员」的<b>唯一判据来源</b>
 * （附件的 {@code TASK} 可见性分支直接依赖它）。此前只有 {@code AttachmentVisibilityPolicy}
 * 的间接覆盖，服务自身四条语义——{@code null} 守卫、权威源兜底、upsert 委派、
 * 重建对账「失败不中断」——从未被单独断言。判据服务出错的表现是<b>静默</b>的
 * （要么全放行、要么全拒绝），故必须逐条钉死。</p>
 *
 * <p>注入方式说明：{@code ServiceImpl#baseMapper} 是 protected 字段且无 setter，
 * 用 {@code ReflectionTestUtils} 显式注入（比 {@code @InjectMocks} 更可预期）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskAgentMemberService 单测")
class TaskAgentMemberServiceImplTest {

    private static final Long TASK_ID = 2106009817950142465L;
    private static final Long AGENT_ID = 2088624140014718977L;

    @Mock
    private TaskAgentMemberMapper mapper;

    private TaskAgentMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskAgentMemberServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    // ==================== isMember ====================

    @Test
    @DisplayName("isMember: 存在 ACTIVE 成员行 -> true")
    void isMemberShouldBeTrueWhenActiveRowExists() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThat(service.isMember(TASK_ID, AGENT_ID)).isTrue();
    }

    @Test
    @DisplayName("isMember: 无成员行 -> false")
    void isMemberShouldBeFalseWhenNoRow() {
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThat(service.isMember(TASK_ID, AGENT_ID)).isFalse();
    }

    @Test
    @DisplayName("isMember: 计数为 null（防御）-> false，且不抛 NPE")
    void isMemberShouldBeFalseWhenCountNull() {
        when(mapper.selectCount(any())).thenReturn(null);

        assertThat(service.isMember(TASK_ID, AGENT_ID)).isFalse();
    }

    @Test
    @DisplayName("isMember: 任一 ID 为 null -> false，且不查库")
    void isMemberShouldBeFalseWhenAnyIdNull() {
        assertThat(service.isMember(null, AGENT_ID)).isFalse();
        assertThat(service.isMember(TASK_ID, null)).isFalse();

        verifyNoInteractions(mapper);
    }

    // ==================== isCurrentExecutorOfTask（derive-on-miss 兜底） ====================

    @Test
    @DisplayName("isCurrentExecutorOfTask: 当前是任务内子任务执行者 -> true")
    void isCurrentExecutorShouldBeTrueWhenAssigned() {
        when(mapper.countCurrentExecutor(TASK_ID, AGENT_ID)).thenReturn(1);

        assertThat(service.isCurrentExecutorOfTask(TASK_ID, AGENT_ID)).isTrue();
    }

    @Test
    @DisplayName("isCurrentExecutorOfTask: 未被指派 -> false")
    void isCurrentExecutorShouldBeFalseWhenNotAssigned() {
        when(mapper.countCurrentExecutor(TASK_ID, AGENT_ID)).thenReturn(0);

        assertThat(service.isCurrentExecutorOfTask(TASK_ID, AGENT_ID)).isFalse();
    }

    @Test
    @DisplayName("isCurrentExecutorOfTask: 任一 ID 为 null -> false，且不查库")
    void isCurrentExecutorShouldBeFalseWhenAnyIdNull() {
        assertThat(service.isCurrentExecutorOfTask(null, AGENT_ID)).isFalse();
        assertThat(service.isCurrentExecutorOfTask(TASK_ID, null)).isFalse();

        verifyNoInteractions(mapper);
    }

    // ==================== register ====================

    @Test
    @DisplayName("register: 委派 upsert，来源枚举按 name 传参、操作者为 system")
    void registerShouldDelegateToUpsert() {
        service.register(TASK_ID, AGENT_ID, TaskMemberJoinSource.ASSIGNED);

        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsert(anyLong(), eq(TASK_ID), eq(AGENT_ID),
                sourceCaptor.capture(), eq("system"));
        assertThat(sourceCaptor.getValue()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("register: ID 为 null 静默跳过，不写库")
    void registerShouldSkipWhenIdNull() {
        service.register(null, AGENT_ID, TaskMemberJoinSource.CLAIMED);
        service.register(TASK_ID, null, TaskMemberJoinSource.CLAIMED);

        verify(mapper, never()).upsert(anyLong(), any(), any(), any(), any());
    }

    // ==================== listActiveAgentIds ====================

    @Test
    @DisplayName("listActiveAgentIds: 正常返回成员 ID 列表")
    void listActiveAgentIdsShouldReturnIds() {
        when(mapper.selectActiveAgentIds(TASK_ID)).thenReturn(List.of(11L, 22L));

        assertThat(service.listActiveAgentIds(TASK_ID)).containsExactly(11L, 22L);
    }

    @Test
    @DisplayName("listActiveAgentIds: mapper 返回 null -> 空列表（不返回 null 契约）")
    void listActiveAgentIdsShouldReturnEmptyWhenMapperReturnsNull() {
        when(mapper.selectActiveAgentIds(TASK_ID)).thenReturn(null);

        assertThat(service.listActiveAgentIds(TASK_ID)).isEmpty();
    }

    @Test
    @DisplayName("listActiveAgentIds: taskId 为 null -> 空列表，且不查库")
    void listActiveAgentIdsShouldReturnEmptyWhenTaskIdNull() {
        assertThat(service.listActiveAgentIds(null)).isEmpty();

        verifyNoInteractions(mapper);
    }

    // ==================== rebuildFromAuthoritativeAssignments ====================

    @Test
    @DisplayName("rebuild: 权威源为空 -> 0，不写库")
    void rebuildShouldReturnZeroWhenNoAssignments() {
        when(mapper.selectAuthoritativeAssignments()).thenReturn(List.of());

        assertThat(service.rebuildFromAuthoritativeAssignments()).isZero();
        verify(mapper, never()).upsert(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rebuild: 跳过残缺行（缺 taskId/agentId / 整行为 null），其余照写")
    void rebuildShouldSkipInvalidRowsAndContinue() {
        // 注意：用 Arrays.asList —— List.of 不接受 null 元素，而"整行为 null"正是要覆盖的防御分支
        when(mapper.selectAuthoritativeAssignments()).thenReturn(java.util.Arrays.asList(
                pair(TASK_ID, AGENT_ID),
                pair(null, AGENT_ID),
                pair(TASK_ID, null),
                null));

        assertThat(service.rebuildFromAuthoritativeAssignments()).isEqualTo(1);
        verify(mapper, times(1)).upsert(anyLong(), eq(TASK_ID), eq(AGENT_ID), eq("REBUILT"), eq("system"));
    }

    @Test
    @DisplayName("rebuild: 中间某行 upsert 失败不中断，后续行继续处理")
    void rebuildShouldNotAbortOnSingleFailure() {
        Long badTask = 999L;
        Long badAgent = 888L;
        Long tailTask = 777L;
        Long tailAgent = 666L;
        when(mapper.selectAuthoritativeAssignments()).thenReturn(List.of(
                pair(TASK_ID, AGENT_ID),
                pair(badTask, badAgent),
                pair(tailTask, tailAgent)));
        // 用 thenAnswer 精确控制"仅中间行失败"，避免多个参数匹配器叠加时的语义歧义
        when(mapper.upsert(anyLong(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Long taskId = invocation.getArgument(1);
            Long agentId = invocation.getArgument(2);
            if (badTask.equals(taskId) && badAgent.equals(agentId)) {
                throw new RuntimeException("simulated upsert failure");
            }
            return 1;
        });

        // 3 行中 1 行失败：写入 2、失败 1，且三行都被尝试过（失败被 swallow，不中断循环）
        assertThat(service.rebuildFromAuthoritativeAssignments()).isEqualTo(2);
        verify(mapper, times(3)).upsert(anyLong(), any(), any(), eq("REBUILT"), eq("system"));
        verify(mapper).upsert(anyLong(), eq(tailTask), eq(tailAgent), eq("REBUILT"), eq("system"));
    }

    private static TaskAgentMember pair(Long taskId, Long agentId) {
        TaskAgentMember member = new TaskAgentMember();
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        return member;
    }
}
