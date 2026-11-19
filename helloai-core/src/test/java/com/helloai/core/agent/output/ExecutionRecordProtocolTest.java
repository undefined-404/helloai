package com.helloai.core.agent.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ExecutionRecordProtocol} 剥离函数的定向单测（{@code MA1-5}）。 */
class ExecutionRecordProtocolTest {

    private static final String HDR = ExecutionRecordProtocol.SECTION_HEADER;

    @Test
    void nullOutputReturnsNull() {
        assertNull(ExecutionRecordProtocol.strip(null));
    }

    @Test
    void noHeaderReturnsAsIs() {
        String out = "## 执行总结\n\n完成 A 与 B。\n";
        assertEquals(out, ExecutionRecordProtocol.strip(out));
    }

    @Test
    void stripsHeaderAndTrailingBlock() {
        String body = "## 执行总结\n\n完成 A 与 B。";
        String tail = "\n\n" + HDR + "\n\n| 键 | 值 |\n|---|---|\n\n---\n";
        assertEquals(body, ExecutionRecordProtocol.strip(body + tail));
    }

    @Test
    void stripsFromLineStartLeavingNoLeadingBlank() {
        String body = "正文";
        String tail = "\n\n" + HDR + "\n\n---\n";
        // 只应保留正文本身，尾随空白被 stripTrailing 清掉
        assertEquals("正文", ExecutionRecordProtocol.strip(body + tail));
    }

    @Test
    void emptyBodyAfterStripReturnsEmpty() {
        String tail = HDR + "\n\n---\n";
        assertEquals("", ExecutionRecordProtocol.strip(tail));
    }

    @Test
    void headerAppearsOnlyInTextStillStripped() {
        String out = HDR + " 说明文字\n";
        assertEquals("", ExecutionRecordProtocol.strip(out));
    }

    @Test
    void headerInlineInSentenceIsNotStripped() {
        // 正文里提到该字面（不在行首）不算回填块——否则改这个协议的子任务会把自己的正文砍掉
        String out = "正文提到 " + HDR + " 这个标记，但不构成块。\n";
        assertEquals(out, ExecutionRecordProtocol.strip(out));
    }

    @Test
    void quotedHeaderInBodyKeepsBodyAndStripsTrailingBlock() {
        // 正文行首引用一次 + 尾部真正的块：只剥尾部块，引用保留（取最后一次出现）
        String body = HDR + "\n（以上是正文对标记的引用）";
        String tail = "\n\n" + HDR + "\nSUMMARY: 说明\n---\n";
        assertEquals(body, ExecutionRecordProtocol.strip(body + tail));
    }

    @Test
    void onlySectionHeaderConstantsNotEmpty() {
        assertTrue(HDR.startsWith("## "));
        assertEquals("---", ExecutionRecordProtocol.BLOCK_END);
    }
}