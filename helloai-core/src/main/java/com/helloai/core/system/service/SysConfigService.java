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

    /**
     * 是否开放自助注册（BASE-4.5）。
     *
     * <p>读取 {@code sys_config} 键 {@code auth.register.enabled}；**缺省视为关闭**，
     * 取值 {@code 1} 或 {@code true}（大小写不敏感）视为开启。开启后
     * {@code POST /api/auth/register} 可用，注册账号默认绑定 GUEST（只读，最小权限）。</p>
     */
    boolean isRegisterEnabled();

    /**
     * 是否启用站内事件消息（G-035 / MSG-1，设计 §3.10）。
     *
     * <p>读取 {@code sys_config} 键 {@code notification.enabled}；**缺省视为关闭**，
     * 取值 {@code 1} 或 {@code true}（大小写不敏感）视为开启。OFF 时消息单一出口早退不写库、
     * 读取端点返回空、前端不渲染（停发 + 隐藏）。</p>
     */
    boolean isNotificationEnabled();

    /**
     * 是否启用置顶公告（G-035 / MSG-6，设计 §3.10）。
     *
     * <p>读取 {@code sys_config} 键 {@code announcement.enabled}；**缺省视为关闭**，
     * 取值 {@code 1} 或 {@code true}（大小写不敏感）视为开启。OFF 只隐藏读取与展示，
     * 公告写入（人工撰写）不受限制。</p>
     */
    boolean isAnnouncementEnabled();
}
