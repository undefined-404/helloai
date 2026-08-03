package com.helloai.common.constant;

/**
 * 任务最终整合报告的生成状态（V41）。
 *
 * <p>与任务主状态（{@link TaskStatus}）解耦：任务收口即 DONE，报告生成是增值物，
 * 单独用本字段表达"生成中/失败"中间态，避免把"报告生成中"塞进任务状态机
 * （否则 DONE 语义、自动收尾判定都会被破坏）。</p>
 *
 * <ul>
 *   <li>{@code NONE}：尚未生成（默认值）</li>
 *   <li>{@code GENERATING}：生成中（CAS 置位防重入，手动/自动两条路径互斥）</li>
 *   <li>{@code DONE}：已生成（final_report 非空）</li>
 *   <li>{@code FAILED}：最近一次生成失败（可手动重试）</li>
 * </ul>
 */
public enum FinalReportStatus {
    NONE,
    GENERATING,
    DONE,
    FAILED
}
