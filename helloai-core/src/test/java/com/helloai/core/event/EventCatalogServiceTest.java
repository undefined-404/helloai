package com.helloai.core.event;

import com.helloai.common.constant.TimelineEventCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EventCatalogService —— REF-6.8 事件码服务端单一来源")
class EventCatalogServiceTest {

    @Test
    @DisplayName("枚举：全码有非空 code + label，code 无重复（单一事实源自洽）")
    void enum_selfConsistent() {
        Set<String> codes = new HashSet<>();
        for (TimelineEventCode c : TimelineEventCode.values()) {
            assertThat(c.code()).isNotBlank();
            assertThat(c.label()).isNotBlank();
            assertThat(codes.add(c.code())).as("重复 code: %s", c.code()).isTrue();
        }
    }

    @Test
    @DisplayName("catalog：遍历枚举全量下发，且每项 code/label 非空")
    void catalog_servesAllVisible() {
        List<EventCatalogItem> items = new EventCatalogService().catalog();
        assertThat(items).hasSize(TimelineEventCode.values().length);
        Set<String> codes = new HashSet<>();
        for (EventCatalogItem it : items) {
            assertThat(it.code()).isNotBlank();
            assertThat(it.label()).isNotBlank();
            assertThat(it).isNotNull();
            assertThat(codes.add(it.code())).isTrue();
        }
    }

    @Test
    @DisplayName("of：精查找的码映射正确，未知码返回 null")
    void of_lookup() {
        assertThat(TimelineEventCode.of("sub_task_execute")).isNotNull();
        assertThat(TimelineEventCode.of("sub_task_execute").label()).isNotBlank();
        assertThat(TimelineEventCode.of("does_not_exist_xyz")).isNull();
        assertThat(TimelineEventCode.of(null)).isNull();
    }
}