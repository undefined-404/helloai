# ADR-002：RAG 嵌入模型选型与密钥管理定稿

| 项目       | 内容                                                                                             |
| -------- | ---------------------------------------------------------------------------------------------- |
| **编号**   | ADR-002                                                                                        |
| **状态**   | Accepted（2026-10-11 用户裁定：选型 + 维度 + 密钥归属 + 数据边界）                                                  |
| **日期**   | 2026-10-11                                                                                     |
| **关联文档** | [`plan/HelloAI 借鉴落地实施计划.md`](../../plan/HelloAI%20借鉴落地实施计划.md) §6（`REF-4.0a` / `4.0b` / `4.2`）、[`HelloAI 目标架构.md`](../../HelloAI%20目标架构.md) §14、[`HelloAI 实现差距表.md`](../../HelloAI%20实现差距表.md) `G-019` / `D-2026-10-11-1`、`LOG-20261011-001` |
| **决策类型** | 选型 + 落地前置契约（**先于** `REF-4.0a` / `4.2` 落地）                                                    |

> **本 ADR 只定选型与前置契约，不建表、不改代码、不动部署。** 表结构与索引落地见 `REF-4.2`；
> PG 镜像与扩展见 `REF-4.0a`；两者动手前按《协作规约》§13 各自出实现计划。

---

## 1. 背景与问题

`REF-4`（RAG 知识库）当前**完全空白**（差距表 `G-019`）。向量检索链路必然包含 embeddings：
`检索 = 把 query 也嵌入到同一空间 → 与库内向量比相似度`，故嵌入模型是**必需件**且其输出**维度**决定存储形态。

三个必须先定的问题：

1. **选哪家嵌入模型**（平台已具备凭据的供应商里，是否有人提供 embeddings？）
2. **维度多少**（决定 `pgvector` 列宽与索引，且**换维度要全量重算**）
3. **用谁的凭据 / 数据能否出平台**（知识库正文要被发送到外部服务做向量化）

---

## 2. 决策一：嵌入模型 = 阿里云百炼 DashScope `text-embedding-v4`

**决策**：采用 `text-embedding-v4`，**输出维度 1024**（v4 的默认维度）。

**为什么是它**（在"四个平台已有凭据的供应商"里做的排除，均为实测/官方文档口径）：

| 候选 | 结论 | 依据 |
| ---- | ---- | ---- |
| **DashScope（阿里云百炼）** | ✅ **采用** | **凭据已在 `credential_vault`**（现网 6 条）、**OpenAI 兼容** `/compatible-mode/v1/embeddings`（与仓库既有「用 `spring-ai-openai` 打通 dashscope chat」**同源**，不新增依赖形态）、v4 维度可选 2048/1536/**1024(默认)**/768/512/256/128/64、中文强、约 $0.07/1M tokens、新开通 100 万 tokens/90 天试用 |
| DeepSeek（平台主供应商） | ❌ 排除 | **官方不提供 embeddings 端点**（DeepSeek-V3 仓库 issue #806：无 embedding head/endpoint） |
| Moonshot / MiniMax | ❌ 排除 | 多源显示**不支持 embeddings**（一处列出端点、两处标 ❌，结论存疑；不作候选） |
| OpenAI `text-embedding-3-*` | ❌ 排除 | 平台 **无境外凭据**；且数据出境至境外主体（合规面更差） |
| 自托管（如 bge-m3） | 🟡 备选（未采用） | 优点：零 API 成本、**数据不出平台**；代价：新增中间件与推理算力（与"部署用 Docker 统一、单后端"的取向相悖）。**触发条件见 §7** |

> 若将来需要换（§7 的触发条件成立），**换供应商 ≠ 改架构**：`EmbeddingModel` 抽象已在类路径
> （`spring-ai-model` 1.1.8 实测含 `EmbeddingModel` / `AbstractEmbeddingModel` / `DocumentEmbeddingModel`；
> `spring-ai-commons` 含 `Document` + `TokenCountBatchingStrategy`），差异收敛在"base-url + 模型名 + 维度"三点。

