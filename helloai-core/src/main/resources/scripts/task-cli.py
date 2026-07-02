#!/usr/bin/env python3
"""
HelloAI task-cli.py — Agent 命令行工具
========================================
用法:
  python task-cli.py --key <API_KEY> poll              # 轮询我的子任务
  python task-cli.py --key <API_KEY> submit <id>       # 提交子任务成果
  python task-cli.py --key <API_KEY> status <id>       # 查看子任务状态
  python task-cli.py --key <API_KEY> skill             # 获取 SKILL.md
  python task-cli.py --key <API_KEY> update            # 更新 CLI + SKILL.md
  python task-cli.py --key <API_KEY> version           # 查看版本信息
"""

CLI_VERSION = 2
BASE_URL = "http://localhost:6565"

import sys
import json
import urllib.request
import urllib.error
import os
import textwrap


# ============================================================
# API 调用
# ============================================================

def _headers(api_key: str) -> dict:
    return {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }


def _get(api_key: str, path: str) -> dict:
    url = f"{BASE_URL}/api{path}"
    req = urllib.request.Request(url, headers=_headers(api_key))
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            if data.get("code") == 200:
                return data.get("data", {})
            print(f"⚠️  API 错误: {data.get('msg', '未知错误')}", file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            msg = json.loads(body).get("msg", str(e))
        except json.JSONDecodeError:
            msg = body or str(e)
        print(f"❌ HTTP {e.code}: {msg}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"❌ 网络错误: {e.reason}", file=sys.stderr)
        sys.exit(1)


def _post(api_key: str, path: str, body: dict = None) -> dict:
    url = f"{BASE_URL}/api{path}"
    data = json.dumps(body or {}).encode() if body else None
    req = urllib.request.Request(url, data=data, headers=_headers(api_key), method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            result = json.loads(resp.read().decode())
            if result.get("code") == 200:
                return result.get("data", {})
            print(f"⚠️  API 错误: {result.get('msg', '未知错误')}", file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            msg = json.loads(body).get("msg", str(e))
        except json.JSONDecodeError:
            msg = body or str(e)
        print(f"❌ HTTP {e.code}: {msg}", file=sys.stderr)
        sys.exit(1)


def _get_raw(api_key: str, path: str) -> str:
    """获取纯文本响应（非 JSON）"""
    url = f"{BASE_URL}/api{path}"
    req = urllib.request.Request(url, headers=_headers(api_key))
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            msg = json.loads(body).get("msg", str(e))
        except json.JSONDecodeError:
            msg = body or str(e)
        print(f"❌ HTTP {e.code}: {msg}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"❌ 网络错误: {e.reason}", file=sys.stderr)
        sys.exit(1)


# ============================================================
# 命令
# ============================================================

def cmd_poll(api_key: str, args: list):
    """查看我的子任务"""
    data = _get(api_key, "/sub-tasks/mine")
    items = data.get("list", data if isinstance(data, list) else [])
    if not items:
        print("📭 没有分配给你的子任务")
        return
    print(f"\n{'='*60}")
    print(f"📋 我的子任务 ({len(items)} 条)")
    print(f"{'='*60}")
    for t in items:
        status_icon = {
            "PENDING": "⏳", "ASSIGNED": "📌", "IN_PROGRESS": "🔄",
            "REVIEW": "👀", "REWORK": "🔁", "BLOCKED": "🚫",
            "DONE": "✅", "CANCELLED": "❌"
        }.get(t.get("status", ""), "❓")
        print(f"  {status_icon} #{t.get('id')} {t.get('title', '无标题')}")
        print(f"     状态: {t.get('status')} | 优先级: {t.get('priority', 'MEDIUM')}")
        print(f"     创建: {t.get('createTime', '')[:19]}")
        print()


def cmd_submit(api_key: str, args: list):
    """提交子任务成果"""
    if not args:
        print("用法: python task-cli.py submit <sub_task_id> [content]", file=sys.stderr)
        sys.exit(1)
    sub_task_id = args[0]
    result = _post(api_key, f"/sub-tasks/{sub_task_id}/submit")
    print(f"✅ 子任务 #{sub_task_id} 已提交审查")


def cmd_status(api_key: str, args: list):
    """查看子任务状态"""
    if not args:
        print("用法: python task-cli.py status <sub_task_id>", file=sys.stderr)
        sys.exit(1)
    sub_task_id = args[0]
    data = _get(api_key, f"/sub-tasks/{sub_task_id}")
    print(f"\n📋 子任务 #{data.get('id')}")
    print(f"  标题: {data.get('title', '无标题')}")
    print(f"  状态: {data.get('status')}")
    print(f"  优先级: {data.get('priority', 'MEDIUM')}")
    print(f"  分配: {data.get('assignedAgent', '未分配')}")
    print(f"  创建: {data.get('createTime', '')[:19]}")
    if data.get("completedAt"):
        print(f"  完成: {data['completedAt'][:19]}")


def cmd_skill(api_key: str, args: list):
    """获取 SKILL.md"""
    data = _get(api_key, "/agents/me/skill")
    content = data.get("content", "")
    print(content)


def cmd_version(api_key: str, args: list):
    """查看版本信息"""
    try:
        check = _get(api_key, "/tools/cli/check-update?currentVersion=" + str(CLI_VERSION))
    except SystemExit:
        check = {}
    print(f"📦 HelloAI Agent CLI v{CLI_VERSION}")
    print(f"  API 地址: {BASE_URL}")
    if check.get("hasUpdate"):
        print(f"  ⬆️  有新版本 v{check.get('latestVersion')} 可用！运行 update 命令更新")
    else:
        print(f"  ✅ 已是最新版本")
    print(f"  更多信息: {BASE_URL}/api/tools")


def cmd_update(api_key: str, args: list):
    """更新 CLI + SKILL.md"""
    import shutil

    # 下载新 CLI
    print("📥 下载最新 task-cli.py...")
    try:
        url = f"{BASE_URL}/api/tools/cli"
        req = urllib.request.Request(url, headers=_headers(api_key))
        with urllib.request.urlopen(req) as resp:
            new_content = resp.read().decode()
            # 备份旧文件
            if os.path.exists("task-cli.py"):
                shutil.copy2("task-cli.py", "task-cli.py.bak")
                print("  已备份旧文件 → task-cli.py.bak")
            with open("task-cli.py", "w", encoding="utf-8") as f:
                f.write(new_content)
            print("✅ task-cli.py 已更新")
    except Exception as e:
        print(f"❌ 更新失败: {e}", file=sys.stderr)
        return

    # 下载 SKILL.md
    print("📥 下载最新 SKILL.md...")
    try:
        data = _get(api_key, "/agents/me/skill")
        content = data.get("content", "")
        if content:
            with open("SKILL.md", "w", encoding="utf-8") as f:
                f.write(content)
            print("✅ SKILL.md 已更新")
        else:
            print("⚠️  SKILL.md 内容为空")
    except Exception as e:
        print(f"❌ SKILL.md 下载失败: {e}", file=sys.stderr)


# ============================================================
# 主入口
# ============================================================

COMMANDS = {
    "poll": cmd_poll,
    "submit": cmd_submit,
    "status": cmd_status,
    "skill": cmd_skill,
    "version": cmd_version,
    "update": cmd_update,
}


def print_usage():
    print(textwrap.dedent(f"""\
    HelloAI Agent CLI v{CLI_VERSION}
    用法:
      python task-cli.py --key <API_KEY> <命令> [参数]

    命令:
      poll                   查看我的子任务列表
      submit <id>            提交子任务成果
      status <id>            查看子任务状态
      skill                  获取角色 SKILL.md
      version                查看版本信息
      update                 更新 CLI + SKILL.md
    """))


def main():
    # 解析 --key 参数
    api_key = None
    cmd_args = []
    skip_next = False
    for i, arg in enumerate(sys.argv[1:]):
        if skip_next:
            skip_next = False
            continue
        if arg == "--key" and i + 1 < len(sys.argv[1:]):
            api_key = sys.argv[i + 2]
            skip_next = True
        elif arg.startswith("--key="):
            api_key = arg[6:]
        else:
            cmd_args.append(arg)

    if not api_key:
        print("错误: 请提供 --key <API_KEY>", file=sys.stderr)
        print_usage()
        sys.exit(1)

    if not cmd_args:
        print_usage()
        sys.exit(1)

    command = cmd_args[0]
    cmd_fn = COMMANDS.get(command)
    if not cmd_fn:
        print(f"未知命令: {command}", file=sys.stderr)
        print_usage()
        sys.exit(1)

    cmd_fn(api_key, cmd_args[1:])


if __name__ == "__main__":
    main()
