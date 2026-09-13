-- ============================================================
-- BASE-4.5 建号与注册开关：自助注册开关种子
--
-- 背景：平台需支持「管理员建号」（POST /api/admin/users，user:add）与「自助注册」
--       （POST /api/auth/register）。自助注册 = 任何人可建号，安全面显著扩大，
--       故**默认关闭**，由本配置键门控；开启后注册账号固定绑定 GUEST（只读、零写码）。
--
-- 取值：'0' 关闭（默认）/ '1' 开启（大小写不敏感的 'true' 亦视为开启）。
-- 变更方式：管理员经 PUT /api/admin/config/updateByKey/auth.register.enabled（config:edit）。
--
-- 红线：V1~V89 只读，本迁移递增为 V90；仅新增一行配置，无 DDL。
-- 回滚：DELETE FROM sys_config WHERE config_key = 'auth.register.enabled';
-- ============================================================

INSERT INTO sys_config (id, config_key, config_value, description)
VALUES (1000000000000000004, 'auth.register.enabled', '0',
        '是否开放自助注册：0-关闭（默认，仅管理员建号），1-开启（注册账号默认 GUEST 只读）')
ON CONFLICT (id) DO NOTHING;
