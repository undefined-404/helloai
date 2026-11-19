package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 派单决策快照单测（`MA-2`）。
 *
 * <p><b>本类守的是两件事</b>：</p>
 * <ol>
 *   <li><b>快照说得对</b>：候选数、位次、中选者唯一、截断与总数；</li>
 *   <li><b>选人没变</b>：同一组 fixture 下，{@code pickPreferred}（旧入口）与
 *       {@code pickPreferredWithSnapshot}（新入口）选出<b>同一个人</b>——
 *       这是「加观测不加判据」的唯一回归保护（判据 18：两组只差被研究的那个变量）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-2 派单决策快照")
class DispatchSnapshotTest {

    @Mock
    private AgentService agentService;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private AgentDutyLeaseService agentDutyLeaseService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private ConcurrencyQuotaService concurrencyQuotaService;

    @Mock
    private AgentQualityProfileService agentQualityProfileService;

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    private AgentSelector selector;

    @BeforeEach
    void setUp() {
        AgentDispatchProperties props = new AgentDispatchProperties();
        props.setPreferExternal(false);
        props.setRequireIdle(false);
        props.setEnforceMaxConcurrent(false);
        // 质量 / 成本维度关闭：本类只验「投影对不对」，不验权重；维度关闭时快照应记 null
        props.setQualityWeight(0);
        props.setCostWeight(0);
        AgentHealthProperties health = new AgentHealthProperties();
        health.setOfflineMinutes(5);
        lenient().when(agentDutyLeaseService.isOnDuty(anyLong())).thenReturn(false);
        lenient().when(agentLlmCredentialResolver.hasUsableCredential(any(Agent.class))).thenReturn(true);
        selector = new AgentSelector(agentService, circuitBreakerRegistry, props, health,
                agentDutyLeaseService, agentLlmCredentialResolver, concurrencyQuotaService,
                agentQualityProfileService, agentExecutionRecordService);
    }

