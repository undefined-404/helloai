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

    /**
     * 获取所有配置为 Map
     */
    @Override
    public Map<String, String> getAllAsMap() {
        return list().stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue));
    }

    /**
     * 获取单个配置值
     */
    @Override
    public String getValue(String key) {
        SysConfig config = lambdaQuery().eq(SysConfig::getConfigKey, key).one();
        return config != null ? config.getConfigValue() : null;
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
        log.info("系统配置更新: key={}, value={}", key, value);
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
