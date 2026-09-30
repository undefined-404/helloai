#!/usr/bin/env bash
# ============================================================================
# HelloAI 中间件 - Redis 容器异常一键取证（只读，不改任何东西）
#
# 用法（在中间件服务器上执行）：
#     cd /home/admin/middleware
#     bash scripts/diagnose-redis.sh
#
# 产出：把 Redis 崩溃循环的全部证据一次性打印出来，便于远程判断根因。
# 本脚本不打印任何密码明文，只打印长度与可疑字符。
# ============================================================================
set -uo pipefail

CONTAINER="${CONTAINER:-middleware-redis}"
ENV_FILE="${ENV_FILE:-.env}"

hr() { printf '\n========== %s ==========\n' "$1"; }

hr "0. 容器基本信息"
docker inspect -f '状态={{.State.Status}}  退出码={{.State.ExitCode}}  重启次数={{.RestartCount}}  开始时间={{.State.StartedAt}}' "$CONTAINER" 2>&1

hr "1. 崩溃日志（最关键，先看这段）"
docker logs "$CONTAINER" --tail 60 2>&1

hr "2. 最近一次退出的日志（若 1 为空则看这里）"
docker logs "$CONTAINER" --tail 60 --since 2h 2>&1 | tail -30

hr "3. 容器实际收到的启动命令（看 ACL 是否被拆成了多个参数）"
docker inspect -f '{{range .Config.Cmd}}{{println .}}{{end}}' "$CONTAINER" 2>&1

hr "4. 主机资源"
printf -- '--- 磁盘 ---\n'
df -h /data / 2>&1
printf -- '--- 内存 / swap ---\n'
free -h 2>&1
printf -- '--- 负载 ---\n'
uptime 2>&1

hr "5. 数据目录权限与内容"
ls -ld /data /data/redis 2>&1
printf -- '--- /data/redis 内容（前 20 项）---\n'
ls -la /data/redis 2>&1 | head -20
printf -- '--- appendonlydir（Redis 7 AOF 目录）---\n'
ls -la /data/redis/appendonlydir 2>&1 | head -20

hr "6. .env 关键变量体检（不打印密码明文）"
if [ -f "$ENV_FILE" ]; then
  printf '  行尾 CR 数量 = %s（非 0 = Windows CRLF，会污染变量值）\n' "$(tr -dc '\r' < "$ENV_FILE" | wc -c)"
  printf '  文件开头 3 字节 = %s（efbbbf = UTF-8 BOM，需去掉）\n' "$(head -c 3 "$ENV_FILE" | od -An -tx1 | tr -d ' \n')"
  for k in REDIS_DEFAULT_PASSWORD HELLOAI_REDIS_PASSWORD; do
    line="$(grep -E "^${k}=" "$ENV_FILE" | head -1)"
    if [ -z "$line" ]; then
      printf '  [缺失] %s —— 变量不存在，compose 会替换为空值\n' "$k"
      continue
    fi
    v="${line#*=}"
    v="${v%\"}"; v="${v#\"}"
    flag=""
    case "$v" in *[\ \"\']*) flag="$flag 含空格或引号(会破坏 ACL 语法)";; esac
    case "$v" in *[\ \	]*) flag="$flag 含空白";; esac
    if [ -z "$v" ]; then flag="$flag 【空值！】"; fi
    printf '  [%s] %s  长度=%s%s\n' "$([ -n "$v" ] && echo OK || echo BAD)" "$k" "${#v}" "$flag"
  done
else
  printf '  [BAD] 未找到 %s —— 若 compose 从这里启动，所有变量都会是空值\n' "$ENV_FILE"
  printf '        当前目录：%s\n' "$(pwd)"
fi

hr "7. 运行时 ACL 实测（仅在容器已启动时有效）"
if [ "$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null)" = "running" ]; then
  printf -- '--- ACL LIST（用户与权限；若 default 显示 nopass 说明未设密码）---\n'
  docker exec "$CONTAINER" redis-cli ACL LIST 2>&1 | sed -E 's/(>[^ ]+)/>***/g'
  printf -- '--- 无密码直连测试（返回 PONG 即=无认证可写）---\n'
  docker exec "$CONTAINER" redis-cli ping 2>&1
else
  printf '  容器未运行，跳过。\n'
fi

hr "8. 结论提示"
cat <<'EOF'
  请对照第 1 段日志的关键字定位：

  A) "FATAL CONFIG FILE ERROR" / "Error in user declaration"
     → ACL 参数写法问题（本仓库已修正为逐 token 拆开，重新 up -d 即可）
  B) "Bad file format reading the append only file" / "Wrong signature"
     → /data/redis 里的 AOF 与当前实例不兼容（多为从旧服务器搬过来的数据）
       处置：备份后清空该目录（Redis 只是缓存/会话，不落业务数据）：
         mv /data/redis /data/redis.bak.$(date +%s) && docker compose up -d
  C) "Can't open the append-only file" / "Permission denied" / "No space left"
     → 权限或磁盘问题：看第 4/5 段（df 是否 100%、目录属主）
  D) "wrong number of arguments" / 其它 directive 报错
     → 命令行参数拆分问题，把日志原文发出来
  E) 日志里完全没有 redis 自身报错，只有 entrypoint 相关
     → 检查镜像 entrypoint / setpriv；把第 3 段输出发出来

  第 6 段若出现「含空格或引号」「空值」「CR」「BOM」，属变量注入问题，
  修 .env 后需 `docker compose up -d --force-recreate redis` 才生效。
EOF
