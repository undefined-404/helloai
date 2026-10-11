package com.helloai.core.event;

/**
 * 事件码目录项（REF-6.8，事件码服务端单一来源的下发形态）。
 *
 * @param code  事件码（snake_case，数据库存储值）
 * @param label 人话短标签（前端时间线显示）
 * @param desc  详细说明（可空）
 */
public record EventCatalogItem(String code, String label, String desc) {
}