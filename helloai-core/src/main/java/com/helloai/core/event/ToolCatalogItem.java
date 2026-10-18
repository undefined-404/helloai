package com.helloai.core.event;

/**
 * 工具名目录项（2026-10-18，工具名服务端单一来源的下发形态）。
 *
 * @param toolName 工具原始标识（与 {@code @Tool(name=…)} 逐字一致）
 * @param label    人话短名（4~6 字，时间线标题/时序图节点显示）
 * @param desc     一句话说明（可空）
 */
public record ToolCatalogItem(String toolName, String label, String desc) {
}
