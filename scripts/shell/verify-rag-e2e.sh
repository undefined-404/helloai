#!/usr/bin/env bash
# ============================================================
# verify-rag-e2e.sh —— REF-4 知识库（RAG）端到端 + 前置③ 召回验证
#
# 用途：经管理端 API 验证「真实 dashscope 嵌入 → vector(1024) 落库 → 余弦检索」链路，
#   并用多主题小样本验证检索的判别性召回（recall quality，REF-4.2 前置③）。
# 前置：helloai 已启动（local profile 起 kb.enabled=true）；admin 口径同 verify-*.ps1。
# 用法（项目根）：
#   HELLOAI_ADMIN_PASSWORD=xx bash scripts/shell/verify-rag-e2e.sh
# 产物：每次重跑幂等（ingest 同 source 覆盖）；离场打印 PASS/FAIL 计数。
# ============================================================
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
set -uo pipefail

BASE="${BASE_URL:-http://localhost:6565}"
ADMIN_PW="${HELLOAI_ADMIN_PASSWORD}"
if [ -z "$ADMIN_PW" ]; then
  echo "FAIL: 未设置 HELLOAI_ADMIN_PASSWORD"
  exit 1
fi

TMP=".tmp/rag-e2e-$$"
mkdir -p "$TMP"
PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "[S1] admin login"
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"type\":\"admin\",\"username\":\"admin\",\"credential\":\"$ADMIN_PW\"}")
TOKEN=$(printf '%s' "$LOGIN" | grep -oE '"token":"[^"]+"' | head -1 | sed 's/"token":"//;s/"//')
[ -n "$TOKEN" ] && ok "login 拿到 token" || { bad "login 失败: $LOGIN"; exit 1; }

ingest() {
  local src="$1"; local text="$2"
  printf '{"source":"%s","text":"%s"}' "$src" "$text" > "$TMP/ingest-$src.json"
  curl -s -X POST "$BASE/api/kb/ingest" -H "Content-Type: application/json" \
    -H "X-Admin-Token: $TOKEN" --data @"$TMP/ingest-$src.json"
}

search_top() { # query 文件 → 打回 top1 source
  local qf="$1"
  curl -s -G "$BASE/api/kb/search" -H "X-Admin-Token: $TOKEN" \
    --data-urlencode "query@$qf" | grep -oE '"source":"[^"]+"' | head -1 | sed 's/"source":"//;s/"//'
}

echo "[S2] ingest 三个主题来源（幂等覆盖）"
ingest "manual-rag" "HelloAI 平台使用 PostgreSQL 16 数据库与 pgvector 扩展做向量检索。平台内 LLM 执行者的默认模型是 deepseek-chat。知识库正文会被上传到阿里云百炼 DashScope 做向量化。" >/dev/null
ingest "deploy-manual" "HelloAI 服务器部署使用 Docker Compose：应用镜像 helloai-start 与 postgres、redis、rabbitmq、minio 容器编排。镜像内有 postgresql-client 用于备份，生产用 docker-compose.server.yml 一键拉起。" >/dev/null
ingest "mq-arch" "HelloAI 的执行命令经 RabbitMQ 解耦投递：ExecutionCommand 落 Outbox 后由 MQ Consumer 消费，DB Poller 兜底孤儿，consumer-mode 决定主消费路径。" >/dev/null

echo "[S3] 判别性召回（前置③）"
printf 'HelloAI 的服务端部署方式是什么' > "$TMP/q1.txt"
printf '执行命令怎么通过消息队列解耦' > "$TMP/q2.txt"
printf '状态这些向量存在哪个数据库扩展里' > "$TMP/q3.txt"

d1=$(search_top "$TMP/q1.txt"); [ "$d1" = "deploy-manual" ] && ok "Q部署 → deploy-manual" || bad "Q部署 top1=$d1 (期望 deploy-manual)"
d2=$(search_top "$TMP/q2.txt"); [ "$d2" = "mq-arch" ] && ok "Q消息队列 → mq-arch" || bad "Q消息队列 top1=$d2 (期望 mq-arch)"
d3=$(search_top "$TMP/q3.txt"); [ "$d3" = "manual-rag" ] && ok "Q向量库 → manual-rag" || bad "Q向量库 top1=$d3 (期望 manual-rag)"

rm -rf "$TMP"
echo
echo "RAG_E2E_RESULT PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]