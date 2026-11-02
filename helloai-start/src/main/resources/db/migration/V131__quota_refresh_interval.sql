-- ============================================================
-- V131__quota_refresh_interval.sql
-- 用途：LLM 额度监控自动刷新间隔（G-026 UX-1）
--
-- 背景：V129/V130 落地后，前端固定 30s 轮询、后端成功缓存 60s、定时采集 15min，
--   运营侧无法调整节奏。本迁移新增 sys_config 键 quota.refresh.interval-seconds：
--   - 0       仅手动刷新（前端不轮询、打开面板不自动抓、定时采集不抓）
--   - 60~3600 自动刷新间隔（秒，1 分钟 ~ 1 小时）
--   空/非法值由 QuotaWidgetSettings 归一化为默认 60s（保持改造前的有效节奏）。
--   变更方式：系统设置页「额度监控」区，或 PUT /api/admin/config/updateByKey/quota.refresh.interval-seconds
--   （config:edit，仅超管）。
--
-- 幂等：冲突目标必须是 partial unique index（idx_sys_config_key WHERE deleted=0）
-- 的完整推断（列名 + WHERE 谓词）——见 V58 同类说明；已有值不覆盖。
-- 红线：V1~V130 只读，本迁移递增为 V131（Flyway 校验和不可变，CODE_STYLE §16）
-- 回滚：DELETE FROM sys_config WHERE config_key = 'quota.refresh.interval-seconds';
-- ============================================================
INSERT INTO sys_config (id, config_key, config_value, description)
VALUES (1000000000000000006, 'quota.refresh.interval-seconds', '60',
        'LLM 额度自动刷新间隔（秒）：0-仅手动刷新，60~3600-自动刷新')
ON CONFLICT (config_key) WHERE deleted = 0 DO NOTHING;
