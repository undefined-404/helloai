package com.helloai.core.task.service;

import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.port.SubTaskView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `LOG-20261011-007` A 能力闸信号判定单测（{@link SubTaskDispatchService#isAttachmentReadDense}）。
 *
 * <p>锁定「<b>两侧信号共现</b>」这一口径：源侧（原始文件 / 原始字段）+ 动作侧（逐项核对类）
 * 必须同时出现才判命中。同时锁定它与 §6.52「执行密集」是<b>两个不同信号类</b>
 * （不可互相替代）。</p>
 */
@DisplayName("A 能力闸·需读原始附件信号判定（SubTaskDispatchService）")
class SubTaskAttachmentReadSignalTest {

    private static SubTask subTask(String content, String acceptance, String deliverable) {
        SubTask st = new SubTask();
        st.setContent(content);
        st.setAcceptance(acceptance);
        st.setDeliverable(deliverable);
        return st;
    }

    private static SubTaskView view(String content, String acceptance, String deliverable) {
        return new SubTaskView(1L, 10L, null, null, null, "标题",
                content, deliverable, acceptance, null, Map.of(), List.of());
    }

    @Nested
    @DisplayName("命中：事故原文形态")
    class Hit {

        @Test
        @DisplayName("LOG-20261011-007 原文（源字段 + 逐项标注）→ 命中")
        void incidentOriginalTextHits() {
            SubTask st = subTask(
                    "盘点数据文件字段：对实际数据文件逐项标注存在/缺失/命名不一致",
                    "全部源字段为 PENDING（验收标准 1）",
                    "字段盘点表");
            assertTrue(SubTaskDispatchService.isAttachmentReadDense(st));
        }

        @Test
        @DisplayName("「导出文件」+「逐项核对」→ 命中")
        void exportFileWithItemizedCheckHits() {
            assertTrue(SubTaskDispatchService.isAttachmentReadDense(
                    subTask("按导出文件逐项核对表头命名", null, null)));
        }

        @Test
        @DisplayName("SubTaskView 重载与实体重载同口径")
        void viewOverloadMatchesEntityOverload() {
            String content = "对原始文件逐行核对其中的每条记录";
            assertTrue(SubTaskDispatchService.isAttachmentReadDense(view(content, null, null)));
            assertTrue(SubTaskDispatchService.isAttachmentReadDense(subTask(content, null, null)));
        }
    }

    @Nested
    @DisplayName("不命中：单侧信号不足 / 与执行密集区分")
    class Miss {

        @Test
        @DisplayName("仅源侧（有导出文件、无逐项核对动作）→ 不命中（词表刻意从窄）")
        void sourceOnlyMisses() {
            assertFalse(SubTaskDispatchService.isAttachmentReadDense(
                    subTask("整理导出文件并输出一份统计摘要", null, null)));
        }

        @Test
        @DisplayName("仅动作侧（逐项核对，但无原始文件语义）→ 不命中")
        void verifyOnlyMisses() {
            assertFalse(SubTaskDispatchService.isAttachmentReadDense(
                    subTask("逐项核对上游文本摘要中的结论是否自洽", null, null)));
        }

        @Test
        @DisplayName("纯描述类任务（撰写文档）→ 不命中")
        void plainWritingMisses() {
            assertFalse(SubTaskDispatchService.isAttachmentReadDense(
                    subTask("撰写接口清单与使用说明", "结构完整、示例可运行", "README.md")));
        }

        @Test
        @DisplayName("null 子任务 → 不命中（消费方无需再判空）")
        void nullMisses() {
            assertFalse(SubTaskDispatchService.isAttachmentReadDense((SubTask) null));
            assertFalse(SubTaskDispatchService.isAttachmentReadDense((SubTaskView) null));
        }

        @Test
        @DisplayName("两个信号类互不替代：脚本类任务命中「执行密集」但不命中「需读原始附件」")
        void executionDenseAndAttachmentReadAreDistinct() {
            SubTask scriptTask = subTask("编写 verify-order-expire.ps1 脚本并执行验证", "脚本运行通过",
                    "verify-order-expire.ps1");
            assertTrue(SubTaskDispatchService.isExecutionDense(scriptTask));
            assertFalse(SubTaskDispatchService.isAttachmentReadDense(scriptTask));
        }
    }
}
