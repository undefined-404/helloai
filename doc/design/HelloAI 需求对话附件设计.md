# HelloAI 需求对话附件设计

> **状态：Partial**
> 最后更新：2026-10-23
>
> **Scope**：需求对话（planner 对话）中「上传文件 → 解析 → 注入 Prompt → 引用回源」的设计口径，含数据模型、解析分工、两层注入、限额与技术选型。
> **不含**：实施顺序与验收（见 `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md`）；外部产品对标（见 `research/HelloAI 需求对话附件产品对标调研（2026-10-22）.md`）。
> **差距表锚点**：`G-016`（附件内容注入口径）/ `G-017`（对象对账覆盖）/ `G-024`（附件与联网检索编排）；任务号段 `ATT-1`~`ATT-4`（《差距表》§0 编号口径表）。
> **实施状态**：`ATT-1` 已落地；`ATT-2`~`ATT-4` 未实施——进度只以《差距表》§1 与 plan 为准，本文不复述。
> **来源**：原 `plan/HelloAI 需求对话附件设计方案（2026-10-22）.md` §3「方案」与 §6「已定口径」，2026-10-23 按《文档体系分类与治理规则》§1「该怎么建 → `design/`」拆分迁出。**该原文件已于同日拆为三份并更名**：计划部分 → `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md`，调研部分 → `research/HelloAI 需求对话附件产品对标调研（2026-10-22）.md`。
> **节号**：沿用原方案文档编号（§3、§6），便于与 `log/`、ADR 及历史引用对照。

---
## 3. 方案

### 3.1 数据模型

**推荐新增 `chat_attachment`，而不是给 `attachment` 加 `conversation_id`**：

| 理由 | 说明 |
|---|---|
| 语义不混 | `attachment` 的可见性策略、补偿任务、交付物判定都建立在「子任务」这个锚点上；塞进会话维度会让 `AttachmentVisibilityPolicy` 出现第四种情形 |
| 生命周期不同 | 会话附件绑定会话，会话删除即回收；子任务附件绑定交付审计，需长期留存 |
| 迁移风险低 | 新表 + 新端点，不动既有 T1/T2 链路 |

```sql
CREATE TABLE chat_attachment (
    id                bigint PRIMARY KEY,
    conversation_id   bigint,              -- 可空：新会话首句先上传、会话尚未创建
    client_token      varchar(64),         -- 新会话挂载期临时归属（前端生成 UUID），建会话后回填
    file_name         varchar(255) NOT NULL,
    file_type         varchar(64)  NOT NULL,   -- 归一扩展名
    mime_type         varchar(128) NOT NULL,
    file_size         bigint       NOT NULL,
    content_hash      varchar(64),             -- sha256，同会话内去重
    bucket_name       varchar(64)  NOT NULL,
    object_key        varchar(255) NOT NULL,
    storage_url       varchar(512) NOT NULL,        -- 实施补列：读写统一走 storageUrl（与 attachment 表同口径）
    parse_status      varchar(16)  NOT NULL,   -- PENDING / PARSING / READY / FAILED / SKIPPED
    parse_error       varchar(512),
    extracted_text    text,                    -- 文本层/解析结果（≤ 解析上限）
    extracted_chars   integer,
    page_count        integer,
    parser            varchar(32),             -- pdfbox / tika / plain / vision / ocr
    kb_source         varchar(160),            -- 已入 RAG 时的 source（chat:{conv}:{att}）
    create_by         varchar(64) NOT NULL DEFAULT '',
    create_time       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           smallint NOT NULL DEFAULT 0
);
CREATE INDEX idx_chat_att_conv  ON chat_attachment (conversation_id) WHERE deleted = 0;
CREATE INDEX idx_chat_att_token ON chat_attachment (client_token)   WHERE conversation_id IS NULL AND deleted = 0;

CREATE TABLE requirement_message_attachment (
    id            bigint PRIMARY KEY,
    message_id    bigint NOT NULL,
    attachment_id bigint NOT NULL,
    seq           integer NOT NULL DEFAULT 0,
    create_time   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_msg_att ON requirement_message_attachment (message_id, attachment_id);
```

> `client_token` 是刚需：新会话是「先上传文件 → 再发第一句」的顺序，此时还没有 `conversation_id`。建会时按 token 认领并回填。