    private static Agent agent(long id, Integer score) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.CLI_CLIENT);
        a.setOnlineStatus(AgentOnlineStatus.ONLINE);
        a.setStatus(AgentStatus.ACTIVE);
        a.setScore(score);
        // CLI_CLIENT 要过心跳新鲜度过滤：last_seen_time 为空会被判「陈旧」而整池清空
        a.setLastSeenTime(OffsetDateTime.now());
        return a;
    }

    @Nested
    @DisplayName("快照内容")
    class SnapshotContent {

        @Test
        @DisplayName("候选数 / 位次 / 中选者唯一：快照第一位就是实选中的人")
        void shouldRankCandidatesAndMarkSingleWinner() {
            Agent low = agent(1L, 10);
            Agent high = agent(2L, 30);
            Agent mid = agent(3L, 20);
            when(agentService.listByRole(AgentRole.EXECUTOR)).thenReturn(List.of(low, high, mid));

            AgentSelector.Selection selection =
                    selector.pickPreferredWithSnapshot(AgentRole.EXECUTOR, null);

            assertThat(selection.agent().getId()).isEqualTo(2L);
            DispatchDecisionSnapshot snapshot = selection.snapshot();
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.path()).isEqualTo(DispatchDecisionSnapshot.PATH_PRIMARY);
            assertThat(snapshot.candidateTotal()).isEqualTo(3);
            assertThat(snapshot.truncated()).isFalse();
            // attempt 由 task 域落库时回填，选人器不知道 → null
            assertThat(snapshot.attempt()).isNull();
            assertThat(snapshot.candidates()).extracting(DispatchDecisionSnapshot.Candidate::agentId)
                    .containsExactly(2L, 3L, 1L);
            assertThat(snapshot.candidates()).filteredOn(DispatchDecisionSnapshot.Candidate::winner)
                    .hasSize(1);
            assertThat(snapshot.candidates().get(0).winner()).isTrue();
            // 同档（无值班/无质量/无成本差异）→ 档位序号都是 0
            assertThat(snapshot.candidates()).extracting(DispatchDecisionSnapshot.Candidate::tier)
                    .containsOnly(0);
        }

        @Test
        @DisplayName("维度未参与 ⇒ 记 null 而不是 0（「不可比」与「最差」必须区分）")
        void shouldRecordNullRanksWhenDimensionsDisabled() {
            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(agent(1L, 10), agent(2L, 20)));

            DispatchDecisionSnapshot snapshot =
                    selector.pickPreferredWithSnapshot(AgentRole.EXECUTOR, null).snapshot();

            assertThat(snapshot.candidates()).allSatisfy(c -> {
                assertThat(c.qualityRank()).isNull();
                assertThat(c.costRank()).isNull();
                // SCORE_DESC 策略不采集在飞数（只有加权随机拿它当分母）
                assertThat(c.inFlight()).isNull();
            });
        }

        @Test
        @DisplayName("候选 > 10 ⇒ 只留前 10，candidateTotal 仍是真实总数")
        void shouldTruncateButKeepRealTotal() {
            List<Agent> many = new ArrayList<>(LongStream.rangeClosed(1, 12)
                    .mapToObj(i -> agent(i, (int) (100 - i)))
                    .toList());
            when(agentService.listByRole(AgentRole.EXECUTOR)).thenReturn(many);

            DispatchDecisionSnapshot snapshot =
                    selector.pickPreferredWithSnapshot(AgentRole.EXECUTOR, null).snapshot();

            assertThat(snapshot.candidates()).hasSize(DispatchDecisionSnapshot.MAX_CANDIDATES);
            assertThat(snapshot.candidateTotal()).isEqualTo(12);
            assertThat(snapshot.truncated()).isTrue();
            assertThat(snapshot.candidates().get(0).agentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("无候选 ⇒ 不产快照（不写空对象，与「没走过选人」区分）")
        void shouldReturnNullSnapshotWhenNoCandidate() {
            Agent sleeping = agent(1L, 50);
            sleeping.setOnlineStatus(AgentOnlineStatus.SLEEPING);
            when(agentService.listByRole(AgentRole.EXECUTOR)).thenReturn(List.of(sleeping));

            AgentSelector.Selection selection =
                    selector.pickPreferredWithSnapshot(AgentRole.EXECUTOR, null);

            assertThat(selection.agent()).isNull();
            assertThat(selection.snapshot()).isNull();
        }
    }

    @Nested
    @DisplayName("选人结果不变（回归核心）")
    class SelectionUnchanged {

        @Test
        @DisplayName("旧入口与新入口选出同一个人（同一组 fixture）")
        void shouldPickSameAgentAsPlainEntry() {
            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(agent(1L, 10), agent(2L, 30), agent(3L, 20), agent(4L, null)));

            Agent plain = selector.pickPreferred(AgentRole.EXECUTOR, null);
            Agent withSnapshot = selector.pickPreferredWithSnapshot(AgentRole.EXECUTOR, null).agent();

            assertThat(withSnapshot.getId()).isEqualTo(plain.getId());
            assertThat(plain.getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("换人入口同样一致，且候选池已排除原 Agent")
        void shouldPickSameAlternativeAndExcludeOriginal() {
            when(agentService.listByRole(AgentRole.EXECUTOR))
                    .thenReturn(List.of(agent(1L, 10), agent(2L, 30), agent(3L, 20)));

            Agent plain = selector.pickAlternative(2L, AgentRole.EXECUTOR, null);
            AgentSelector.Selection withSnapshot =
                    selector.pickAlternativeWithSnapshot(2L, AgentRole.EXECUTOR, null);

            assertThat(withSnapshot.agent().getId()).isEqualTo(plain.getId());
            assertThat(plain.getId()).isEqualTo(3L);
            assertThat(withSnapshot.snapshot().candidates())
                    .extracting(DispatchDecisionSnapshot.Candidate::agentId)
                    .containsExactly(3L, 1L);
        }
    }
}
