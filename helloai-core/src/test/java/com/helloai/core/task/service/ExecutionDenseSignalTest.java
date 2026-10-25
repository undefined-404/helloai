package com.helloai.core.task.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.52 执行密集信号词表：`脚本化` 类形容词用法不得命中（★2026-10-25 误判修正）。
 *
 * <p><b>事故来源</b>：子任务 `2114355728453206020`「字数与合规机器终检」的 `content`
 * 写着「对定稿<b>执行脚本</b>化终检」，命中了旧词条 {@code 执行脚本} → 判成执行密集 →
 * 派发侧拒绝（首选 + 替代全 skip）→ 人工强行指派 → 执行成功（3 次 `internal_run_in_sandbox`
 * 实测 `exitCode=0`）→ <b>核验侧又用同一判据拒一次</b>，产出完整却永远停在 REVIEW。</p>
 *
 * <p>「脚本化 X」= 用脚本的方式做 X，「脚本」是修饰语而非可执行本体，与「需要在本机跑脚本文件」
 * 是两件事。本类逐字锁定这条边界，并同时锁定强信号（文件后缀）不得被误伤。</p>
 *
 * <p><b>为什么直接测 {@link Pattern} 而不测 {@code isExecutionDense} 实体方法</b>：
 * 后者的全部逻辑就是「content/acceptance/deliverable 三段 join 后 `find()`」，
 * 追加一层实体构造不增加任何覆盖，反而让本表更难读、更难逐条核对。</p>
 */
class ExecutionDenseSignalTest {

    private static final Pattern PATTERN = SubTaskDispatchService.EXECUTION_DENSE_PATTERN;

    private static boolean matches(String text) {
        return PATTERN.matcher(text).find();
    }

    @Test
    @DisplayName("事故原文：『对定稿执行脚本化终检』不命中（脚本化 = 形容词）")
    void shouldNotMatchIncidentWording() {
        assertThat(matches("对定稿执行脚本化终检：统计含标题与小节名的中文字符数并判定是否超限；"
                + "扫描全文是否出现外部链接、参考文献或引用类标记。"))
                .isFalse();
    }

    @Test
    @DisplayName("『脚本方式 / 脚本化处理』类形容词用法一律不命中")
    void shouldNotMatchAdjectivalUsages() {
        assertThat(matches("用脚本方式校验全文")).isFalse();
        assertThat(matches("运行脚本化处理流程")).isFalse();
        assertThat(matches("执行脚本化改造")).isFalse();
    }

    @Test
    @DisplayName("真正的『动词 + 脚本』仍命中（收窄不得误伤原信号）")
    void shouldStillMatchVerbPlusScript() {
        assertThat(matches("执行脚本并验证")).isTrue();
        assertThat(matches("运行脚本收集结果")).isTrue();
        assertThat(matches("编写脚本做回归")).isTrue();
    }

    @Test
    @DisplayName("强信号（脚本文件后缀 / 容器 / 构建 / 启服务）不受收窄影响")
    void shouldKeepStrongSignals() {
        assertThat(matches("产出 verify-order-expire.ps1")).isTrue();
        assertThat(matches("执行 ./run.sh")).isTrue();
        assertThat(matches("docker compose up -d")).isTrue();
        assertThat(matches("mvn clean package")).isTrue();
        assertThat(matches("启动服务后跑用例")).isTrue();
        assertThat(matches("npm run build")).isTrue();
    }

    @Test
    @DisplayName("反例：纯文档类措辞不命中（防闸门整体过敏）")
    void shouldNotMatchPureDocumentTasks() {
        assertThat(matches("撰写说明文档正文初稿，字数 ≤500，无外部链接")).isFalse();
        assertThat(matches("审读可传阅性并定稿，保持结构不变")).isFalse();
        assertThat(matches("统计中文字符数并输出 CJK_COUNT=NNN PASS")).isFalse();
    }
}
