#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
亲邻云端网关「接口契约探针」——无凭证、只读、不触发开门、不发送短信。

每个请求只带一个无效的 sessionId 和空 JSON body，网关在鉴权层即拒绝，
因此不会执行任何业务动作，也不会产生短信费用或风控副作用。

用法：
    python api-probe.py                    # 探测主路径并与基线对比
    python api-probe.py --all              # 连同备用 / 404 对照路径一起探测
    python api-probe.py --save-baseline    # 把本次结果写成新基线
    python api-probe.py --all --save-baseline

退出码：0 = 与基线一致；2 = 检测到接口或响应码变化；1 = 执行出错。
建议：官方 App 每次更新后跑一次 --all；日常可只跑主路径。
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "https://mobileapi3.qinlinkeji.com"
INVALID_SESSION = "INVALID_PROBE"
ROOT = os.path.dirname(os.path.abspath(__file__))
BASELINE = os.path.join(ROOT, "docs", "api-baseline.json")

# role: primary = 本 APK 正在使用；fallback = 官方 APK 静态提取出的迁移候选；
#       control = 已知不存在的路径，用作「上游是否新增路由」的对照。
PATHS = [
    ("primary",  "/api/app/v1/login",                          "验证码登录"),
    ("primary",  "/api/app/user/v2/communityInfo",             "小区列表"),
    ("primary",  "/api/app/user/v2/queryAllUserDoorByCache",   "门列表"),
    ("primary",  "/api/open/doorcontrol/v2/open",              "开门"),
    ("fallback", "/api/doorcontrol/v2/open",                   "开门备选 A"),
    ("fallback", "/api/open/doorcontrol/openTheDoor",          "开门备选 B"),
    ("fallback", "/api/app/user/v1/communityInfo",             "小区列表 v1"),
    ("fallback", "/api/app/user/v2/queryUserDoorByCacheNew",   "门列表备选 A"),
    ("fallback", "/api/app/user/v2/searchAllUserDoors",        "门列表备选 B"),
    ("fallback", "/api/app/user/v1/bluetoothOpenDoorLog",      "蓝牙开门日志"),
    ("control",  "/api/app/doorcontrol/v2/open",               "404 对照"),
    ("control",  "/api/user/v2/communityInfo",                 "404 对照"),
    ("control",  "/api/app/v1/userCommunity/getCommunity",     "404 对照"),
    ("control",  "/api/app/doorcontrol/queryDoorControlInfo",  "404 对照"),
]

# 短信接口 gateway2.qinlinkeji.com/member/sms/sendSecurityCode 故意不纳入探测：
# 即使参数不完整，也可能触发真实短信或风控，避免任何副作用。


def probe(path):
    url = BASE + path + "?sessionId=" + INVALID_SESSION
    req = urllib.request.Request(
        url, data=b"{}", method="POST",
        headers={
            "Content-Type": "application/json",
            "User-Agent": "qinlin-api-contract-probe/1.0",
            "qplatform": "android",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read(1000).decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read(1000).decode("utf-8", "ignore")
    except Exception as e:
        return "ERR", str(e)[:200]


def classify(http, body):
    code, msg = "", ""
    try:
        j = json.loads(body)
        code = str(j.get("code", j.get("status", "")))
        msg = str(j.get("message", ""))[:60]
        if not msg:
            msg = str(j.get("path", ""))[:60]
    except Exception:
        msg = re.sub(r"\s+", " ", body)[:80]
    if str(http) == "404" or code == "404":
        state = "GONE"
    elif str(http) == "200" and code and code != "404":
        state = "ALIVE"
    else:
        state = "UNKNOWN"
    # 时间戳 / 随机串不进基线，避免每次探测都误报
    msg = re.sub(r"\d{4}-\d{2}-\d{2}[ \d:.]*", "<time>", msg)
    return {"http": str(http), "code": code, "message": msg, "state": state}


def main():
    ap = argparse.ArgumentParser(description="亲邻网关接口契约探测")
    ap.add_argument("--all", action="store_true", help="连同备用与 404 对照路径一起探测")
    ap.add_argument("--save-baseline", action="store_true", help="把本次结果保存为新基线")
    args = ap.parse_args()

    selected = PATHS if args.all else [p for p in PATHS if p[0] == "primary"]
    results = {}
    print("%-52s %-9s %-6s %-7s %-8s %s" %
          ("PATH", "ROLE", "HTTP", "CODE", "STATE", "MESSAGE"))
    print("-" * 130)
    for role, path, note in selected:
        http, body = probe(path)
        info = classify(http, body)
        info["role"] = role
        info["note"] = note
        results[path] = info
        print("%-52s %-9s %-6s %-7s %-8s %s" %
              (path, role, info["http"], info["code"] or "-",
               info["state"], info["message"]))
        time.sleep(0.25)

    if args.save_baseline:
        os.makedirs(os.path.dirname(BASELINE), exist_ok=True)
        with open(BASELINE, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=1, sort_keys=True)
        print("\n[OK] 基线已写入 %s（%d 条）" % (BASELINE, len(results)))
        return 0

    if not os.path.exists(BASELINE):
        print("\n[WARN] 尚无基线文件，请先执行：python api-probe.py --all --save-baseline")
        return 1

    with open(BASELINE, encoding="utf-8") as f:
        baseline = json.load(f)

    changes = []
    for path, cur in results.items():
        old = baseline.get(path)
        if old is None:
            changes.append((path, "新增路径", "-", cur["state"] + "/" + cur["code"]))
            continue
        if old.get("state") != cur["state"] or old.get("code") != cur["code"]:
            changes.append((path, "状态或错误码变化",
                            old["state"] + "/" + old["code"],
                            cur["state"] + "/" + cur["code"]))

    if changes:
        print("\n[WARN] 检测到 %d 项变化 —— 上游接口可能已调整：" % len(changes))
        for path, kind, a, b in changes:
            print("  - %s | %s | %s -> %s" % (path, kind, a, b))
        print("  请对照 docs/API-CONTRACT.md 的备用路径，必要时手动切换并重新验证。")
        return 2

    print("\n[OK] 本次 %d 条探测结果与基线一致，接口未变化。" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
