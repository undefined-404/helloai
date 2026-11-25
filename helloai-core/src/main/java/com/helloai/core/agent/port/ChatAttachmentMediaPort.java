package com.helloai.core.agent.port;

/**
 * 会话附件字节读取端口 —— 消费方 {@code agent} 定义、提供方 {@code planner} 实现
 * （CODE_STYLE §7.2 情形①：消费方低于提供方 ⇒ 端口落消费方，实现侧
 * {@code planner → agent} 属顺向合法）。
 *
 * <p><b>为什么需要它（`VU-9`）</b>：对话面（工作台 / 新建对话 / 澄清问答）的图存在
 * {@code chat_attachment}（planner 域），而 agent 域现成的 {@link AttachmentPort} 只读
 * task 域 {@code attachment}。<b>两张表没有桥</b>——设计文档
 * {@code design/HelloAI 需求对话附件设计.md} 明确「不改任务域 {@code attachment}」
 * （两套生命周期：会话删除即回收 vs 交付审计长期留存）。本端口是「读」而不「搬」：
 * 不复制行、不改表结构、不介入任一侧的删除回收。</p>
 *
 * <p><b>为什么不复用 {@code ChatAttachmentReferencePort}</b>：那个端口服务的是 system 域的
 * <b>存储对账</b>（全量含逻辑删除的元数据，宁可漏判孤儿也不误删），语义与本端口相反——
 * 本端口只要「本轮用户点名的那几张图的字节」，且<b>必须只看未删除行</b>
 * （对账要宽、取数要严）。同一张表两个相反的读取口径，合并会让两边都得带一堆开关参数。</p>
 *
 * <p><b>只读单条、不批量</b>：装载侧逐条 try/catch（单张读失败只跳过该张，
 * 媒体是增强不是必要条件），批量接口会把「部分失败」的语义塞进返回值形状里，
 * 反而让调用方多一层解析。</p>
 */
public interface ChatAttachmentMediaPort {

    /**
     * 读取会话附件的字节内容。
     *
     * <p>入参已由调用方按 {@link MediaRef#fileSize()} 与 MIME 预筛过大小与类型，
     * 本方法不再重复判定（判定留在一处，避免两套阈值漂移）。</p>
     *
     * @param attachmentId 会话附件主键
     * @return 文件字节（绝不返回 {@code null}；读不到时抛异常，由调用方逐条降级）
     * @throws com.helloai.common.base.BizException 附件不存在 / 已删除 / 存储不可读
     */
    byte[] loadChatAttachmentContent(Long attachmentId);
}