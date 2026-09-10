package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * sub_task.uncertainties（JSONB → {@code List<Uncertainty>}）强类型 TypeHandler。
 *
 * <p>背景（2026-09-10 派单死锁根因修复）：MyBatisPlusConfig 为兼容 JSONB 列把
 * {@code List.class} 全局注册为 {@link JacksonTypeHandler}，MyBatis 自动映射按
 * getter 擦除后的 {@code List.class} 命中该全局注册；Jackson 没有具体泛型目标，
 * 把 JSON 数组反序列化为 {@code List<LinkedHashMap>}。强类型消费点（如
 * sendInboxNotification 统计 UNCONFIRMED 缺口做 {@code u.getKind()}）随即抛
 * ClassCastException，异常被 catch 后 inbox 通知静默失败 → 外部 Agent 的
 * pullTasks 永远为空 → 子任务卡 ASSIGNED、并发额度被占死、调度链整体死锁。</p>
 *
 * <p>本 Handler 在 read 侧硬编码 {@code TypeReference<List<Uncertainty>>}，
 * 与实例化方式（@TableField 注解 / resultMap 声明 / 全局注册）无关，恒返回
 * 强类型列表；空白 JSON 返回空列表。写侧沿用父类 toJson 实现，序列化行为与
 * 修复前一致。</p>
 */
public class UncertaintyListTypeHandler extends JacksonTypeHandler {

    private static final TypeReference<List<Uncertainty>> UNCERTAINTY_LIST =
            new TypeReference<List<Uncertainty>>() {};

    public UncertaintyListTypeHandler(Class<?> type) {
        super(type);
    }

    /** 与父类构造面保持一致（MP/MyBatis 反射实例化的另一条路径）。*/
    public UncertaintyListTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public Object parse(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<Uncertainty>();
        }
        try {
            return getObjectMapper().readValue(json, UNCERTAINTY_LIST);
        } catch (Exception e) {
            throw new RuntimeException("uncertainties JSONB 反序列化失败: " + e.getMessage(), e);
        }
    }
}
