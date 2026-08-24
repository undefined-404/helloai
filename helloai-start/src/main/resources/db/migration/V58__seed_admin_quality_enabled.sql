-- ============================================================
-- V58: 质量看板/实测端点默认开放 —— 初始化 sys_config 键 admin.quality.enabled
-- ------------------------------------------------------------
-- 背景（迭代记录 §6.151）：质量看板默认关闭（缺省无配置行），但前端菜单始终可见，
-- 首次点击返回业务码 403 被前端当作认证失败触发登出，用户误以为系统 Bug。
-- 修复：门控语义反转为"默认开放，仅显式 false 关闭"，本迁移显式落库默认值 true，
-- 保证新老部署开箱即用；仍可在系统设置页「质量门控」关闭（显式 false 生效）。
--
-- 幂等：冲突目标必须是 partial unique index（idx_sys_config_key WHERE deleted=0）
-- 的完整推断（列名 + WHERE 谓词）——裸列名无法匹配 partial index，会报 42P10；
-- 已存在（含用户手动创建/历史遗留）时不覆盖既有值，软删行（deleted=1）不拦截重建。
-- ============================================================
INSERT INTO sys_config (id, config_key, config_value, description)
VALUES (1000000000000000004, 'admin.quality.enabled', 'true', '管理侧质量看板/实测端点开关：默认开放，置 false 关闭（§6.151）')
ON CONFLICT (config_key) WHERE deleted = 0 DO NOTHING;
