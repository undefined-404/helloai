-- ============================================================
-- V106: REF-4.0a 基础设施 —— 启用 pgvector 的 vector 扩展
-- 前提：PG 镜像已切换为 pgvector/pgvector:0.8.0-pg16（docker-compose.yml /
--       docker-compose.server.yml / deploy/middleware/docker-compose.yml / ItContainers.java）。
--       vector 为非 trusted 扩展，须超级用户创建；本库四处 DB 用户均为 postgres（超级用户）。
-- 本迁移只启用扩展，不建向量列/索引（那属 REF-4.2）。
-- 幂等：IF NOT EXISTS，反复执行不报错。
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;