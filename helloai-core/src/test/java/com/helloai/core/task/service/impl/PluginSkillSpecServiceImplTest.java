package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PluginSkillSpecService")
class PluginSkillSpecServiceImplTest {

    @Mock
    private TaskService taskService;

    @InjectMocks
    private PluginSkillSpecServiceImpl pluginSkillSpecService;

    private Task taskWithSkills(List<String> skills) {
        Task task = new Task();
        task.setRequiredSkills(skills);
        return task;
    }

    @Test
    @DisplayName("should return empty when taskId is null")
    void shouldReturnEmptyWhenTaskIdIsNull() {
        assertThat(pluginSkillSpecService.renderSection(null)).isEmpty();
    }

    @Test
    @DisplayName("should return empty when task not found")
    void shouldReturnEmptyWhenTaskNotFound() {
        when(taskService.getById(1L)).thenReturn(null);
        assertThat(pluginSkillSpecService.renderSection(1L)).isEmpty();
    }

    @Test
    @DisplayName("should return empty when requiredSkills is empty")
    void shouldReturnEmptyWhenRequiredSkillsEmpty() {
        when(taskService.getById(1L)).thenReturn(taskWithSkills(List.of()));
        assertThat(pluginSkillSpecService.renderSection(1L)).isEmpty();
    }

    @Test
    @DisplayName("should return empty when no eng plugin tag hit")
    void shouldReturnEmptyWhenNoPluginTagHit() {
        when(taskService.getById(1L)).thenReturn(taskWithSkills(List.of("shell", "web-search")));
        assertThat(pluginSkillSpecService.renderSection(1L)).isEmpty();
    }

    @Test
    @DisplayName("should render speed summary only when eng-code-review hit")
    void shouldRenderSpeedSummaryWhenCodeReviewHit() {
        when(taskService.getById(1L)).thenReturn(taskWithSkills(List.of("eng-code-review")));

        String section = pluginSkillSpecService.renderSection(1L);

        assertThat(section).contains("## 平台技能规范");
        assertThat(section).contains("### eng-code-review");
        assertThat(section).contains("接口契约（C1）");
        assertThat(section).doesNotContain("## 详细规范");
        assertThat(section).doesNotContain("# eng-code-review 平台技能规范");
    }

    @Test
    @DisplayName("should render multiple specs in declared registry order")
    void shouldRenderMultipleSpecsInDeclaredOrder() {
        when(taskService.getById(1L)).thenReturn(
                taskWithSkills(List.of("eng-verification", "eng-code-review")));

        String section = pluginSkillSpecService.renderSection(1L);

        int codeReview = section.indexOf("### eng-code-review");
        int verification = section.indexOf("### eng-verification");
        assertThat(codeReview).isGreaterThan(0);
        assertThat(verification).isGreaterThan(codeReview);
        assertThat(section).contains("## 平台技能规范");
    }

    @Test
    @DisplayName("should ignore unknown skills and render only known hits")
    void shouldIgnoreUnknownSkillsAndRenderOnlyKnownHits() {
        when(taskService.getById(1L)).thenReturn(
                taskWithSkills(List.of("eng-doc-standard", "kubernetes")));

        String section = pluginSkillSpecService.renderSection(1L);

        assertThat(section).contains("### eng-doc-standard");
        assertThat(section).contains("命题完整保留");
        assertThat(section).doesNotContain("### kubernetes");
    }
}