### 3.2 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat-attachments/upload` | multipart：`files`（1~5 个）+ 可选 `conversationId` / `clientToken`；平台账号通道，权限码复用 `conversation:add` |
| GET | `/api/chat-attachments?conversationId=&clientToken=` | 本会话/本临时批次的文件清单（含 parseStatus，前端轮询解析进度） |
| POST | `/api/chat-attachments/deleteById/{id}` | 删除（软删 + 事务提交后回收对象） |
| GET | `/api/chat-attachments/previewById/{id}` | 内联预览（文本/图片/PDF，≤5MB；Content-Type 由 Tika 魔数探测） |

**路径订正（2026-10-22 实施）**：原稿写 `/api/clarify/attachments/*`，但仓内实际没有 `/api/clarify` 前缀（需求对话是 `/api/requirement-conversations`），故改为平级的 `/api/chat-attachments`，与既有 `/api/attachments` 命名风格一致。按《CODE_STYLE》§50.6「代码事实优先」，以本表为准。

**请求侧变化（ATT-1 实际口径）**：新建会话接口增加可选 `clientToken`（`POST /api/requirement-conversations` 与 `/streamCreate`），建会时按 token 认领临时附件。**ATT-1 不做** `attachmentIds`：附件按「会话级」注入（挂上会话即对后续每轮可见），消息与附件的逐条关联表 `requirement_message_attachment` 已建好但留待 ATT-3 的「引用回源」使用。

### 3.3 解析流水（异步）

```
upload ─► 落对象存储 + 落 chat_attachment(PENDING) ─► 返回 id（不等解析）
                                   │
                                   ├─ 魔数校验：Tika.detect(bytes, 文件名) 与声明 mimeType 对照（不一致即拒）
                                   │   ※ 必须带文件名提示，否则 text/plain 会被判成 octet-stream（实测，见下）
                                   │
                                   ├─ @Async 解析 worker（按 ContentKind 分流，两条路径共用一个 DocumentParser 端口）
                                   │    │
                                   │    ├─ ① 文本族（text/*、md、csv、json、yaml、代码扩展名）
                                   │    │     读原始字节 → 判码 → 整段文本，**不经 Tika 解析**
                                   │    │     判码顺序：BOM → UTF-8 严格 → Tika DefaultEncodingDetector → GB18030 → UTF-8(REPLACE)
                                   │    │     截断 200k 字符（按字符，不切半个代理对）
                                   │    │
                                   │    ├─ ② 结构化格式（pdf / docx / pptx / xlsx / html / rtf…）
                                   │    │     Tika AutoDetectParser + ToMarkdownContentHandler
                                   │    │     pdf → 文本层按页拼 [Page N] 锚点；字符密度过低 → 判扫描件 → 渲染页 → 视觉（3.3.1）
                                   │    │     docx/pptx/xlsx → 标题/段落/表格一次成型（表格输出 |a|b|）
                                   │    │
                                   │    └─ ③ 图片 → 视觉直读（3.3.1）：转写文本落 extracted_text，parser=vision
                                   │
                                   └─ 写 extracted_text / page_count / parser / parse_status
                                   └─ 前端 2s 轮询清单直到终态（或 SSE 推，复用现有流式通道）
```

#### 为什么文本族不交给 Tika（实测，不是偏好）

用户上传的 `md` / 代码 / `txt`，诉求是"原样给模型看"。三条实测结果决定了两条路径必须分开：

| 做法 | 实测结果 | 问题 |
|---|---|---|
| Tika + `ToMarkdownContentHandler` | `# 标题` → `\# 标题`；`\|a\|b\|` → `\|a\|b\|`；`` `code` `` → `` \`code\` ``；`String[]` → `String\[\]`；`{{REQUIRED_SKILLS}}` → `{{REQUIRED\_SKILLS}}` | Markdown handler 把正文当"要转义的文本"，用户的 md/代码被加上一层反斜杠 |
| Tika + `BodyContentHandler`（纯文本） | `# 标题` → `标题`；表格 → tab 分隔；代码文件首行被插进文件名（`Demo.javapublic class Demo {`） | 结构被剥平 + 莫名前缀 |
| 直读字节 + UTF-8 | GBK 编码的 txt 读成 `����һ��˵��…` | 中文场景高频踩，等于丢文件 |
| **直读字节 + Tika `DefaultEncodingDetector` 判码** | `gbk.txt → GB18030(1.0)`、UTF-8 的 md → `UTF-8(0.95)` | **采用**：既不转义也不剥结构，编码还准 |

