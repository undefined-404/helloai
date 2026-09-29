#!/usr/bin/env bash
# ============================================================
# MinIO 隔离初始化（minio-init 容器内执行，幂等）
# 通过 mc 创建：
#   - bucket  helloai-artifacts
#   - user    helloai-s3（仅 helloai-artifacts bucket 权限）
# 其他项目模板见底部注释段（policies/other-project.json）
# ============================================================
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
set -euo pipefail

# 等待 minio 就绪并建立 alias
until mc alias set local "${MINIO_ENDPOINT:-http://minio:9000}" \
  "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1; do
  echo "[minio-init] waiting for minio..."
  sleep 2
done

# ---------- HelloAI ----------
echo "[minio-init] creating bucket helloai-artifacts"
mc mb --ignore-existing local/helloai-artifacts

# bucket 专属 policy（Resource 限定本 bucket）
mc admin policy create local helloai-artifacts-only \
  /policies/helloai-artifacts.json 2>/dev/null || true

# 项目专属 S3 用户（已存在则跳过）
mc admin user add local "${HELLOAI_S3_ACCESS_KEY}" "${HELLOAI_S3_SECRET_KEY}" 2>/dev/null || true

# 绑定最小权限（幂等）
mc admin policy attach local helloai-artifacts-only --user "${HELLOAI_S3_ACCESS_KEY}"

# ---------- 其他项目（按需取消注释并补全 .env 变量）----------
# mc mb --ignore-existing local/other-project
# mc admin policy create local other-project-only /policies/other-project.json 2>/dev/null || true
# mc admin user add local other-s3 "${OTHER_S3_SECRET_KEY}" 2>/dev/null || true
# mc admin policy attach local other-project-only --user other-s3

echo "[minio-init] DONE"
