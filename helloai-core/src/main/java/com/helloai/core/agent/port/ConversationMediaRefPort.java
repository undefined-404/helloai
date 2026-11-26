package com.helloai.core.agent.port;

import java.util.List;
import java.util.Map;

/**
 * 任务关联会话的图片引用端口 —— 消费方 {@code agent} 定义、提供方 {@code planner} 实现
 * （CODE_STYLE §7.2 情形①：消费方低于提供方 ⇒ 端口落消费方，实现侧
 * {@code planner → agent} 属顺向合法）。
 *
 * <p><b>为什么需要它（`VU-8`）</b>：用户在需求对话里上传的图存在 {@code chat_attachment}
 * （planner 域），而子任务执行器只读 {@code attachment}（task 域，按 {@code sub_task_id} 聚合）。
 * <b>两张表没有桥</b>——设计文档 {@code design/HelloAI 需求对话附件设计.md} 明确「不改任务域
 * {@code attachment}」（两套生命周期：会话删除即回收 vs 交付审计长期留存）。本端口是<b>读</b>
 * 而非<b>搬</b>：不复制行、不加来源列、不介入任一侧的删除回收。</p>
 *
 * <p><b>为什么入参是 {@code taskId} 而不是 {@code conversationId}</b>：这是本端口最重要的
 * 设计约束。「任务 → 会话」的映射靠 {@code task.context.conversationId} 这个键，而该键是
 * <b>planner 域写入的私有约定</b>（{@code TaskContextKeys.CONVERSATION_ID}，写入方
 * {@code ClarifyFinalizeServiceImpl}）。若把 {@code conversationId} 当入参，agent 域就必须
 * 自己知道「去 context 里找哪个键」——那等于把 planner 的键空间泄漏成 agent 的第二事实源
 * （判据 4：同一事实双写必分叉，某天键改名就会静默失效）。<b>解析责任留在提供方</b>，
 * 消费方只说「给我这个任务的会话图片」。</p>
 *
 * <p><b>与 {@link ChatAttachmentMediaPort} 的分工</b>：那个端口读<b>字节</b>（按
 * {@link MediaRef#source()} 分派，对话面 / 执行面共用装载器）；本端口只列<b>引用</b>
 * （不读字节，落实不变量 I-3）。两者串成一条链：本端口产出引用 → 装载器判能力/类型/体积
 * → 才调字节端口。</p>
 *
 * <p><b>只列图片、不列文档</b>：文档正文由 planner 侧的拆解期快照与需求包承载，执行期再注入
 * 一遍会重复占用预算；且「哪些文档该进 Prompt」是拆解期的判定，不该在执行期重做一遍
 * （两处各判一次必然漂移）。音频/视频同样不列——在用模型的 {@code capabilities.input}
 * 只声明 {@code text/image}，塞进去会被 provider 直接 400。</p>
 *
 * <p><b>`G-042` 作用域</b>：执行面此前拿的是<b>整个会话</b>的图，多图会话下每个子任务的 prompt
 * 都被无关图占满，模型会把 A 图上的要求落到 B 子任务上。现在改为按<b>本子任务声明的附件</b>
 * 取图（声明由拆解期 Planner 给出，落在 {@code sub_task.context}）；无声明时才回落会话全量，
 * 且<b>必须把回落原因带回去</b>让 prompt 显式告知模型——否则模型会把「看到的是全量」误当成
 * 「这些都和我相关」，等于换了个方式犯同一个错。</p>
 */
public interface ConversationMediaRefPort {

    /**
     * 列出本子任务<b>可送模型</b>的图片引用（按上传顺序）。
     *
     * <p><b>作用域判定顺序</b>：</p>
     * <ol>
     *   <li>本子任务声明了专属附件（{@code sub_task.context} 里的附件 ID 清单）且能解析到图片
     *       ⇒ 只返回这几张，{@link ScopedMediaRefs#scoped()} 为 {@code true}；</li>
     *   <li>未声明 / 声明的全不是图片 / 解析不到 ⇒ <b>回落会话全量</b>，
     *       {@code scoped=false} 并带回落原因。</li>
     * </ol>
     *
     * <p><b>绝不抛异常</b>：会话不存在、任务无关联会话、查询失败一律按回落处理。
     * 媒体是<b>增强</b>而非必要条件——历史任务（会话附件功能上线前建的）本就没有
     * {@code conversationId} 键，此时让它把执行链打断毫无道理。</p>
     *
     * @param taskId         主任务 ID（可空；空 ⇒ 空表）
     * @param subTaskId      子任务 ID（可空；空 ⇒ 无法判定作用域，按回落处理）
     * @param subTaskContext 子任务上下文快照（可空）。<b>由消费方原样透传、不做解析</b>——
     *                       哪个键代表附件声明是 planner 域私有约定，消费方不认识它
     *                       （同「为什么入参不是 conversationId」的理由）。
     * @return 图片引用 + 作用域结论（绝不为 {@code null}；无图时为空表）
     */
    ScopedMediaRefs listScopedImages(Long taskId, Long subTaskId, Map<String, Object> subTaskContext);