⇒ **结论：Tika 只负责「结构化格式」（pdf/office/html），文本族走「直读 + 判码」**。判码复用 Tika 的 `DefaultEncodingDetector`（`tika-core` 自带的一个类，不额外引依赖），所以"保留 Java 自读"不等于绕开 Tika——它是**同一个端口下的第二个实现**，由格式决定，不是二选一。

另一个必须记住的坑：**Tika 必须拿到文件名提示**，否则扩展名信息丢失、类型判定退化：

```text
不带 RESOURCE_NAME_KEY → ct=application/octet-stream，解析结果空字符串
带   RESOURCE_NAME_KEY="gbk.txt" → ct=text/plain; charset=GB18030，中文正确
```

**pom 里到底怎么写**（按 §2.5 实测，推荐第一种：**一行搞定**）：

```xml
<!-- 推荐：标准解析包（4.1.0 实测 86 条 classpath，含 pdf / microsoft / html / image / ocr 模块） -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-parsers-standard-package</artifactId>
  <version>4.1.0</version>
  <type>pom</type>            <!-- pom 打包的聚合件，type 必写 -->
</dependency>
<!-- 注意：4.1.0 会自动带入 tika-core（经 tika-encoding-detector-html 传递），不必额外声明；
     3.x 时代不会，这也是上一版方案多写一条 tika-core 的原因 -->

<!-- 备选：只要 PDF + Office（50 条，体积小一半，但没有 html / ocr / image 模块） -->
<!--
<dependency><groupId>org.apache.tika</groupId><artifactId>tika-core</artifactId><version>4.1.0</version></dependency>
<dependency><groupId>org.apache.tika</groupId><artifactId>tika-parser-pdf-module</artifactId><version>4.1.0</version></dependency>
<dependency><groupId>org.apache.tika</groupId><artifactId>tika-parser-microsoft-module</artifactId><version>4.1.0</version></dependency>
-->
```

> 解析代码骨架（两条路径，共用一个 `DocumentParser` 端口）：

```java
// ① 文本族：直读 + 判码（不转义、不剥结构）
byte[] bytes = storage.read(ca.getObjectKey());
Charset cs = TextCharsetResolver.resolve(bytes, ca.getFileName());  // BOM → UTF-8 严格 → Tika → GB18030 → REPLACE
String text = new String(bytes, cs);

// ② 结构化格式：Tika + Markdown，必须带文件名提示与限额
Metadata md = new Metadata();
md.set(TikaCoreProperties.RESOURCE_NAME_KEY, ca.getFileName());   // 少了这行：判成 octet-stream、解析为空
try (TikaInputStream in = TikaInputStream.get(path)) {           // 4.x 入参是 TikaInputStream，不是 InputStream
    ParseContext ctx = new ParseContext();
    ctx.set(TimeoutLimits.class, new TimeoutLimits(60_000, 10_000)); // 总预算 60s / 停顿 10s
    new AutoDetectParser().parse(in, new ToMarkdownContentHandler(out), md, ctx);
}
```

> `TextCharsetResolver` 内部只用一个 Tika 类做判码，不引额外依赖：
> `new DefaultEncodingDetector().detect(TikaInputStream.get(bytes), md, new ParseContext())` → 取 `getConfidence()` 最高者的 `getCharset()`。
> 实测：GBK 的 txt → `GB18030(1.0)`；UTF-8 的 md → `UTF-8(0.95)`；纯 ASCII 代码 → `windows-1252(0.1)`（置信度极低，此时直接用 UTF-8 即可）。
> `tika-parser-pdf-module` 已把 `pdfbox 3.0.8` 作为传递依赖带进来，渲染扫描件页不需要再单独声明；若后续要与 PDFBox 直接交互（`PDFRenderer`），版本以 Tika 带入的为准，避免双版本共存。

判定阈值（可配 `chat.attachment.*`）：

| 项 | 建议值 | 理由 |
|---|---|---|
| 单文件 | 20MB | 覆盖绝大多数 PDF/PPT；multipart 上限同步抬到 30MB |
| 单次上传 | 5 个 | 与「一轮对话挂 5 个文件」的用户心智一致 |
| 单会话累计 | 20 个 / 100MB | 防滥用，超出提示删除旧文件 |
| 直读全文上限 | 200k 字符 | 超过即转 RAG（见 3.4） |
| 解析超时 | 60s | 超时标 FAILED 可重试，不阻塞会话 |
| PDF 扫描件判定 | 每页平均字符 < 50 且总页数 ≥ 3 | 经验阈值，日志留实际值便于回调 |
| 扫描件转图像 | 最多 20 页、每页长边 1568px | 控视觉 token 与费用；超出只处理前 20 页并在清单标注 |

