-- ============================================================
-- V130__quota_balance_permission.sql
-- 用途：LLM 余额监控（G-026）管理端权限码
--
-- 背景（G-026，计划 `doc/plan/HelloAI 额度与用量监控实施计划（2026-11-02）.md` Q-7，
--   设计 `doc/design/HelloAI LLM 额度与用量监控设计.md` §4.6）：
--   QuotaController 提供「4 家外部供应商余额聚合（overview）」与「手动刷新单家（refresh）」。
--   余额=金额/额度是**运维敏感数据**（谁还有多少钱、额度快没了没有），
--   参照 V105 backup:view/backup:run「ADMIN 绑、NORMAL_USER 不绑」的范式：
--   - quota:view  余额聚合查看   → 绑 ADMIN(2)，只读监控面
--   - quota:edit  手动刷新单家   → 绑 ADMIN(2)，属变更动作
--   SUPER_ADMIN(1) 经 "*" 通配（StpInterfaceImpl 特判）自动全权限，无需绑定；
--   NORMAL_USER(3) / GUEST(4) 不绑 —— 业务/游客角色不暴露供应商余额金额。
--
-- 边界（Q-8 前端配套）：浮球组件的可见性由前端按「当前用户是否持 quota:view」决定
--   + `sys_config["quota.widget.enabled"]` 平台开关双重闸门（开关关→不渲染也不请求）。
--
-- 幂等：ON CONFLICT DO NOTHING
-- 红线：V1~V129 只读，本迁移递增为 V130（Flyway 校验和不可变，CODE_STYLE §16）
-- 回滚：按 code 反向删 sys_role_permission / sys_permission（无 DDL）
-- ============================================================

-- ------------------------------------------------------------
-- 1) 权限码事实源（id 131 / 132，接续 V107 的 130）
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (131, 'quota:view', '额度-余额查看',   'API', 121, 'system', 'system'),
    (132, 'quota:edit', '额度-手动刷新',   'API', 122, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 角色绑定：quota:view / quota:edit → ADMIN(2)
--    与 backup:view/backup:run 同型：余额监控是管理面能力，ADMIN 持有；
--    NORMAL_USER / GUEST 不绑（不向业务/游客角色暴露供应商余额）。
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('quota:view', 'quota:edit')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3) 前端浮球开关（Q-8）默认开
--    sys_config["quota.widget.enabled"]：'1'=渲染浮球并允许轮询；'0'=整组件关闭（不渲染也不发请求）。
--    变更方式：PUT /api/admin/config/updateByKey/quota.widget.enabled（config:edit，仅超管）。
-- ------------------------------------------------------------
INSERT INTO sys_config (id, config_key, config_value, description)
VALUES (1000000000000000005, 'quota.widget.enabled', '1',
        'LLM 额度浮球开关：1-开启（右下角只读余额浮球），0-关闭（不渲染也不发请求）')
ON CONFLICT (id) DO NOTHING;