package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysConfig;

import java.util.Map;

/**
 * 系统配置服务接口。
 */
public interface SysConfigService extends IService<SysConfig> {

    /**
     * 获取所有配置为 Map
     */
    Map<String, String> getAllAsMap();

    /**
     * 获取单个配置值
     */
    String getValue(String key);

    /**
     * 设置配置值
     */
    void setValue(String key, String value);

    /**
     * 批量更新配置
     */
    void batchUpdate(Map<String, String> configMap);

    /**
     * 检查初始化向导是否已完成
     */
    boolean isSetupFinished();
}
