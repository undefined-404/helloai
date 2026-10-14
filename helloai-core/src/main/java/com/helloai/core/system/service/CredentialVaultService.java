package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.port.CredentialSecret;
import com.helloai.core.system.port.PlatformApiKeyChange;
import com.helloai.core.system.port.PlatformApiKeyView;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 凭证保险库服务。
 *
 * <p>只提供最小可复用能力，供后续 AgentExecutor / ChatClient 链路继续接入。</p>
 */
public interface CredentialVaultService extends IService<CredentialVault> {

    /**
     * 查询 Agent 的当前启用 API Key 凭证。
     */
    CredentialVault getActiveAgentApiKey(Long agentId, String provider);

    /**
     * 查询平台级（PLATFORM/ownerId=0）的当前启用 API Key 凭证。
     *
     * <p>平台级凭证按 provider 唯一（owner_type=PLATFORM、owner_id=0），
     * 由 {@code PlatformProviderConfigService} 读取，替代 yml 启动期一次性绑定。</p>
     */
    CredentialVault getActivePlatformApiKey(String provider);

    // ── 只读快照变体（RM5 批 3）：供 agent 域消费，避免其 import system.entity ──

    /**
     * 查询 Agent 当前启用 API Key 凭证的只读快照。
     *
     * <p>语义与 {@link #getActiveAgentApiKey} 逐字一致（无启用凭证返回 null），
     * 仅投影解密所需字段（{@code encryptedValue} / {@code secretRef}）。</p>
     */
    CredentialSecret getActiveAgentApiKeySecret(Long agentId, String provider);

    /**
     * 查询平台级当前启用 API Key 凭证的只读快照（语义与 {@link #getActivePlatformApiKey} 一致）。
     */
    CredentialSecret getActivePlatformApiKeySecret(String provider);

    /**
     * 查询平台级全部凭证记录（不含加密值明文），供管理端脱敏展示。
     */
    List<CredentialVault> listPlatformCredentials();

    /**
     * 判断平台级是否已配置启用态凭证。
     */
    boolean hasActivePlatformCredential(String provider);

    /**
     * 查询 Agent 的全部凭证记录（不含加密值明文）。
     *
     * <p>按 §6.3 分层红线从 CredentialController 收口。</p>
     */
    List<CredentialVault> listAgentCredentials(Long agentId);

    /**
     * 判断 Agent 当前是否已绑定启用态托管凭证。
     */
    boolean hasActiveAgentCredential(Long agentId);

