package com.helloai.core.system.service;

import com.helloai.common.constant.ActivityAction;
import com.helloai.common.constant.InboxEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举字典契约（2026-10-18）：把 {@code sub_task.approved} / {@code coding} 这类码值统一翻译。
 *
 * <p><b>为什么要有这组用例</b>：前端原先硬编码 {@code INBOX_EVENT_TYPE_MAP}（8 个键），
 * 而后端实际发送 10 种事件类型 ⇒ 页面裸露 {@code sub_task.approved} 等英文码。
 * 集中字典后，"后端新增发送点但忘了补枚举"仍可能复现，故用契约锁住：</p>
 * <ul>
 *   <li>收件箱事件类型**必须**覆盖后端实际发送的全部取值（下方硬编码清单即 {@code send()} 现场）；</li>
 *   <li>code 唯一、label 非空且不过长（列表列宽有限）。</li>
 * </ul>
 */
@DisplayName("枚举字典契约（收件箱事件 / 活动动作）")
class EnumCatalogServiceTest {

    /**
     * 后端实际发送的收件箱事件类型（{@code grep agentInboxService.send(} 现场快照）。
     *
     * <p>新增发送点时必须同步本清单 + {@link InboxEventType}，否则用例失败 —— 这是刻意的：
     * 漏登记的直接后果就是页面上裸露英文码。</p>
     */
    private static final Set<String> SENT_INBOX_EVENT_TYPES = Set.of(
            "sub_task.assigned", "sub_task.reassigned", "sub_task.unassigned",
            "sub_task.review", "sub_task.approved", "sub_task.rejected",
            "sub_task.blocked", "sub_task.paused",
            "task.created", "task.republished");

    private final EnumCatalogService service = new EnumCatalogService();

    @Test
    @DisplayName("★收件箱事件枚举必须覆盖后端全部发送点（漏登记 ⇒ 页面裸露英文码）")
    void inboxEventTypeCoversAllSentTypes() {
        Set<String> declared = new HashSet<>();
        for (InboxEventType e : InboxEventType.values()) {
            declared.add(e.code());
        }
        assertThat(declared)
                .as("后端 send() 的取值与 InboxEventType 不一致（缺登记或多登记）")
                .containsExactlyInAnyOrderElementsOf(SENT_INBOX_EVENT_TYPES);
    }

    @Test
    @DisplayName("活动 action 覆盖全局规则白名单（coding/delivery/blocked/reflection/plan/review/patrol）")
    void activityActionCoversRuleWhitelist() {
        Set<String> declared = new HashSet<>();
        for (ActivityAction a : ActivityAction.values()) {
            declared.add(a.code());
        }
        assertThat(declared).containsExactlyInAnyOrder(
                "coding", "delivery", "blocked", "reflection", "plan", "review", "patrol");
    }

    @Test
    @DisplayName("label 非空且 ≤ 8 字（列表列宽约束）")
    void labelsAreShortAndNonBlank() {
        for (InboxEventType e : InboxEventType.values()) {
            assertThat(e.label()).as("label 为空: %s", e.code()).isNotBlank();
            assertThat(e.label().length()).as("label 过长: %s → %s", e.code(), e.label())
                    .isLessThanOrEqualTo(8);
        }
        for (ActivityAction a : ActivityAction.values()) {
            assertThat(a.label()).as("label 为空: %s", a.code()).isNotBlank();
            assertThat(a.label().length()).as("label 过长: %s → %s", a.code(), a.label())
                    .isLessThanOrEqualTo(8);
        }
    }

    @Test
    @DisplayName("字典分组齐全且项数与枚举一致（下发不丢项）")
    void catalogContainsAllGroups() {
        Map<String, List<EnumCatalogService.EnumItem>> catalog = service.catalog();
        assertThat(catalog).containsKeys("inboxEventType", "activityAction");
        assertThat(catalog.get("inboxEventType")).hasSize(InboxEventType.values().length);
        assertThat(catalog.get("activityAction")).hasSize(ActivityAction.values().length);
    }

    @Test
    @DisplayName("未知码返回 null（调用方回退显示原码，不显示「未知」）")
    void unknownCodeReturnsNull() {
        assertThat(InboxEventType.labelOf("no.such.type")).isNull();
        assertThat(InboxEventType.labelOf(null)).isNull();
        assertThat(ActivityAction.labelOf("no_such_action")).isNull();
    }
}