#### 3.3.1 视觉直读（已定口径：图片与扫描件都走这条）

统一成一条链：**图片 / 扫描件页 → 视觉模型 → 文本 → 与普通附件同一条注入路径**。

| 环节 | 做法 |
|---|---|
| 模型选择 | 读 `llm_provider_model.capabilities.input` 里含 `image` 且 `enabled=1` 的模型择一（优先 dashscope qwen-vl 系，其次 anthropic / openai）；**不复用规划者模型**，避免把每轮澄清成本抬到视觉档 |
| 传输通道 | Spring AI `UserMessage` + `Media`（`MediaFormat.IMAGE_PNG/JPEG`，base64 内联）。这是平台**首条多模态通道**，需在 provider 适配层补齐（`spring-ai-openai` 与 `spring-ai-anthropic` 都已支持，不新增依赖） |
| 调用形态 | 单图一次调用，输出分两段：「客观描述」+「图中全部可读文字」；扫描件按页批量（每批 ≤ 5 页）后拼接，保留 `[Page N]` 锚点 |
| 落库 | `extracted_text` 存转写结果，`parser=vision`，`parse_status=READY`；原图仍存对象存储供预览 |
| 降级 | 无可用视觉模型 / 调用失败 / 超费用闸 → `parse_status=SKIPPED`，清单显式写「图片未识别」，Prompt 条款要求不得臆测（不静默当空文件） |
| 费用闸 | 每会话每日视觉调用次数 + 单文件页数双上限，超限走 SKIPPED 并提示 |

> 为什么选视觉而不是 OCR：OCR 对截图（UI 稿、报错弹窗、图表）只拿到文字，丢掉布局与颜色语义，而这类截图正是需求澄清里最常见的图片输入。代价是必须先把多模态通道建起来——它同时是后续「子任务执行带图」的前置能力，属一次性投入。


### 3.4 注入策略（三层，按预算降级）

新增模板变量，CLARIFY / CHAT / FINALIZE 三模板统一（与 N-012 的 Context 分层一致）：

```
{{ATTACHMENT_MANIFEST}}     恒注入：文件清单表（名称/类型/大小/页数/解析状态/是否已入检索）
{{ATTACHMENT_CONTEXT}}      小文件全文层（复用 G-016 限额：单文件 64k、合计 200k 字符，超限截断标注）
{{ATTACHMENT_RETRIEVAL}}    大文件检索层（按当前用户消息 embedding → top-k → 带 chunk 锚点）
```

分层规则：

| 场景 | 处理 |
|---|---|
| 文本总字符 ≤ 200k | 全部进 `{{ATTACHMENT_CONTEXT}}`，顺序按上传顺序，每文件前加 `### 附件 i：文件名（页数/字符）` |
| 有文件 > 64k 或超总计 | 该文件 **ingest 到 kb**（`source = chat:{conversationId}:{attachmentId}`），只把 **检索命中片段** 放进 `{{ATTACHMENT_RETRIEVAL}}`，清单里标注「已入检索」 |
| 图片 / 扫描件（视觉转写成功） | 转写文本按普通文本走同一套预算；清单标注 `来源=视觉识别`，Prompt 条款要求「转写可能有误差，涉及数值/条款需注明出处页码」 |
| 解析失败 / 跳过（无视觉模型、超费用闸、类型不支持） | 清单里显式写 `状态=正文不可读（原因）`，并在 Prompt 条款里要求「不得据此臆测内容」（与 G-010 的「缺形态点名驳回」同源，避免模型瞎猜） |
| 无附件 | 三个变量都渲染成「（无）」，保持模板无残留占位符（延续 `ReviewPromptRenderTest` 的反向完备判据） |

**为什么不全塞**：单轮 200k 字符 ≈ 130k+ tokens，DeepSeek 64K 上下文直接爆；而一次澄清往往只看文件的一两节。检索层解决的是「大窗口也塞不下」和「塞了也不准」两个问题。

### 3.5 多轮与引用

