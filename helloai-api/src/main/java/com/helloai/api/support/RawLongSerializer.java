package com.helloai.api.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 把 {@code Long} 写成 JSON <b>数字</b>（而非字符串）。
 *
 * <p><b>为什么需要它</b>：{@code JacksonConfig} 的默认口径是把 {@code Long}/{@code long}/
 * {@code BigInteger} <b>一律</b>序列化为字符串，用于护住雪花 ID 在 JavaScript 侧的精度
 * （JS {@code Number} 仅 53 位有效位，19 位雪花 ID 直接丢精度）。但该规则<b>不区分「ID」与
 * 「普通数值」</b>——分页 {@code total}、仪表盘计数、耗时、字节数等<b>非 ID 数值</b>也被写成
 * 字符串，与前端 TypeScript 类型（{@code number}）不符，并曾导致 Element Plus 分页组件
 * {@code :total} 校验失败、组件不渲染（见 {@code helloai-ui/src/api/request.ts} 的兜底注释）。</p>
 *
 * <p>{@code JacksonConfig} 通过 {@code BeanSerializerModifier} 把「非 ID 命名且未显式声明
 * 序列化器」的 {@code Long} 属性改用本序列化器，从而在<b>不牺牲 ID 精度</b>的前提下修正这些
 * 字段；其余场景（{@code R<List<Long>>} 的 ID 列表、{@code Map<String,Object>} 中的 Long 值、
 * 运行时泛型擦除处）仍走默认字符串口径，避免任何精度回归。</p>
 *
 * <p><b>刻意声明为 {@code JsonSerializer<Object>}</b>：{@code BeanPropertyWriter#assignSerializer}
 * 的形参类型是 {@code JsonSerializer<Object>}，声明为 {@code JsonSerializer<Long>} 会因泛型
 * 不变性无法传入；{@code Object} 是 {@code Long} 的超类型，故同样可用于
 * {@code @JsonSerialize(contentUsing = ...)}（如 {@code List<Long>} 计数序列）。</p>
 */
public final class RawLongSerializer extends JsonSerializer<Object> {

    public static final RawLongSerializer INSTANCE = new RawLongSerializer();

    private RawLongSerializer() {
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeNumber(((Number) value).longValue());
        }
    }
}
