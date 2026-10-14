package com.helloai.core.system.port;

/**
 * 平台级 API Key 主备操作结果只读快照（`M-8`）。
 *
 * <p>把「操作前后的启用位是谁」原样交出，使上游（agent 域编排 + API 响应）无需再查库即可
 * 如实报告：本次是否动了启用位、动到了哪一把。</p>
 *
 * <p><b>不设 {@code activeChanged} 布尔字段</b>：它恒等于
 * {@code !Objects.equals(previousActiveId, activeId)}，冗余字段必然漂移 ⇒ 由消费方自行比较。</p>
 *
 * <p><b>不设 {@code message} 文案</b>（与 `M-6`/`M-7` 的 {@code ApiKeySaveOutcome.message()} 不同）：
 * 那两个的文案承载了「前端无法预知的结论」（同一 Key 二次录入 ⇒ 已存在，未重复保存）；
 * 本类三个操作的结论由请求方自己发起，前端可自述 ⇒ 不下发冗余文案。失败一律走
 * {@code BizException}（fail-close），由统一异常出口转成可读错误。</p>
 *
 * @param credentialId     本次操作的目标凭证 ID（切换 = 被升为启用位的那把；停用 / 删除 = 目标本身）
 * @param fingerprint      目标凭证指纹（不可逆，24 字符）
 * @param previousActiveId 操作前的启用位 ID；此前无启用位时为 {@code null}
 * @param activeId         操作后的启用位 ID；不变量要求该 provider 有凭证时恒非 {@code null}
 * @see PlatformApiKeyView
 */
public record PlatformApiKeyChange(
        Long credentialId,
        String fingerprint,
        Long previousActiveId,
        Long activeId
) {
}
