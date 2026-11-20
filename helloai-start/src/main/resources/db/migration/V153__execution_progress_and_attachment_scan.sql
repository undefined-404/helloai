-- ============================================================================
-- V153：执行记录「无进展」兜底列（MA-10 改动 3）+ 附件正文敏感命中摘要（MA-8 挂点④）
--
-- 说明：本文件承载**同一开发批次**的两处 schema 变更（MA-10 改动 3 与 MA-8 挂点④）。
--   按 MEMORY 铁律「迁移号按变更批次分配，并入当前未发布 V 号，禁『改字段就开新号』」合并。
-- ============================================================================

-- ============================================================================
-- 一、MA-10 改动 3：无进展判定
--
-- 治什么：ExecutionCompensationTask 原按 start_time + runningTimeoutMinutes（固定 10 分钟
--   总时长）判 RUNNING 超时。固定总时长把「慢但有效」与「卡死」判成同一件事：
--   20 轮 × 慢工具 5 分钟超时 = 100 分钟，与 10 分钟毫无关系。
--   改为按「有无进展」判定：新增 last_progress_at，每轮末 / 工具调用边界更新。
--
-- 回滚：DROP COLUMN last_progress_at（本列只被 ExecutionCompensationTask 与
--   ExecutionProgressRecorder 使用；回滚后扫描条件须同步改回 start_time）。
--
-- 存量行为：last_progress_at 为 NULL 的历史行，读侧回落 start_time ⇒ 与改造前等价
--   （不需要回填，也不应该回填：历史行没有「进展」这个事实可写）。
-- ============================================================================

ALTER TABLE agent_execution_record ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMPTZ;

COMMENT ON COLUMN agent_execution_record.last_progress_at IS
    'MA-10：最近一次有进展的时间（每轮末 / 工具调用边界更新）；NULL 表示未记录，读侧回落 start_time';

-- ============================================================================
-- V153 同批次追加：附件正文敏感命中摘要（MA-8 挂点④）
--
-- 治什么：附件正文（chat_attachment.extracted_text）是**第四条数据通路**，
--   原先完全不被三个挂点覆盖——用户上传一份含密钥的配置文件，平台既不知道也不提示。
--   挂点④ 在解析完成、写库之前扫一次；命中摘要落到本表，供前端胶囊直接提示用户。
--
-- 为什么不在 chat_attachment 上存「命中明细」：明细含偏移与指纹，属审计面，
--   已由 task_timeline 事件承载；这里只存**给用户看的聚合**（几处 / 哪几类 / 是否截断）。
--
-- 回滚：DROP COLUMN sensitive_scan_hit_count / sensitive_scan_rules（无其他读取方）。
--
-- 存量行为：两列为 NULL ⇒ 前端按「未扫描」处理（不显示提示），与改造前视觉一致。
-- ============================================================================

ALTER TABLE chat_attachment ADD COLUMN IF NOT EXISTS sensitive_scan_hit_count INT;

ALTER TABLE chat_attachment ADD COLUMN IF NOT EXISTS sensitive_scan_rules TEXT;

COMMENT ON COLUMN chat_attachment.sensitive_scan_hit_count IS
    'MA-8：附件正文疑似敏感内容命中条数；NULL = 未扫描（如解析失败/SKIPPED），0 = 扫过且未命中';

COMMENT ON COLUMN chat_attachment.sensitive_scan_rules IS
    'MA-8：命中规则的稳定 code（逗号分隔，如 "config_secret,api_token"）；不含原文'