    /**
     * 列出本子任务<b>作用域内的会话输入附件</b>（`G-051`）——供执行通道注入沙箱，<b>不限 MIME</b>。
     *
     * <p><b>为什么和 {@link #listScopedImages} 分成两个方法（而不是加个「只图片」开关）</b>：
     * 两者是<b>两个消费目的</b>，口径在三处刻意不同，合并只会让每处都要带开关参数：</p>
     *
     * <table border="1">
     *   <caption>两条通道的口径差异</caption>
     *   <tr><th></th><th>{@code listScopedImages}（送模型）</th><th>本方法（进沙箱）</th></tr>
     *   <tr><td>类型</td><td>只图片</td><td>全部（图片 / 文档 / 数据文件）</td></tr>
     *   <tr><td>未声明时</td><td><b>回落会话全量</b>并带原因</td><td><b>返回空表</b>，绝不回落</td></tr>
     *   <tr><td>顺序</td><td>最新在前（{@code G-046} 截断保新）</td><td>上传正序（无截断语义）</td></tr>
     * </table>
     *
     * <p><b>★为什么这里必须 fail-close（未声明就一张不给）</b>：本方法的产出会被写成沙箱内的
     * <b>可执行文件</b>。若照视觉通道的「无声明即全量」口径，任何子任务都能把用户在该会话上传的
     * 任意文件拉进自己的沙箱——那既是作用域失效（`G-042` 白费），也是越权面放大。视觉通道之所以
     * 能回落，是因为「多送一张图」的最坏后果是模型分心；这里的最坏后果是拿到不该拿的字节。</p>
     *
     * <p><b>为什么复用本端口而不是另起一个</b>：作用域解析有两段 planner 私有知识——
     * {@code task.context.conversationId} 与 {@code sub_task.context} 里的附件声明。这两段
     * 已经是本端口的职责；再建一个实现会把同一段解析复制两份（判据 4：同一事实双写必分叉，
     * 某天键改名就会一边失效一边还能用）。本方法只是把「声明 → 引用」的<b>同一份解析</b>
     * 用在另一个消费目的上。</p>
     *
     * <p><b>绝不抛异常</b>：与 {@link #listScopedImages} 同款容错——会话不存在、任务未关联会话、
     * 查询失败一律按「无输入附件」处理，由消费方按「缺省即拒绝注入」继续（本方法的空表语义
     * 天然就是拒绝，不需要消费方再判一次）。</p>
     *
     * @param taskId         主任务 ID（可空；空 ⇒ 空表）
     * @param subTaskId      子任务 ID（可空；仅用于观测与日志归属）
     * @param subTaskContext 子任务上下文快照（可空；<b>原样透传、消费方不做解析</b>）
     * @return 作用域内的会话附件引用（上传正序；绝不为 {@code null}，未声明/解析不到即空表）
     */
    List<MediaRef> listScopedInputAttachments(Long taskId, Long subTaskId, Map<String, Object> subTaskContext);

    /**
     * 作用域取图结果（`G-042`）：引用清单 + 「是不是真的按作用域取的」+ 回落原因。
     *
     * <p><b>为什么把三者放一个返回值里</b>：它们由<b>同一个判定</b>产生。若拆成
     * {@code listScopedImages} + {@code fallbackReason} 两次调用，两次之间会话可能变化，
     * 出现「按作用域取了 3 张，却又说自己回落了全量」这种自相矛盾的 prompt
     * （判据 32 / 4：同一判定的多个产物必须一次产出）。</p>
     *
     * @param refs         本次可送模型的图片引用（按上传顺序；空表 = 无图）
     * @param scoped       {@code true} = 严格按本子任务声明的附件取的；{@code false} = 回落
     * @param fallbackNote 回落原因（面向模型的一句话，会追加进执行期 prompt）；
     *                     {@code scoped=true} 时恒为 {@code null}
     */
    record ScopedMediaRefs(List<MediaRef> refs, boolean scoped, String fallbackNote) {

        /** 无图且无回落说明（如任务本就没有关联会话）。 */
        public static ScopedMediaRefs empty() {
            return new ScopedMediaRefs(List.of(), false, null);
        }

        /** 按作用域取到的结果。 */
        public static ScopedMediaRefs ofScoped(List<MediaRef> refs) {
            return new ScopedMediaRefs(refs == null ? List.of() : List.copyOf(refs), true, null);
        }

        /** 回落会话全量的结果（{@code note} 必填，空串按「未标注」处理但仍算回落）。 */
        public static ScopedMediaRefs ofFallback(List<MediaRef> refs, String note) {
            return new ScopedMediaRefs(refs == null ? List.of() : List.copyOf(refs), false, note);
        }
    }
}