package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.system.entity.SysConfig;
import com.helloai.core.system.mapper.SysConfigMapper;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

    /** 凭证类配置键后缀：读取端点对外返回时脱敏，防明文/密文经管理接口泄露。 */
    private static final String SENSITIVE_KEY_SUFFIX = ".api-key";

    /** 脱敏占位值：仅表达"已配置"，不回显任何凭证内容。 */
    private static final String SENSITIVE_MASKED = "********";

    /**
     * 获取所有配置为 Map
     */
    @Override
    public Map<String, String> getAllAsMap() {
        return list().stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, c -> maskIfSensitive(c.getConfigKey(), c.getConfigValue())));
    }

    /**
     * 获取单个配置值（内部业务消费入口，不脱敏；凭证读取自行走 KeyStore 解密）
     */
    @Override
    public String getValue(String key) {
        SysConfig config = lambdaQuery().eq(SysConfig::getConfigKey, key).one();
        return config != null ? config.getConfigValue() : null;
    }

    /** 凭证类键对外脱敏；其余键原样返回。 */
    private static String maskIfSensitive(String key, String value) {
        if (key != null && key.endsWith(SENSITIVE_KEY_SUFFIX) && value != null && !value.isBlank()) {
            return SENSITIVE_MASKED;
        }
        return value;
    }

    /**
     * 设置配置值
     */
    @Override
    public void setValue(String key, String value) {
        SysConfig config = lambdaQuery().eq(SysConfig::getConfigKey, key).one();
        if (config != null) {
            config.setConfigValue(value);
            updateById(config);
        } else {
            config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            save(config);
        }
        // 凭证类键不落日志明文/密文，避免敏感信息扩散到日志采集链路
        if (key != null && key.endsWith(SENSITIVE_KEY_SUFFIX)) {
            log.info("系统配置更新: key={}, value=<masked>", key);
        } else {
            log.info("系统配置更新: key={}, value={}", key, value);
        }
    }

    /**
     * 批量更新配置
     */
    @Override
    public void batchUpdate(Map<String, String> configMap) {
        configMap.forEach(this::setValue);
    }

    /**
     * 检查初始化向导是否已完成
     */
    @Override
    public boolean isSetupFinished() {
        String val = getValue("system.setup_finished");
        return "1".equals(val);
    }
}
