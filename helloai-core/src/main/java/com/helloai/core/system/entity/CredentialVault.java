package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 凭证保险库。
 *
 * <p>T1 先落最小模型，解决 4 个核心表达能力：
 * <ul>
 *   <li>凭证类型：{@link #credentialType}</li>
 *   <li>密文 / 引用：{@link #encryptedValue} / {@link #secretRef}</li>
 *   <li>归属对象：{@link #ownerType} / {@link #ownerId}</li>
 *   <li>启停状态：{@link #status}</li>
 * </ul>
 * </p>
 *
 * <p>当前仅 API_KEY_LLM 场景会落库到此表，CLI_CLIENT 仍沿用 `agent.api_key` 做对外鉴权。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("credential_vault")
public class CredentialVault extends BaseEntity {

    /** 归属对象类型，当前只支持 AGENT。 */
    private CredentialOwnerType ownerType;

    /** 归属对象 ID，例如 agent.id。 */
    private Long ownerId;

    /** Provider 标识，例如 deepseek / openai / claude。 */
    private String provider;

    /** 凭证类型，T1 固定为 API_KEY。 */
    private CredentialType credentialType;

    /** 应用层加密后的凭证值；若使用外部引用，可为空。 */
    private String encryptedValue;

    /** 外部 Secret 引用（如 Vault/环境变量 Key 名），和 encryptedValue 二选一或并存。 */
    private String secretRef;

    /** 当前启停状态。 */
    private CredentialStatus status;

    /** 可选到期时间。 */
    private OffsetDateTime expireTime;
}
