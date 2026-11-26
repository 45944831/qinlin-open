#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
core 包的零依赖自检。

只编译 src/com/wb/qinlin/core/*.java + tests/*.java，不碰 android.jar，
因此任何一台装了 JDK 的机器（含 CI）都能直接跑：

    python run-tests.py

退出码 0 = 全部通过，1 = 有用例失败或编译失败。
"""

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "build" / "test-classes"

# JDK 解析顺序：JAVA_HOME → PATH → 本机历史路径（保持与 build-apk.py 一致）
def find_jdk():
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "javac.exe").exists():
        return Path(home)
    if home and (Path(home) / "bin" / "javac").exists():
        return Path(home)
    legacy = Path(r"E:\Java\jdk-17")
    if (legacy / "bin" / "javac.exe").exists():
        return legacy
    return None


def main():
    jdk = find_jdk()
    if jdk is None:
        print("找不到 JDK：请设置 JAVA_HOME，或把 javac 放进 PATH")
        return 1
    javac = jdk / "bin" / "javac.exe"
    java = jdk / "bin" / "java.exe"
    if not javac.exists():
        javac = jdk / "bin" / "javac"
        java = jdk / "bin" / "java"

    core = sorted(str(p) for p in (ROOT / "src" / "com" / "wb" / "qinlin" / "core").glob("*.java"))
    tests = sorted(str(p) for p in (ROOT / "tests").glob("*.java"))
    if not core or not tests:
        print("未找到 core 源码或测试源码")
        return 1

    OUT.mkdir(parents=True, exist_ok=True)
    cmd = [str(javac), "-encoding", "UTF-8", "-d", str(OUT)] + core + tests
    print(">>", " ".join(cmd), flush=True)
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stdout.decode("utf-8", "replace"))
        sys.stderr.write(r.stderr.decode("utf-8", "replace"))
        return 1

    r = subprocess.run([str(java), "-cp", str(OUT), "CoreSelfTest"])
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
