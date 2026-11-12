package com.helloai.core.task.service;

import com.helloai.common.util.RuntimeContractGate;

import java.util.List;

/**
 * 执行期整改的契约门（`G5`）：确定性文本匹配 + 一次 LLM 语义判断。
 *
 * <p><b>两层的处置不同</b>：确定性结论（下游显式引用了被删掉的标识）可 `block`；
 * 语义结论只给 `warn`——遵循 `ADR-008` 决策 17「LLM 结论永无 block 权」，模型误判不能把
 * 正确整改卡死。语义门超时/失败同样降级为 `warn` 并留痕（消歧：不是「没跑」而是「没跑成」）。</p>
 */
public interface RuntimeContractGateService {

    /**
     * @return 合并后的结论；确定性层已阻断时**不调用模型**（省一次调用）
     */
    RuntimeContractGate.Result check(String oldDeliverable, String oldAcceptance,
                                     String newDeliverable, String newAcceptance,
                                     List<RuntimeContractGate.Downstream> downstreams,
                                     List<RuntimeContractGate.Upstream> upstreams,
                                     Long judgeAgentId);
}
