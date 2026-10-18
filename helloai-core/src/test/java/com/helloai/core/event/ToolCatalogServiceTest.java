package com.helloai.core.event;

import com.helloai.common.constant.ToolDisplayName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具名目录契约（2026-10-18）：把 {@code internal_run_in_sandbox} 这类英文标识翻译成人话。
 *
 * <p>锁三件事：①目录与枚举同源（不出现「枚举加了但前端拿不到」）；
 * ②工具名唯一（重复会让 {@code labelOf} 静默取到错的那个）；
 * ③label 非空且足够短（时长会挤坏时间线标题与 mermaid 节点）。</p>
 */
@DisplayName("工具名目录契约（toolName → 中文短名）")
class ToolCatalogServiceTest {

    private final ToolCatalogService service = new ToolCatalogService();

    @Test
    @DisplayName("目录项数 = 枚举项数，且顺序一致（遍历下发，不丢项）")
    void catalogCoversAllEnumValues() {
        List<ToolCatalogItem> items = service.catalog();
        ToolDisplayName[] values = ToolDisplayName.values();

        assertThat(items).hasSize(values.length);
        for (int i = 0; i < values.length; i++) {
            assertThat(items.get(i).toolName()).isEqualTo(values[i].toolName());
            assertThat(items.get(i).label()).isEqualTo(values[i].label());
        }
    }

    @Test
    @DisplayName("工具名唯一（重复会让 labelOf 静默取到错误的那条）")
    void toolNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ToolCatalogItem item : service.catalog()) {
            assertThat(seen.add(item.toolName()))
                    .as("工具名重复: %s", item.toolName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("label 非空且 ≤ 8 字（时间线标题/时序图节点宽度约束）")
    void labelsAreShortAndNonBlank() {
        for (ToolCatalogItem item : service.catalog()) {
            assertThat(item.label()).as("label 为空: %s", item.toolName()).isNotBlank();
            assertThat(item.label().length())
                    .as("label 过长（%s 字）: %s → %s", item.label().length(), item.toolName(), item.label())
                    .isLessThanOrEqualTo(8);
        }
    }

    @Test
    @DisplayName("核心执行链工具必须有中文名（internal_* 九个 + web_search）")
    void coreExecutionChainToolsHaveLabels() {
        assertThat(ToolDisplayName.labelOf("internal_run_in_sandbox")).isEqualTo("沙箱执行");
        assertThat(ToolDisplayName.labelOf("internal_get_sub_task_detail")).isEqualTo("读子任务详情");
        assertThat(ToolDisplayName.labelOf("internal_load_skill")).isEqualTo("加载技能");
        assertThat(ToolDisplayName.labelOf("web_search")).isEqualTo("联网搜索");
    }

    @Test
    @DisplayName("未知工具返回 null（调用方回退原始英文名，不显示「未知工具」掩盖事实）")
    void unknownToolReturnsNull() {
        assertThat(ToolDisplayName.labelOf("no_such_tool")).isNull();
        assertThat(ToolDisplayName.labelOf(null)).isNull();
        assertThat(ToolDisplayName.descOf("no_such_tool")).isNull();
    }
}
