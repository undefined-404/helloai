package com.helloai.core.task.service;

/**
 * 派生任务报告回挂（`ADR-008` §15，方案 C：物化附件）。
 *
 * <p>派生任务 `Y` 完成后，把「`A2` 原产出 + `Y.final_report`」合并渲染成一份附件挂到 `A2` 上，
 * 下游依赖读取 `A2` 时走既有的「附件优先」路径即可拿到合并结果，读侧零改动。</p>
 *
 * <p><b>为什么必须合并而不是只挂报告</b>：{@code loadUpstreamContent} 是附件优先——
 * 一旦 `A2` 上有任何附件，就不再读原始产出。只挂 `Y` 的报告会把 `A2` 自己的产出顶掉，
 * 下游看到的内容「凭空换了一套」（§15.3）。</p>
 */
public interface DerivedReportAttachmentService {

    /**
     * 若派生任务已 `DONE` 且报告已落库，则回挂（幂等：同名 ACTIVE 附件已存在时跳过）。
     *
     * @param derivedTaskId 派生任务 id
     * @return true=本次实际写入；false=条件不满足或已存在
     */
    boolean attachIfDue(Long derivedTaskId);
}
