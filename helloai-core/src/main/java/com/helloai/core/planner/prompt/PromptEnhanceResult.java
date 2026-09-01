package com.helloai.core.planner.prompt;

/**
 * 输入优化结果（CODE_STYLE V2 §32）：原样保留用户输入 + 优化后版本并列返回，
 * 由前端展示预览并让用户自行决定是否回填，服务端不做任何覆盖/自动发送。
 */
public record PromptEnhanceResult(String originalPrompt, String optimizedPrompt) {
}
