package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.mapper.LlmProviderModelMapper;
import com.helloai.core.system.service.impl.LlmProviderModelQueryServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmProviderModelQueryServiceImpl 单元测试。
 *
 * <p>覆盖：按 Provider ID / Code 查询模型列表、默认模型查询、模型可用性校验、
 * 数量统计，以及空值入参防御（返回空集合而非 null）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderModelQueryServiceImpl")
class LlmProviderModelQueryServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 MyBatis 上下文，手动注册实体元数据，供 LambdaQueryWrapper 解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), LlmProviderModel.class);
    }

    @Mock
    private LlmProviderModelMapper mapper;

    @InjectMocks
    private LlmProviderModelQueryServiceImpl service;

    private LlmProviderModel model(String name, int isDefault, int enabled) {
        LlmProviderModel m = new LlmProviderModel();
        m.setProviderId(1L);
        m.setProviderCode("deepseek");
        m.setModelName(name);
        m.setIsDefault(isDefault);
        m.setEnabled(enabled);
        return m;
    }

    @Test
    @DisplayName("listByProviderId：providerId 为 null 时返回空列表而非抛异常")
    void listByProviderId_nullId_returnsEmpty() {
        assertThat(service.listByProviderId(null)).isEmpty();
    }

    @Test
    @DisplayName("listByProviderId：正常返回模型列表")
    void listByProviderId_hasData_returnsList() {
        LlmProviderModel m1 = model("deepseek-v4-flash", 1, 1);
        LlmProviderModel m2 = model("deepseek-v4-pro", 0, 1);
        when(mapper.selectList(any())).thenReturn(List.of(m1, m2));

        List<LlmProviderModel> result = service.listByProviderId(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getModelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("listByProviderId：mapper 返回 null 时兜底为空列表")
    void listByProviderId_mapperNull_returnsEmpty() {
        when(mapper.selectList(any())).thenReturn(null);

        assertThat(service.listByProviderId(1L)).isEmpty();
    }

    @Test
    @DisplayName("listEnabledByProviderCode：providerCode 归一化为小写后查询")
    void listEnabledByProviderCode_normalizesLowercase() {
        LlmProviderModel m = model("deepseek-v4-flash", 1, 1);
        when(mapper.selectList(any())).thenReturn(List.of(m));

        List<LlmProviderModel> result = service.listEnabledByProviderCode("DeepSeek");

        ArgumentCaptor<LambdaQueryWrapper<LlmProviderModel>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        // 先触发 SQL 解析（mock 场景下 selectList 不执行真实 SQL，参数映射不会自动填充）
        assertThat(captor.getValue().getSqlSegment()).contains("provider_code");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("deepseek");
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listEnabledByProviderCode：blank 入参返回空列表")
    void listEnabledByProviderCode_blankCode_returnsEmpty() {
        assertThat(service.listEnabledByProviderCode("  ")).isEmpty();
        assertThat(service.listEnabledByProviderCode(null)).isEmpty();
    }

    @Test
    @DisplayName("findDefaultByProviderId：存在默认模型时返回实体")
    void findDefaultByProviderId_found_returnsModel() {
        LlmProviderModel m = model("deepseek-v4-flash", 1, 1);
        when(mapper.selectOne(any())).thenReturn(m);

        LlmProviderModel result = service.findDefaultByProviderId(1L);

        assertThat(result).isNotNull();
        assertThat(result.getModelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("findDefaultByProviderId：无默认模型时返回 null")
    void findDefaultByProviderId_notFound_returnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.findDefaultByProviderId(1L)).isNull();
        assertThat(service.findDefaultByProviderId(null)).isNull();
    }

    @Test
    @DisplayName("findDefaultModelNameByProviderCode：返回默认模型名称")
    void findDefaultModelNameByProviderCode_found_returnsName() {
        LlmProviderModel m = model("kimi-k2.5", 1, 1);
        when(mapper.selectOne(any())).thenReturn(m);

        String result = service.findDefaultModelNameByProviderCode("moonshot");

        assertThat(result).isEqualTo("kimi-k2.5");
    }

    @Test
    @DisplayName("findDefaultModelNameByProviderCode：未命中返回 null")
    void findDefaultModelNameByProviderCode_notFound_returnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.findDefaultModelNameByProviderCode("unknown")).isNull();
    }

    @Test
    @DisplayName("isModelAvailable：存在且启用时返回 true")
    void isModelAvailable_enabledModel_returnsTrue() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThat(service.isModelAvailable("deepseek", "deepseek-v4-flash")).isTrue();
    }

    @Test
    @DisplayName("isModelAvailable：不存在或已禁用时返回 false")
    void isModelAvailable_disabledModel_returnsFalse() {
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThat(service.isModelAvailable("deepseek", "deepseek-v5")).isFalse();
    }

    @Test
    @DisplayName("isModelAvailable：空入参直接返回 false，不触发查询")
    void isModelAvailable_blankInput_returnsFalse() {
        assertThat(service.isModelAvailable(null, "m")).isFalse();
        assertThat(service.isModelAvailable("deepseek", "  ")).isFalse();
    }

    @Test
    @DisplayName("findCapabilityByModelType：命中模型返回携带能力列的实体")
    void findCapabilityByModelType_found_returnsModel() {
        LlmProviderModel m = model("deepseek-v4-flash", 1, 1);
        m.setCapabilitySkills(List.of("thinking"));
        m.setAvailableOptionalSkills(List.of("shell", "code-review"));
        when(mapper.selectOne(any())).thenReturn(m);

        LlmProviderModel result = service.findCapabilityByModelType("deepseek:deepseek-v4-flash");

        assertThat(result).isNotNull();
        assertThat(result.getCapabilitySkills()).containsExactly("thinking");
        assertThat(result.getAvailableOptionalSkills()).containsExactly("shell", "code-review");
    }

    @Test
    @DisplayName("findCapabilityByModelType：providerCode 归一化为小写后查询")
    void findCapabilityByModelType_normalizesLowercase() {
        LlmProviderModel m = model("kimi-k2.5", 1, 1);
        when(mapper.selectOne(any())).thenReturn(m);

        service.findCapabilityByModelType("Moonshot:kimi-k2.5");

        ArgumentCaptor<LambdaQueryWrapper<LlmProviderModel>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("provider_code");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("moonshot");
        assertThat(captor.getValue().getParamNameValuePairs()).containsValue("kimi-k2.5");
    }

    @Test
    @DisplayName("findCapabilityByModelType：未命中或入参非法返回 null")
    void findCapabilityByModelType_notFound_returnsNull() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.findCapabilityByModelType("deepseek:deepseek-v5")).isNull();
        // 非法格式：无冒号 / 空段 / blank 整体
        assertThat(service.findCapabilityByModelType("deepseek-v4-flash")).isNull();
        assertThat(service.findCapabilityByModelType("deepseek:")).isNull();
        assertThat(service.findCapabilityByModelType("  ")).isNull();
        assertThat(service.findCapabilityByModelType(null)).isNull();
    }

    @Test
    @DisplayName("countByProviderId：返回数量，null providerId 返回 0")
    void countByProviderId_returnsCount() {
        when(mapper.selectCount(any())).thenReturn(3L);

        assertThat(service.countByProviderId(1L)).isEqualTo(3L);
        assertThat(service.countByProviderId(null)).isZero();
        // mapper 返回 null 时兜底为 0
        when(mapper.selectCount(any())).thenReturn(null);
        assertThat(service.countByProviderId(1L)).isZero();
    }

    @Test
    @DisplayName("listEnabledByProviderId：正常返回启用模型列表")
    void listEnabledByProviderId_returnsEnabledList() {
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(model("deepseek-v4-flash", 1, 1)));

        List<LlmProviderModel> result = service.listEnabledByProviderId(1L);

        assertThat(result).hasSize(1);
        assertThat(service.listEnabledByProviderId(null)).isEmpty();
    }
}
