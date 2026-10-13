#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""gen-provider-catalog.py —— M-1 生成 providerCatalog.ts 结构骨架（一次性生成辅助，可复用）。

输入：
  1. magpie presets.go（vendor/relay/local 的 端点/keysURL/名称）
  2. models.dev/api.json（每个 provider 的当前可用模型 → 取默认模型）
输出：
  stdout 一份「新增 provider 结构条目」TS 骨架（providerCode/name/protocol/baseUrl/apiKeyUrl/defaultModel
  已从源数据程序化填好；monogram/brandColor/tagline 留待人工补展示文案）。

说明：
  - defaultModel 取 models.dev 该 provider → models 里「可用且支持文本输入输出」的首个对话模型（启发式补默认位，
    若厂商无明确默认即取之；个别需人工核对的品目标记 _needs-confirm）。
  - baseUrl 一律去 /v1 /v1beta 后缀（OpenAI 兼容工厂会自动拼 /v1/chat/completions）。
  - 只取 KindVendor / KindRelay / KindLocal 顶层条目（跳过 region / plan / api 子项）。
用法：
  python scripts/shell/gen-provider-catalog.py <presets.go> <models.dev.json>
"""
import json
import re
import sys


def parse_presets(path):
    """扫描 presets.go 顶层条目 {ID:...}（括号平衡），仅保留 Vendor/Relay/Local。"""
    text = open(path, encoding="utf-8").read()
    entries = []
    starts = [m.start() for m in re.finditer(r"\{ID:", text)]
    for s in starts:
        depth = 0
        i = s
        n = len(text)
        in_str = False
        esc = False
        while i < n:
            c = text[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
            else:
                if c == '"':
                    in_str = True
                elif c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        break
            i += 1
        block = text[s:i + 1]
        km = re.search(r"Kind:\s*Kind(\w+)", block)
        if not km or km.group(1) not in ("Vendor", "Relay", "Local"):
            continue
        eid = re.search(r'\{ID:\s*"([^"]+)"', block)
        name = re.search(r'Name:\s*"([^"]+)"', block)
        keys = re.search(r'KeysURL:\s*"([^"]+)"', block)
        chat = re.search(r'Chat:\s*"([^"]+)"', block)
        ant = re.search(r'Anthropic:\s*"([^"]+)"', block)
        cat = re.search(r'Catalog:\s*"([^"]+)"', block)
        entries.append({
            "id": eid.group(1) if eid else "",
            "kind": km.group(1),
            "name": name.group(1) if name else (eid.group(1) if eid else ""),
            "keys": keys.group(1) if keys else "",
            "chat": chat.group(1) if chat else "",
            "anthropic": ant.group(1) if ant else "",
            "catalog": cat.group(1) if cat else "",
        })
    return entries


def strip_v_suffix(url):
    """去掉结尾 /v1 /v1beta（工厂自动拼路径）。"""
    if url:
        return re.sub(r'/v1(beta)?$', '', url)
    return url


def default_model(models, prov_key):
    """取该 provider 在 models.dev 里的默认文本对话模型。"""
    if not models:
        return None, False
    ent = models.get(prov_key)
    if not ent or not isinstance(ent.get("models"), dict):
        return None, False
    mods = ent["models"]
    for mid, m in mods.items():
        status = m.get("status") or "available"
        if status in ("hidden", "deprecated"):
            continue
        mod = m.get("modalities") or {}
        inp = mod.get("input") or []
        out = mod.get("output") or []
        if "text" in inp and "text" in out and m.get("embedding") is not True:
            return mid, True
    return None, False


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    presets = parse_presets(sys.argv[1])
    models = json.load(open(sys.argv[2], encoding="utf-8"))

    rows = []
    for p in presets:
        # protocol 与 baseUrl：优先 Anthropic-only（无 Chat）→ ANTHROPIC；否则 OPENAI（用 Chat 去 /v1）
        if p["chat"]:
            protocol = "OPENAI_COMPATIBLE"
            base = strip_v_suffix(p["chat"])
        elif p["anthropic"]:
            protocol = "ANTHROPIC_COMPATIBLE"
            base = strip_v_suffix(p["anthropic"])
        else:
            protocol = "OPENAI_COMPATIBLE"
            base = strip_v_suffix(p["anthropic"] or "")
        # 映射 magpie catalog → models.dev key（几种已知别名）
        cat = p["catalog"].split(",")[0].strip()
        alias = {
            "moonshotai": "moonshot", "zhipuai": "zai", "alibaba": "qwen",
            "stepfun-ai-step-plan": "stepfun",
        }.get(cat, cat)
        dm, confirmed = default_model(models, alias)
        default = dm or ""  # 空则留待人工
        rows.append({
            "code": p["id"], "name": p["name"], "kind": p["kind"].lower(),
            "protocol": protocol, "base": base, "keys": p["keys"], "default": default,
            "confirm": "" if confirmed else " needs-confirm",
        })

    # 打印骨架（人工补 monogram/brandColor/tagline）
    for r in rows:
        print(f"// [{r['kind']}]{r['confirm']}")
        print(f"//   defaultModel 来源= models.dev")
        print("  {")
        print(f"    providerCode: '{r['code']}',")
        print(f"    providerName: '{r['name']}',")
        print(f"    monogram: 'XX',")  # 人工
        print(f"    brandColor: '#000000',")  # 人工
        print(f"    tagline: '',")  # 人工
        print(f"    protocolType: '{r['protocol']}',")
        print(f"    baseUrl: '{r['base']}',")
        print(f"    defaultModel: '{r['default']}',")
        print(f"    apiKeyUrl: '{r['keys']}'")
        print("  },")

    print(f"// TOTAL in-scope: {len(rows)} (kindVendor/Relay/Local)  needsConfirm={sum(1 for r in rows if r['confirm'])}")


if __name__ == "__main__":
    main()