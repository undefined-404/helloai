package com.helloai.core.task.policy;

import com.helloai.common.constant.TaskPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskPriority} C4-S1 单元测试：优先级解析/归一化/档位。
 */
@DisplayName("TaskPriority 解析与档位（C4-S1）")
class TaskPriorityTest {

    @Test
    @DisplayName("解析：合法值大小写不敏感，null/空白/非法回落 MEDIUM")
    void parse() {
        assertThat(TaskPriority.parse("HIGH")).isEqualTo(TaskPriority.HIGH);
        assertThat(TaskPriority.parse("medium")).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.parse("  low  ")).isEqualTo(TaskPriority.LOW);
        assertThat(TaskPriority.parse(null)).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.parse("  ")).isEqualTo(TaskPriority.MEDIUM);
        assertThat(TaskPriority.parse("URGENT")).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    @DisplayName("normalize：返回枚举 name 字符串（与表列 VARCHAR 对齐）")
    void normalize() {
        assertThat(TaskPriority.normalize("high")).isEqualTo("HIGH");
        assertThat(TaskPriority.normalize(null)).isEqualTo("MEDIUM");
        assertThat(TaskPriority.normalize("whatever")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("档位：HIGH=3 / MEDIUM=2 / LOW=1（调度出队排序用）")
    void rank() {
        assertThat(TaskPriority.HIGH.rank()).isEqualTo(3);
        assertThat(TaskPriority.MEDIUM.rank()).isEqualTo(2);
        assertThat(TaskPriority.LOW.rank()).isEqualTo(1);
        assertThat(TaskPriority.DEFAULT()).isEqualTo(TaskPriority.MEDIUM);
    }
}
