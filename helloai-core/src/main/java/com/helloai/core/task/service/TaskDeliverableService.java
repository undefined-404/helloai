package com.helloai.core.task.service;

/**
 * 主任务交付物实时聚合打包（Kimi 式 zip 下载）。
 *
 * <p>下载时现场从子任务产出组 zip 返回，不预生成、不落库：历史任务立即可下、
 * 返工后重下即最新、无存储成本。zip 结构：</p>
 * <ul>
 *   <li>{@code 00-任务概览.md}：任务信息 + 子任务完成情况表（状态/Agent/完成时间/核验结论）</li>
 *   <li>{@code NN-xxx}：按依赖拓扑序编号的 DONE 子任务产出——优先收录方案2 物化的
 *       local:// 附件（同名取最新一轮），无附件时回退 context.lastExecution.output
 *       生成单 Markdown（兼容物化上线前的历史任务）</li>
 * </ul>
 *
 * <p>非 DONE 子任务不收录产出，仅在概览表标注状态。产出均为文本，
 * 内存聚合（byte[]）足够，无需流式落盘。</p>
 */
public interface TaskDeliverableService {

    /** 打包结果：fileName 为建议下载名（含 .zip），content 为压缩包字节。 */
    record DeliverablePackage(String fileName, byte[] content) {
    }

    /**
     * 实时聚合任务交付物 zip；任务不存在抛 BizException(404)。
     *
     * @param taskId 顶层任务 ID
     * @return 交付物压缩包
     */
    DeliverablePackage buildZip(Long taskId);
}
