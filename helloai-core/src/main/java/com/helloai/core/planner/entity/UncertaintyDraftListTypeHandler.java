package com.helloai.core.planner.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.helloai.core.task.port.UncertaintyDraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * `task_plan_draft.uncertainties`（JSONB → {@code List<UncertaintyDraft>}）强类型 TypeHandler。
 *
 * <p><b>为什么不能直接用 {@code PgJsonbTypeHandler}</b>：它按 getter 擦除后的
 * {@code List.class} 反序列化，Jackson 没有具体泛型目标，会把 JSON 数组读成
 * {@code List<LinkedHashMap>}。强类型消费点随后 {@code u.kind()} 就抛 ClassCastException。
 * 这个坑本项目已经踩过一次（2026-09-10 派单死锁，见 {@code UncertaintyListTypeHandler}
 * 的类注释），这里不重犯。</p>
 *
 * <p><b>为什么不复用 {@code UncertaintyListTypeHandler}</b>：那个 handler 的元素类型是
 * {@code task.entity.Uncertainty}（执行期实体）。草案期用的是
 * {@code task.port.UncertaintyDraft}（拆解 Prompt 输出的 JSON 契约形状）。两者字段同形但
 * 语义不同——前者是落库后的执行期事实，后者是还没确认的申报。planner 域**不能**依赖
 * {@code task.entity}（反向依赖会被架构守卫阻断），只能依赖 port。</p>
 */
public class UncertaintyDraftListTypeHandler extends PgJsonbTypeHandler {

    private static final TypeReference<List<UncertaintyDraft>> DRAFT_LIST =
            new TypeReference<List<UncertaintyDraft>>() {};

    public UncertaintyDraftListTypeHandler(Class<?> type) {
        super(type);
    }

    /** 与父类构造面保持一致（MP/MyBatis 反射实例化的另一条路径）。 */
    public UncertaintyDraftListTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public Object parse(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<UncertaintyDraft>();
        }
        try {
            return getObjectMapper().readValue(json, DRAFT_LIST);
        } catch (Exception e) {
            throw new RuntimeException("plan draft uncertainties JSONB 反序列化失败: " + e.getMessage(), e);
        }
    }
}
