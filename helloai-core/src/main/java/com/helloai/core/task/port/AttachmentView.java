package com.helloai.core.task.port;

/**
 * 附件只读快照（task 域对外契约，RM5 批 4）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 review 域（下标 1）【高于】提供方
 * task 域（下标 2），故读契约落【提供方】{@code task.port}；消费方 {@code review → task.port}
 * 属顺向合法，从此不再 import {@code task.entity.Attachment}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：{@code id} / {@code fileName} / {@code fileType} /
 * {@code mimeType} / {@code fileSize}。</p>
 *
 * <p><b>派生字段登记（W11）</b>：{@code contentLoadable} 非实体原样投影，由提供方映射器顺带计算
 * （{@code = AttachmentService#isContentLoadable(entity)}）——判定依据属 task 领域规则，
 * 由提供方判定，消费方零判定、零额外 DB 往返。</p>
 *
 * <p><b>{@code round}（`V157` / `VU-3` 第 4 条）</b>：登记时点轮次快照，实体原样投影。
 * 消费方（核验证据段）拿它和子任务当前轮比对，只为产出一句<b>文本提示</b>
 * 「这份不是本轮登记的」——<b>不参与</b>覆盖判定、不改台账行格式（台账是机器格式，
 * 冻结进 {@code review_record} 后要能被历史快照解析）。{@code null} 表示上游未提供
 * （历史数据/兼容调用），此时一律不提示，绝不按 0 猜。</p>
 */
public record AttachmentView(
        Long id,
        String fileName,
        String fileType,
        String mimeType,
        Long fileSize,
        boolean contentLoadable,
        Integer round
) {

    /** 兼容构造（无轮次语义的调用点：测试夹具与历史路径）。 */
    public AttachmentView(Long id, String fileName, String fileType, String mimeType,
                          Long fileSize, boolean contentLoadable) {
        this(id, fileName, fileType, mimeType, fileSize, contentLoadable, null);
    }
}
