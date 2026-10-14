package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.agent.service.LlmProviderKeyVerifyService;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.LlmProviderModelService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.LlmProviderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/admin/llm-providers/getSkillOptionsByModelType/{modelType}} 的 `M-9` 契约单测。
 *
 * <p>覆盖：识别模型（谓词派生 capabilitySkills / availableOptionalSkills）/ 未识别模型降级
 * （逐字 {@code degraded=true} + {@code capabilities=null}）/ reasoning=false / webSearch=false /
 * {@code capabilities} 透出 / 出参字段名集合不变（仅新增 1 个字段）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminLlmProviderSkillOptionsTest {

    @Mock
    private LlmProviderService providerService;
    @Mock
    private LlmProviderQueryService queryService;
    @Mock
    private PlatformProviderConfigService platformProviderConfigService;
    @Mock
    private LlmProviderKeyVerifyService llmProviderKeyVerifyService;
    @Mock
    private LlmProviderModelService llmProviderModelService;
    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;

    @InjectMocks
    private AdminLlmProviderController controller;

    private static final String MODEL = "deepseek:deepseek-v4-flash";

    private LlmProviderModel model(boolean reasoning, boolean webSearch) {
        LlmProviderModel m = new LlmProviderModel();
        m.setModelName("deepseek-v4-flash");
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("input", List.of("text", "image", "pdf"));
        caps.put("output", List.of("text"));
        caps.put("reasoning", reasoning);
        caps.put("toolCalling", true);
        caps.put("webSearch", webSearch);
        m.setCapabilities(caps);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String modelType) {
        R<Map<String, Object>> r = controller.getSkillOptions(modelType);
        assertThat(r.getCode()).isEqualTo(200);
        return r.getData();
    }

    @Test
    @DisplayName("识别模型 reasoning=true / webSearch=false：锁定 [thinking]，可选 [shell,code-review]")
    void identifiedModelPredicateDerived() {
        when(llmProviderModelQueryService.findCapabilityByModelType(MODEL)).thenReturn(model(true, false));
        Map<String, Object> b = body(MODEL);
        assertThat((List<String>) b.get("capabilitySkills")).containsExactly("thinking");
        assertThat((List<String>) b.get("availableOptionalSkills")).containsExactly("shell", "code-review");
        assertThat(b.get("degraded")).isEqualTo(false);
    }

    @Test
    @DisplayName("识别模型 reasoning=false / webSearch=true：锁定空，可选追加 web-search")
    void identifiedModelReasoningFalseWebSearchTrue() {
        when(llmProviderModelQueryService.findCapabilityByModelType(MODEL)).thenReturn(model(false, true));
        Map<String, Object> b = body(MODEL);
        assertThat((List<String>) b.get("capabilitySkills")).isEmpty();
        assertThat((List<String>) b.get("availableOptionalSkills"))
                .containsExactly("shell", "code-review", "web-search");
    }

    @Test
    @DisplayName("未识别模型：degraded=true + 逐字默认值 + capabilities=null")
    void unknownModelDegraded() {
        when(llmProviderModelQueryService.findCapabilityByModelType("legacy:old")).thenReturn(null);
        Map<String, Object> b = body("legacy:old");
        assertThat(b.get("capabilitySkills")).isEqualTo(List.of());
        assertThat(b.get("availableOptionalSkills")).isEqualTo(List.of("shell", "code-review"));
        assertThat(b.get("degraded")).isEqualTo(true);
        assertThat(b).containsKey("capabilities");
        assertThat(b.get("capabilities")).isNull();
        assertThat(b.get("modelType")).isEqualTo("legacy:old");
    }

    @Test
    @DisplayName("识别模型：capabilities 透出（object，含 input/output/reasoning/toolCalling/webSearch）")
    void identifiedModelExposesCapabilities() {
        when(llmProviderModelQueryService.findCapabilityByModelType(MODEL)).thenReturn(model(true, true));
        Map<String, Object> b = body(MODEL);
        Object capsObj = b.get("capabilities");
        assertThat(capsObj).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) capsObj;
        assertThat(caps).containsKeys("input", "output", "reasoning", "toolCalling", "webSearch");
        assertThat(caps.get("input")).isEqualTo(List.of("text", "image", "pdf"));
        assertThat(caps.get("webSearch")).isEqualTo(true);
    }

    @Test
    @DisplayName("出参字段名集合：modelType/capabilitySkills/availableOptionalSkills/degraded/capabilities（仅新增 1 个）")
    void responseFieldNamesStable() {
        when(llmProviderModelQueryService.findCapabilityByModelType(MODEL)).thenReturn(model(true, false));
        Map<String, Object> b = body(MODEL);
        assertThat(b.keySet()).containsExactlyInAnyOrder(
                "modelType", "capabilitySkills", "availableOptionalSkills", "degraded", "capabilities");
    }

    @Test
    @DisplayName("空 capabilities（模型行谓词缺失）：走保守面，不抛错")
    void nullCapabilitiesUsesConservativeFace() {
        LlmProviderModel m = new LlmProviderModel();
        m.setCapabilities(null);
        when(llmProviderModelQueryService.findCapabilityByModelType(MODEL)).thenReturn(m);
        Map<String, Object> b = body(MODEL);
        assertThat((List<String>) b.get("capabilitySkills")).isEmpty();
        assertThat((List<String>) b.get("availableOptionalSkills")).containsExactly("shell", "code-review");
        assertThat(b.get("degraded")).isEqualTo(false);
    }
}
