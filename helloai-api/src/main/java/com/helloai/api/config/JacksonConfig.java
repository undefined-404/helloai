package com.helloai.api.config;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.api.support.RawLongSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HTTP 响应的 JSON 序列化口径（{@code Long} 的两层规则）。
 *
 * <p><b>背景</b>：雪花 ID 为 19 位十进制数，超过 JavaScript {@code Number} 的 53 位有效位，
 * 直接以数字下发会在前端静默丢精度（前端拿到的 {@code id} 与真实 {@code id} 不同 ⇒ 请求打错
 * 对象）。因此本项目把 {@code Long} 序列化为字符串。</p>
 *
 * <p><b>原口径的问题</b>：旧实现按<b>类型</b>一刀切（{@code Long}/{@code long}/{@code BigInteger}
 * 全部 {@code ToStringSerializer}），<b>不区分「ID」与「普通数值」</b>——分页 {@code total/pages/current}、
 * 仪表盘计数、耗时（{@code latencyMs}）、字节数（{@code fileSize}）等非 ID 数值同样被写成字符串，
 * 与前端 TypeScript 类型（{@code number}）不符；并曾导致 Element Plus {@code <el-pagination :total>}
 * 校验失败、组件不渲染（前端为此写了兜底补丁，见 {@code helloai-ui/src/api/request.ts}）。</p>
 *
 * <p><b>现口径（两层）</b>：</p>
 * <ol>
 *   <li><b>默认层（保 ID 精度）</b>：{@code Long}/{@code long}/{@code BigInteger} 仍一律写字符串。
 *       它同时覆盖<b>属性修饰器看不见</b>的场景——{@code R<List<Long>>} 的 ID 列表
 *       （如权限/角色/部门 ID 集合）、{@code Map<String,Object>} 中的 Long 值、运行时泛型擦除处
 *       ——这些位置无法按属性名判定语义，保持字符串是<b>唯一不会引入精度回归</b>的选择。</li>
 *   <li><b>例外层（修非 ID 数值）</b>：{@link #nonIdLongAsNumberModule()} 对「属性名非 ID 语义
 *       <b>且</b>未显式声明 {@code @JsonSerialize}」的 {@code Long} 属性改用
 *       {@link RawLongSerializer}，写成 JSON 数字。</li>
 * </ol>
 *
 * <p><b>为什么默认层不反过来</b>（即「默认 number、按名把 ID 改字符串」）：那样会让
 * {@code R<List<Long>>} 这类无属性名的 ID 集合退化为数字，产生<b>静默精度丢失</b>——比「数字被写成
 * 字符串」严重得多，且难以察觉。故默认必须偏向安全一侧。</p>
 *
 * <p><b>存量 ID 字段的保护来源</b>：{@code Long}</b> 命名为 {@code id} 或以 {@code Id}/{@code ID}
 * 结尾（如 {@code taskId}/{@code reviewerAgentId}）者由默认层覆盖，无需逐个注解；
 * <b>命名不符合但语义是 ID</b> 的字段（如 {@code assignedAgent}/{@code reviewerAgent}/{@code createdBy}）
 * 必须显式加 {@code @JsonSerialize(using = ToStringSerializer.class)}——本类会尊重显式声明，不覆盖。
 * {@code List<Long>} 形式的计数序列（如 {@code DashboardTrend.createdCounts}）需显式加
 * {@code @JsonSerialize(contentUsing = RawLongSerializer.class)}。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonLongSerializationCustomizer() {
        return builder -> {
            // 默认层：护住雪花 ID 精度（含修饰器无法按属性名判定的位置）。
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
            // 例外层：非 ID 命名的 Long 属性回退为 JSON number。
            builder.modulesToInstall(nonIdLongAsNumberModule());
        };
    }

    /**
     * 构造「非 ID 命名的 {@code Long} 属性写 JSON 数字」的 Jackson 模块。
     *
     * <p>判定顺序（任一命中即<b>跳过</b>，保持默认字符串口径）：</p>
     * <ol>
     *   <li>属性类型不是 {@code Long}/{@code long}（含 {@code List<Long>} 等容器，其原始类型为容器）；</li>
     *   <li>属性名是 ID 语义（见 {@link #isIdName(String)}）；</li>
     *   <li>属性在字段/读方法/写方法/构造参数任一位置显式声明了 {@code @JsonSerialize}
     *       —— 尊重既有显式契约（如 {@code ReviewResponse#reviewerAgent}），不覆盖。</li>
     * </ol>
     */
    private static SimpleModule nonIdLongAsNumberModule() {
        SimpleModule module = new SimpleModule("helloai-non-id-long-as-number");
        module.setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                             BeanDescription beanDesc,
                                                             List<BeanPropertyWriter> beanProperties) {
                Set<String> explicitlyConfigured = explicitlyConfiguredNames(beanDesc);
                for (BeanPropertyWriter writer : beanProperties) {
                    if (!isLongProperty(writer.getType())) {
                        continue;
                    }
                    String name = writer.getName();
                    if (isIdName(name) || explicitlyConfigured.contains(name)) {
                        continue;
                    }
                    writer.assignSerializer(RawLongSerializer.INSTANCE);
                }
                return beanProperties;
            }
        });
        return module;
    }

    /**
     * 收集本类中<b>任一成员</b>带 {@code @JsonSerialize} 的属性名。
     *
     * <p>不能只用 {@code BeanPropertyDefinition#getPrimaryMember()}：{@code @Data} 类同时暴露字段与
     * 读方法，Jackson 的主成员未必是承载注解的那个（注解常写在字段上），会导致漏判并<b>覆盖用户的
     * 显式声明</b>。故字段/读方法/写方法/构造参数四处逐一检查。</p>
     */
    private static Set<String> explicitlyConfiguredNames(BeanDescription beanDesc) {
        Set<String> names = new HashSet<>();
        for (BeanPropertyDefinition def : beanDesc.findProperties()) {
            if (hasJsonSerialize(def.getField())
                    || hasJsonSerialize(def.getGetter())
                    || hasJsonSerialize(def.getSetter())
                    || hasJsonSerialize(def.getConstructorParameter())) {
                names.add(def.getName());
            }
        }
        return names;
    }

    private static boolean hasJsonSerialize(AnnotatedMember member) {
        return member != null && member.getAnnotation(JsonSerialize.class) != null;
    }

    private static boolean isLongProperty(JavaType type) {
        Class<?> raw = type.getRawClass();
        return raw == Long.class || raw == long.class;
    }

    /**
     * 属性名是否为 ID 语义：恰为 {@code id}（忽略大小写），或以 {@code Id} / {@code ID} 结尾。
     *
     * <p><b>刻意大小写敏感的后缀匹配</b>：若用大小写不敏感的 {@code .*id}，会把 {@code valid}
     * 之类的普通词误判为 ID；而 {@code idleMinutes} 虽以 {@code id} 开头，因后缀不匹配也不受影响。</p>
     */
    static boolean isIdName(String name) {
        return name != null
                && (name.equalsIgnoreCase("id") || name.endsWith("Id") || name.endsWith("ID"));
    }
}