    /**
     * 以最小 upsert 方式保存 Agent 的 API Key 凭证。
     *
     * <p>先只支持单条启用态记录；后续多 Provider / 多版本轮换再继续扩展。</p>
     *
     * @param fingerprint 明文指纹（`M-6`，{@code com.helloai.core.system.crypto.CredentialFingerprint#of}）；
     *                    {@code secretRef} 型凭证传 {@code null}
     */
    CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                              String encryptedValue, String secretRef,
                                              OffsetDateTime expiresAt,
                                              String remark, String fingerprint);

    /**
     * 以最小 upsert 方式保存平台级 API Key 凭证（ownerId 固定占位 0）。
     *
     * @param fingerprint 明文指纹（`M-6`）；{@code secretRef} 型凭证传 {@code null}
     */
    CredentialVault savePlatformApiKeyCredential(String provider,
                                                 String encryptedValue, String secretRef,
                                                 String remark, String fingerprint);

    /**
     * 以最小 upsert 方式保存 Agent 的 API Key 凭证（无到期时间重载，`M-6` 起含指纹）。
     */
    CredentialVault saveAgentApiKeyCredential(Long agentId, String provider,
                                              String encryptedValue, String secretRef,
                                              String remark, String fingerprint);

    /**
     * 轮换 Agent 的 API Key 凭证：旧凭证 → EXPIRED，新凭证 → ACTIVE。
     *
     * <p>AgentHub 轮换语义：</p>
     * <ul>
     *   <li>旧 ACTIVE 凭证标为 {@code EXPIRED}（非 DISABLED），
     *       区分"人为停用"和"自动轮换"</li>
     *   <li>新建 ACTIVE 凭证，在 remark 中记录 rotated_from_id 审计链</li>
     *   <li>事务内保证一致性：旧凭证过期 + 新凭证创建原子完成</li>
     * </ul>
     *
     * @param agentId        Agent ID
     * @param provider       LLM Provider
     * @param encryptedValue 新凭证加密值
     * @param secretRef      新凭证 Secret 引用
     * @param remark         审计备注
     * @param fingerprint    明文指纹（`M-6`）
     * @return 新创建的 ACTIVE 凭证
     */
    CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                      String encryptedValue, String secretRef,
                                      String remark, String fingerprint);

    /**
     * 轮换平台级 API Key 凭证（旧凭证 → EXPIRED，新凭证 → ACTIVE），ownerId 固定占位 0。
     *
     * @param fingerprint 明文指纹（`M-6`）
     */
    CredentialVault rotatePlatformApiKey(String provider,
                                         String encryptedValue, String secretRef,
                                         String remark, String fingerprint);

    // ── `M-6` Key 指纹：同 provider 内同 Key 去重 / 历史行回填 ──────────────

    /**
     * 判断指定归属 + provider 下是否已存在**同指纹**的凭证（不限状态，含历史 EXPIRED/DISABLED 行）。
     *
     * <p>用于「同一 Key 二次录入 ⇒ 已存在」判重。**作用域 = owner + provider**：
     * 同一把 Key 被不同 provider 或不同 owner 合法复用时，不得互相判重。</p>
     *
     * @param fingerprint 指纹；{@code null} / 空白 ⇒ 直接返回 {@code false}
     */
    boolean existsFingerprint(CredentialOwnerType ownerType, Long ownerId,
                              String provider, String fingerprint);

    /**
     * `M-6` 惰性回填：把指定归属 + provider 下「**指纹为空且密文非空**」的历史行解密补写指纹。
     *
     * <p>迁移 `V111` 无法在 SQL 侧由密文推导指纹（AES-GCM 每次随机 nonce，密文不可还原），
     * 故回填落在**应用层本域内**（agent 域不得 import `system.entity`，见 RM5 批 3 快照先例）。
     * 幂等：只补不覆盖；{@code secretRef} 型与已解密失败的行跳过。</p>
     *
     * @return 本次实际补写的行数
     */
    int backfillFingerprints(CredentialOwnerType ownerType, Long ownerId, String provider);

    // ── `M-7` 批量录入：备位（DISABLED）落库 ─────────────────────────────

    /**
     * 落一条**平台级备位** API Key 凭证（{@code status=DISABLED}），**不触碰当前 ACTIVE 凭证**。
     *
     * <p>供 `M-7` 批量录入使用：对齐 magpie {@code AddKeys}（{@code keys.go:183-222}）在
     * 「已有主 key」时**只往备位追加、不覆盖主 key**的语义。`credential_vault` 的部分唯一索引
     * （{@code uk_credential_vault_*_active}）**只约束 {@code status='ACTIVE'}**，故批量备位落库
     * 不会冲突；备位转正是 `M-8`（多 Key 主备切换）的职责。</p>
     *
     * @param provider       LLM Provider
     * @param encryptedValue AES-GCM 密文
     * @param remark         审计备注
     * @param fingerprint    明文指纹（`M-6` 口径）
     * @return 新建的 DISABLED 凭证
     */
    CredentialVault saveDisabledPlatformApiKeyCredential(String provider,
                                                         String encryptedValue,
                                                         String remark, String fingerprint);

    // ── `M-8` 多 Key 主备：列表 / 手动切换 / 停用 / 删除 ────────────────────

    /**
     * 列出某 provider 下平台级 API Key 的条目快照（**不含密文**，明文仅以脱敏形态出现）。
     *
     * <p>排序 = **启用位在前**（{@code ACTIVE → DISABLED → EXPIRED}），同组内按
     * {@code create_time ASC, id ASC}。该顺序即「顶替启用位时选哪把备位」的依据
     * （对齐 magpie 取「下一把在用的」{@code keys.go:369-379}）。</p>
     *
     * <p>含 {@code EXPIRED}（轮换淘汰的历史行）而不隐藏：隐藏会造成「列表为空但库里
     * 还有行、provider 却显示未配置」的错觉，不如显式标注状态。</p>
     *
     * @param provider LLM Provider；空白 ⇒ 返回空列表
     */
    List<PlatformApiKeyView> listPlatformApiKeyViews(String provider);

    /**
     * `M-8` **手动切换**启用位：把指定的备位凭证升为 {@code ACTIVE}，同
     * (owner, provider, type) 下的原 {@code ACTIVE} 降为 {@code DISABLED}。
     *
     * <p>对齐 magpie {@code UseKey}（{@code keys.go:346-365}）「makes one of a provider's keys
     * the first」；**差异**：magpie 被替换的那把「stays on, next in line」（仍可用于
     * failover），helloai 因红线 {@code N-1} 无自动 failover，被降级行只能落
     * {@code DISABLED}（不参与路由）。</p>
     *
     * <p><b>顺序敏感</b>：{@code uk_credential_vault_*_active} 部分唯一索引只允许一条
     * {@code ACTIVE}，故必须**先降后升**，且两步同事务。两步均走 CAS
     * （{@code WHERE status = <期望值>}），状态被并发改动即整体回滚。</p>
     *
     * @param credentialId 目标备位凭证 ID
     * @param provider     所属 provider（不匹配即拒绝，防跨 provider 误切）
     * @param operator     操作者
     * @return 切换前后启用位快照
     * @throws com.helloai.common.base.BizException 凭证不存在 / 非平台级 API Key / 与 provider
     *                                              不匹配 / 已是启用位 / 为 EXPIRED / CAS 冲突
     */
    PlatformApiKeyChange activatePlatformApiKeyCredential(Long credentialId, String provider,
                                                          String operator);

    /**
     * `M-8` **停用**平台级 API Key（{@code ACTIVE / DISABLED → DISABLED}）。
     *
     * <p>对齐 magpie {@code SetKeyOn}（{@code keys.go:381-405}）「turns a key on or off」，
     * 并遵守其守卫：<b>在用的最后一把必须留在用</b>。落到 helloai 的单一启用位模型即
     * —— 停用**启用位**时若有备位可顶，则**原子地把最旧的一把备位升为启用位**；若无备位可顶，
     * 则 fail-close 拒绝（否则该 provider 会静默变成「未配置」）。停用**备位**是幂等空操作
     * （它本就不参与路由）。</p>
     *
     * @param credentialId 目标凭证 ID
     * @param provider     所属 provider（不匹配即拒绝）
     * @param operator     操作者
     * @return 操作前后启用位快照
     * @throws com.helloai.common.base.BizException 凭证不存在 / 非平台级 API Key / 与 provider
     *                                              不匹配 / 为 EXPIRED（轮换终态）/
     *                                              停用启用位但无备位可顶
     */
    PlatformApiKeyChange deactivatePlatformApiKeyCredential(Long credentialId, String provider,
                                                            String operator);

    /**
     * `M-8` **删除**平台级 API Key（软删 {@code deleted = 1}，保留行以备审计追溯）。
     *
     * <p>对齐 magpie {@code RemoveKey}（{@code keys.go:407-423}）「forgets a key」及其守卫
     * 「One key in use always stays」：删除**启用位**时同样先顶替、无备位可顶则拒绝；
     * 删除**备位 / EXPIRED** 行直接放行（不动启用位，故不会使 provider 失去可用 Key）。</p>
     *
     * @param credentialId 目标凭证 ID
     * @param provider     所属 provider（不匹配即拒绝）
     * @param operator     操作者
     * @return 操作前后启用位快照（{@link PlatformApiKeyChange#credentialId()} 为已删除行）
     */
    PlatformApiKeyChange deletePlatformApiKeyCredential(Long credentialId, String provider,
                                                        String operator);

    /**
     * 人工停用凭证（N-004 收口）。
     *
     * <p>复用 {@link CredentialStatus#DISABLED} 人为停用语义（与 bind 旧凭证一致），
     * 不新增状态。仅 ACTIVE / DISABLED 可停用；EXPIRED（轮换淘汰）不可逆，禁止停用。</p>
     *
     * <p><b>`M-8` 新增作用域限定的守卫</b>：目标为**平台级**（{@code PLATFORM}）且状态为
     * {@code ACTIVE} 时，本方法会先找备位顶替，**无备位可顶则拒绝**（语义与
     * {@link #deactivatePlatformApiKeyCredential} 完全一致 —— 后者在生产路径上已覆盖本入口，
     * 此处是防止旧端点 {@code POST /api/credentials/revoke/{id}} 绕过守卫）。</p>
     *
     * <p><b>为何 Agent 级不受此守卫约束</b>：平台级缺启用位 ⇒ 该 provider 对**全平台**不可用，
     * 故需防呆；Agent 级缺启用位只影响该 Agent 自身的绑定，管理员可随时重绑，
     * 过度拦截会破坏 N-004 已确立的停用语义。</p>
     *
     * @param id       凭证 ID
     * @param operator 操作者（admin）
     * @return 停用后的凭证
     * @throws com.helloai.common.base.BizException 凭证不存在 / 已 EXPIRED / 平台级启用位无备位可顶（fail-close）
     */
    CredentialVault revokeCredential(Long id, String operator);

    /**
     * 过期扫描：将 ACTIVE 且 {@code expire_time < now} 的凭证批量置为 EXPIRED。
     *
     * <p>Secret 生命周期治理——激活 {@code expireTime} 死字段：到期凭证自动退出路由
     * （{@code getActive*} 只查 ACTIVE）。逐行 CAS（WHERE status='ACTIVE'）防并发双写。</p>
     *
     * @param batchLimit 单轮上限（≤0 返回 0 空转）
     * @return 实际失效行数
     */
    int expireOverdue(int batchLimit);

    /**
     * 分页查询凭证操作审计。
     */
    IPage<CredentialAuditLog> listAudits(Long credentialId, CredentialOwnerType ownerType,
                                         Long ownerId, long page, long size);
}
