#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线构建 qinlin-open.apk。

不需要 Gradle / Android Gradle Plugin / Maven / 任何第三方依赖，
只用 JDK 17 + Android build-tools 29.0.3 + android.jar。
流程：aapt2 compile/link -> javac -> d8 -> 追加 classes.dex -> zipalign -> apksigner。

用法：python build-apk.py
产物：<项目根>/qinlin-open.apk
"""

import glob
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
TOOLS = ROOT / "tools"
GEN = BUILD / "gen"
CLASSES = BUILD / "classes"
DEX = BUILD / "dex"

SDK = Path(r"C:\Program Files (x86)\Android\android-sdk")
BT = SDK / "build-tools" / "29.0.3"
ANDROID_JAR = TOOLS / "android.jar"

JDK = Path(r"E:\Java\jdk-17")
JAVA = JDK / "bin" / "java.exe"
JAVAC = JDK / "bin" / "javac.exe"
KEYTOOL = JDK / "bin" / "keytool.exe"

AAPT2 = BT / "aapt2.exe"
ZIPALIGN = BT / "zipalign.exe"
D8JAR = BT / "lib" / "d8.jar"
APKSIGNERJAR = BT / "lib" / "apksigner.jar"


def run(cmd, allow_fail=False):
    shown = " ".join('"{}"'.format(x) if " " in str(x) else str(x) for x in cmd)
    print(">>", shown, flush=True)
    r = subprocess.run([str(x) for x in cmd], capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    if r.stdout and r.stdout.strip():
        print(r.stdout[-3000:], flush=True)
    if r.stderr and r.stderr.strip():
        print(r.stderr[-3000:], flush=True)
    if r.returncode != 0 and not allow_fail:
        raise SystemExit("FAILED rc={}".format(r.returncode))
    return r.returncode


def clean():
    for d in (GEN, CLASSES, DEX):
        if d.exists():
            shutil.rmtree(d)
    for name in ("res.zip", "base.apk", "aligned.apk"):
        p = BUILD / name
        if p.exists():
            p.unlink()
    out = ROOT / "qinlin-open.apk"
    if out.exists():
        out.unlink()
    for d in (GEN, CLASSES, DEX):
        d.mkdir(parents=True, exist_ok=True)


def main():
    if not ANDROID_JAR.exists():
        raise SystemExit("缺少 tools/android.jar，请先下载 platform-33 并提取")
    for p in (AAPT2, ZIPALIGN, D8JAR, APKSIGNERJAR, JAVA, JAVAC, KEYTOOL):
        if not p.exists():
            raise SystemExit("缺少工具：{}".format(p))

    clean()

    # 1) 编译资源
    run([AAPT2, "compile", "--dir", str(ROOT / "res"), "-o", str(BUILD / "res.zip")])

    # 2) 链接出基础 APK + R.java
    base = [AAPT2, "link", "-o", str(BUILD / "base.apk"),
            "-I", str(ANDROID_JAR),
            "--manifest", str(ROOT / "AndroidManifest.xml"),
            "-R", str(BUILD / "res.zip"),
            "--java", str(GEN),
            "--auto-add-overlay",
            "--min-sdk-version", "26", "--target-sdk-version", "33"]
    # targetSdk>=30 要求 resources.arsc 不压缩；老版 aapt2 可能不支持 -0，故做回退
    rc = run(base + ["-0", "arsc"], allow_fail=True)
    if rc != 0:
        print(">> [-0 arsc] 不被支持，回退为默认链接", flush=True)
        base_apk = BUILD / "base.apk"
        if base_apk.exists():
            base_apk.unlink()
        run(base)

    # 3) 编译 Java 源码
    srcs = sorted(glob.glob(str(ROOT / "src" / "**" / "*.java"), recursive=True))
    gens = sorted(glob.glob(str(GEN / "**" / "*.java"), recursive=True))
    if not srcs:
        raise SystemExit("未找到 Java 源码")
    print(">> java sources:", len(srcs), "generated:", len(gens), flush=True)
    run([JAVAC, "--release", "8", "-encoding", "UTF-8",
         "-cp", str(ANDROID_JAR), "-d", str(CLASSES)] + srcs + gens)

    # 4) class -> dex
    classes = sorted(glob.glob(str(CLASSES / "**" / "*.class"), recursive=True))
    print(">> classes:", len(classes), flush=True)
    # d8.jar 没有 Main-Class，必须显式指定入口类
    run([JAVA, "-cp", str(D8JAR), "com.android.tools.r8.D8",
         "--lib", str(ANDROID_JAR),
         "--min-api", "26", "--output", str(DEX)] + classes)
    dex_file = DEX / "classes.dex"
    if not dex_file.exists():
        raise SystemExit("d8 未生成 classes.dex")

    # 5) 把 dex 放进 APK 根目录
    with zipfile.ZipFile(str(BUILD / "base.apk"), "a") as z:
        z.write(str(dex_file), "classes.dex", compress_type=zipfile.ZIP_DEFLATED)

    # 6) 对齐
    run([ZIPALIGN, "-f", "4", str(BUILD / "base.apk"), str(BUILD / "aligned.apk")])

    # 7) 调试签名密钥（本地生成，仅用于自安装）
    ks = BUILD / "debug.keystore"
    if not ks.exists():
        run([KEYTOOL, "-genkeypair", "-keystore", str(ks),
             "-storepass", "android", "-keypass", "android",
             "-alias", "androiddebugkey", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000",
             "-storetype", "PKCS12",
             "-dname", "CN=Android Debug,O=Android,C=US"])

    # 8) 签名 + 校验
    out = ROOT / "qinlin-open.apk"
    run([JAVA, "-jar", str(APKSIGNERJAR), "sign",
         "--ks", str(ks), "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--out", str(out), str(BUILD / "aligned.apk")])
    run([JAVA, "-jar", str(APKSIGNERJAR), "verify", "--verbose", str(out)])

    print("\n=== BUILD OK ===")
    print("APK:", out)
    print("size:", out.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