- 每条 user 消息落 `requirement_message_attachment`，气泡内显示文件 chip（图标 + 文件名 + 大小，点击预览）
- 后续轮次默认**继续可见**（同一会话的附件集合进 `{{ATTACHMENT_MANIFEST}}`，正文按需检索），与 Kimi「文件跟会话走」一致
- 「引用对话中的文件」= composer「＋」菜单第二组「文件」，列出本会话已有文件；选中即加进本轮 `attachmentIds`（不重新上传）
- 助手回答需要指向 PDF 具体位置时，用 `[附件i#Page N]` 形式；RAG 命中用 `[kb:source#chunkIdx]`，前端渲染为可点标记（`/api/kb/chunk` 已支持）

### 3.6 前端交互（对齐现有「＋」菜单与技能胶囊）

```
＋
├─ 技能       方案整理 / 直接拆解            （已上线）
└─ 文件       添加文件…                      → 系统文件选择器 / 拖拽
              引用本会话文件…                → 子弹层列表（多选）
```

- 已挂文件 → 复用技能的胶囊样式，排在技能胶囊之后；`解析中` 态显示转圈 + 禁发提示（或允许发送但标注「该文件仍在解析」）
- 输入区支持拖拽上传 + 粘贴截图（`paste` 事件取 `image/*`），这两种是最高频入口，比点两次菜单快
- 超限/类型不支持 → 前端先拦（`accept` + `size` 校验），后端再兜（双校验）
- 解析失败 → 胶囊变红 + tooltip 显示原因 + 「重试解析」

### 3.7 安全与成本

| 项 | 措施 |
|---|---|
| 类型白名单 | 扩展名 + MIME + **魔数校验**（`tika-core` 的 `Tika().detect()` 一行搞定，避免改扩展名绕过） |
| 解压炸弹 | 若支持 zip：限制条目数 / 解压后总量 / 递归深度；一期可不支持压缩包 |
| 病毒扫描 | 阶段三接 ClamAV sidecar；当前至少不做可执行文件（`.exe/.sh/.jar` 直接拒） |
| 私有内容提示 | 上传区常驻一句「文件内容会发给模型服务处理」，与 Kimi 同款告知 |
| 越权 | 会话附件按 `conversationId` + 创建者校验；预览/删除走同一条判据，不再复制策略 |
| 成本 | 同会话内按 `content_hash` 去重（同一文件重复上传不重复解析/嵌入）；解析 token 与检索 top-k 全可配 |

---


## 6. 已定口径（2026-10-22 拍板）

| 项 | 决定 | 落地含义 |
|---|---|---|
| 依赖引入 | **直接 Maven 引入，不编译源码** | `tika-parsers-standard-package:4.1.0` + `<type>pom</type>` 一行（4.1.0 自动带 `tika-core`）；`~/Downloads/tika-main` 是 `4.2.0-SNAPSHOT`，只当 API/文档参考，不 `mvn install` 当依赖 |
| 解析路线 | **纯 Java 为主，Docling 留接口位** | ATT-1 用 Tika 4.1.0 标准解析包（单依赖）；`DocumentParser` 端口留第二实现位，Docling sidecar 不排期 |
| 文本族走哪条 | **保留 Java 直读，Tika 只管结构化格式** | 文本族（txt/md/csv/代码）直读 + `DefaultEncodingDetector` 判码，避免 Markdown handler 转义、纯文本 handler 剥结构；pdf/office/html 走 Tika + `ToMarkdownContentHandler`。两条路径同一端口、由 `ContentKind` 决定 |
| 图片与扫描件 | **视觉模型直读** | 需新建平台首条多模态通道（`Media`）；视觉模型与规划者解耦；失败降级 SKIPPED，不静默当空文件 |
| 限额 | **单文件 20MB / 单次 5 个 / 单会话 20 个、累计 100MB** | multipart 上限抬到 30MB；解析超时 60s（用 `TimeoutLimits`，不自己包 `Future.get`）；扫描件转图 ≤ 20 页 |

> 与 §2.5 的关系：解析路线确定"纯 Java"后，Tika 版本选择也随之确定——**4.1.0 标准解析包单依赖**（86 条 classpath、一套 `AutoDetectParser` 覆盖 20+ 格式、自带魔数检测与限额类），比「三件套」（50 条但缺 html/image/ocr）更省心，比裸 PDFBox+POI（只覆盖两类）覆盖广得多。
> 与 §3.3 的关系：**"用 Tika" 和 "保留直读" 不冲突**——Tika 提供解析器、编码探测（`DefaultEncodingDetector`）、限额（`TimeoutLimits`）、魔数（`Tika.detect`）四样东西，直读只是不用它的 SAX 渲染那一段，其余照用。