---

## 3. 决策二：维度 1024 —— **单向门**（硬约束）

```text
存储形态：pgvector 列 vector(1024)  ── 列宽与 HNSW/IVFFlat 索引【绑定维度】
换模型 或 换维度 ⇒ 库内既有向量【全部失效】⇒ 必须全量重算（逐 chunk 重新 embed + 重建索引）
```

**约束**（对 `REF-4.2` 生效）：

1. 维度值**写入配置**（不硬编码），但**一经上线即冻结**；同模型内换维度（v4 还支持 2048/1536/768/…）
   **同样要重算**，故与"换模型"同视为单向门。
2. 变更路径**不走原地 ALTER**：须「新建列/表 → 双写或离线回填 → 校验 → 切换 → 清理旧列」；
   该迁移方案本 ADR **不设计**，触发时另开（避免把未验证的 DDL 计划固化）。
3. 之所以选 1024 而非更大：v4 的**默认值**、HNSW 索引内存/构建成本显著低于 1536/2048，
   中文场景召回够用；且"要更大"仍留在同模型内（不必换供应商）——**保留余地而不预支成本**。

---

## 4. 决策三：密钥**复用** `credential_vault` 既有 dashscope 条目，不新增独立 key

**决策**（用户裁定 2026-10-11）：与内部 LLM Agent **共用**同一份 dashscope 凭据，**不新增**独立条目。

**用户理由**：扣费来自同一账户、资产同属一人，**key 分离不产生任何财务隔离** —— 分离只有形式意义。

**本决策的工程影响（均为"零改动"）**：

- 平台侧**无需新增机制**：`credential_vault` 本就是「provider × scope」**多条目**表；运行时经**平台级**凭据解析取用
  （`AgentLlmCredentialResolver` 是「Agent 级 → 平台级」兜底，RAG 属平台能力、**只走平台级**，不绑 Agent）。
- **成本归因不依赖 key 分离**：改为在**平台侧记录 token 用量**（compatible-mode 响应体含 `usage`）
  ⇒ 共用也不丢可观测性。
- **可逆**：若将来批量建库与 agent 流量互相挤配额/限流，**加一条独立 key 的成本≈0**（多条目 + provider 级解析）
  ⇒ 本决策**不是单向门**（与 §3 的维度形成对比，这是刻意的）。

---

## 5. 决策四：数据边界 —— 知识库正文会发送至阿里云（用户已接受）

- 事实：向量化意味着**正文离开平台**，发送至阿里云百炼（**境内主体**）。
- 用户 2026-10-11 **明确接受**该边界（含可能含内部文档 / 代码 / 任务产出）。
- **不含项**：不引入境外供应商（OpenAI / Cohere / Voyage / Jina 等）—— 平台无凭据且涉境外出境。
- **触发条件**（见 §7）：若出现"**不可外发**"的知识域，须支持**按知识域选择 provider**
  （届时评估自托管 bge-m3 或本地推理）；本 ADR 不设计该机制。

---

## 6. 决策五：落地前置检查与实现约束（对 `REF-4.0a` / `4.2` 生效）

**前置检查（能力可用性判据，与备份批次的 `pg_dump --version` 同型）**：

```text
POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
  Authorization: Bearer <既有 dashscope key>
  {"model":"text-embedding-v4","input":["探针"],"dimensions":1024,"encoding_format":"float"}
期望：data[0].embedding 长度为 1024
失败：显式报「RAG 向量化不可用」并给出可读原因（不静默降级、不产出半库向量）
```

⚠️ **待验证**：现有 key 所在 workspace 是否已开通 `text-embedding-v4`（现有套餐的适用范围以该探针为准）。

**实现约束**：

