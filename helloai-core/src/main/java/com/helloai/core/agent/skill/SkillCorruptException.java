package com.helloai.core.agent.skill;

/**
 * 技能包定义不可用（frontmatter 缺失 / 围栏未闭合 / 字段缺失或非法等）。
 *
 * <p><b>消息面向人</b>：一句话说明「哪个字段、错在哪」，直接用于技能目录 API 的 {@code error} 字段
 * 与扫描期日志，不承载堆栈语义。</p>
 *
 * <p><b>为什么是受检异常</b>：强制解析调用点显式决定降级方式（扫描链路记 corrupt 条目），
 * 避免「忘了处理」被静默吞掉。</p>
 */
public class SkillCorruptException extends Exception {

    private static final long serialVersionUID = 1L;

    public SkillCorruptException(String message) {
        super(message);
    }

    public SkillCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