| 约束 | 值 | 来源 |
| ---- | ---- | ---- |
| 单请求行数上限 | **≤ 10 行** | 官方（compatible-mode） |
| 单行 token 上限 | ≤ 8192 tokens | 官方 |
| 稀疏向量 | **不支持**（`output_type=sparse` 在兼容模式会 200 但向量为空）⇒ 不依赖 sparse | 官方 |
| 查询侧成本 | **每次检索 1 次 embeddings 调用**（query 也要嵌同一空间） | 链路固有 |
| 降级策略 | embeddings 不可用时的检索降级（候选：PG 全文/关键词兜底 + **显式标注降级**）在 `REF-4.2` 定 | 待定 |
| 客户端 | 复用**既有** `spring-ai-openai` OpenAI 兼容路径；`VectorStore` 后端实现（pgvector）如需引用，artifact 名在 `REF-4.2` 前核实（**不猜**） | 本 ADR |

---

## 7. 决策六：向量存储沿用 **pgvector**，不引入 Elasticsearch

三条理由（第 2 条是本轮 REF-2 交付后的**新论据**）：

1. **单后端**：不新增中间件（符合"部署用 Docker 统一 / 不新增平行架构"）。
2. **已在既有备份覆盖范围内**：`REF-2.3/2.3b/2.4` 已交付 PG 全库备份 + 恢复三门 + 停机流程；
   向量与业务同库 ⇒ **自动进备份**。引入 ES 等于新增一个**不在备份范围内**的状态源（须另做备份/恢复/演练）。
3. **事务一致性**：chunk 元数据（来源 / 版本 / 可见性）与向量同库同事务 ⇒ 无"向量在、记录不在"的双写漂移。

**规模判断**：平台知识库量级（文档 / 技能包 / 任务产出）远低于 ES 的亿级优势区；pgvector 的 HNSW 在百万级以内表现良好。
**不锁死**：`VectorStore` 抽象可换后端；若规模进入亿级，按 §8 触发条件重新评估。

---

## 8. 触发重新评估的条件

```text
① 出现「不可外发」的知识域           ⇒ 评估按域选 provider / 自托管 bge-m3
② 批量建库与 agent 流量互相挤配额      ⇒ 拆一条独立 RAG key（成本≈0，见 §4）
③ 向量规模进入亿级                    ⇒ 评估专用向量库（Milvus/Qdrant 等）
④ text-embedding-v4 下线 / 大幅涨价   ⇒ 同形态换供应商（base-url + 模型名 + 维度三点）
⑤ 需要 BM25 + 向量的混合检索且 PG 方案不满足 ⇒ 评估 ES/OpenSearch（须连备份链一并设计）
```

---

## 9. 后果

- **正面**：RAG 的选型面收口（供应商 / 维度 / 密钥 / 数据边界四问全定），`REF-4.0a` 与 `4.2` 可直接开工；
  且"换供应商"与"换 key"都保留了低成本退路，"换维度"则被明确标成单向门。
- **代价**：① 检索路径引入**外部依赖延迟**（第三方实测单句 0.29–0.61s，叠加在每次检索上）；
  ② 知识库正文出平台（用户已接受）；③ 维度冻结意味着试错成本集中在**建库前**（故 `REF-4.2` 必须先小样本验证召回质量再全量灌库）。

---

## 10. 外部依据（可核）

- 阿里云百炼：OpenAI 兼容 Embedding 接口与维度可选 —— <https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai>
- `text-embedding-v4` 维度与定价（第三方页） —— <https://empiriolabs.ai/pt-BR/models/text-embedding-v4>
- 国内向量模型实测对比（延迟 / 维度 / 价格） —— <https://jishuzhan.net/article/2088941366448373762>
- DeepSeek 无 embeddings 端点（官方仓库 issue） —— <https://github.com/deepseek-ai/DeepSeek-V3/issues/806>
- Embedding 模型横比（BGE / E5 / Jina / OpenAI / Cohere） —— <https://wangyong9999.github.io/lakehouse-wiki/compare/embedding-models/>
