#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线构建 qinlin-open.apk。

不需要 Gradle / Android Gradle Plugin / Maven / 任何第三方依赖，
只用 JDK 17 + Android build-tools + android.jar。
流程：aapt2 compile/link -> javac -> d8 -> 追加 classes.dex -> zipalign -> apksigner。

用法：
    python build-apk.py

可用的环境变量（都有默认值，换机器不用改代码）：
    JAVA_HOME              JDK 根目录（优先用它；没设就找 PATH / 历史路径）
    ANDROID_SDK_ROOT       Android SDK 根目录（等同 ANDROID_HOME）
    ANDROID_BUILD_TOOLS    指定 build-tools 目录；没设则用 29.0.3，再不行取最新
    QINLIN_VERSION_CODE    覆盖 versionCode（默认取 git 提交数）
    QINLIN_VERSION_NAME    覆盖 versionName（默认 1.1-<短 commit>）
    QINLIN_KEYSTORE        指定签名密钥库（默认 build/debug.keystore，首次自动生成）

产物：<项目根>/qinlin-open.apk
"""

import glob
import os
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

ANDROID_JAR = TOOLS / "android.jar"

# 基线版本号：git 不可用时（例如源码压缩包）退回这两个值
FALLBACK_VERSION_CODE = 2
FALLBACK_VERSION_NAME = "1.1"


def _bin(root, name):
    """兼容 Windows（.exe）与 Linux/macOS。"""
    exe = root / "bin" / (name + ".exe")
    if exe.exists():
        return exe
    plain = root / "bin" / name
    if plain.exists():
        return plain
    return exe          # 不存在时返回 .exe 路径，由 main() 统一报错


def resolve_jdk():
    """JAVA_HOME → PATH → 本机历史路径。"""
    home = os.environ.get("JAVA_HOME")
    if home and Path(home).is_dir():
        return Path(home)
    which = shutil.which("javac")
    if which:
        return Path(which).resolve().parent.parent
    legacy = Path(r"E:\Java\jdk-17")
    if legacy.is_dir():
        return legacy
    return None


def resolve_sdk():
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        v = os.environ.get(key)
        if v and Path(v).is_dir():
            return Path(v)
    legacy = Path(r"C:\Program Files (x86)\Android\android-sdk")
    if legacy.is_dir():
        return legacy
    return None


def resolve_build_tools(sdk):
    """显式指定 → 29.0.3（本项目验证过的版本）→ SDK 下最新版本。"""
    env = os.environ.get("ANDROID_BUILD_TOOLS")
    if env:
        return Path(env)
    if sdk is None:
        return None
    pref = sdk / "build-tools" / "29.0.3"
    if pref.is_dir():
        return pref
    parent = sdk / "build-tools"
    if parent.is_dir():
        cands = sorted([d for d in parent.iterdir() if d.is_dir()], key=lambda d: d.name)
        if cands:
            return cands[-1]
    return None


def _tool(d, name):
    """build-tools 里的可执行文件：Windows 带 .exe，其它平台不带。"""
    if d is None:
        return None
    exe = d / (name + ".exe")
    if exe.exists():
        return exe
    plain = d / name
    return plain if plain.exists() else exe


JDK = resolve_jdk()
SDK = resolve_sdk()
BT = resolve_build_tools(SDK)

JAVA = _bin(JDK, "java") if JDK else None
JAVAC = _bin(JDK, "javac") if JDK else None
KEYTOOL = _bin(JDK, "keytool") if JDK else None

AAPT2 = _tool(BT, "aapt2")
ZIPALIGN = _tool(BT, "zipalign")
D8JAR = (BT / "lib" / "d8.jar") if BT else None
APKSIGNERJAR = (BT / "lib" / "apksigner.jar") if BT else None


def _decode(b):
    """javac / d8 在中文 Windows 上按 GBK 输出，aapt2/apksigner 一般是 UTF-8。

    旧代码用 text=True + encoding="utf-8" + errors="replace" 统一解码，
    GBK 的中文错误信息会变成一串乱码 —— 偏偏构建失败时最需要看懂的就是那几行。
    """
    if not b:
        return ""
    for enc in ("utf-8", "gbk", "cp936"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", "replace")


def run(cmd, allow_fail=False):
    shown = " ".join('"{}"'.format(x) if " " in str(x) else str(x) for x in cmd)
    print(">>", shown, flush=True)
    r = subprocess.run([str(x) for x in cmd], capture_output=True)
    out = _decode(r.stdout)
    err = _decode(r.stderr)
    if out.strip():
        print(out[-3000:], flush=True)
    if err.strip():
        print(err[-3000:], flush=True)
    if r.returncode != 0 and not allow_fail:
        raise SystemExit("FAILED rc={}".format(r.returncode))
    return r.returncode


def git(*args):
    """跑一条 git 命令，失败返回空串（源码压缩包里没有 .git）。"""
    try:
        r = subprocess.run(["git"] + list(args), cwd=str(ROOT), capture_output=True)
        if r.returncode == 0:
            return _decode(r.stdout).strip()
    except Exception:
        pass
    return ""


def build_version():
    """
    版本号注入：versionCode 用提交数（单调递增，覆盖安装一定认），
    versionName 用 1.1-<短 commit>。以前两者一直是 1/1.0，
    十几次改动在系统「应用信息」里完全看不出来，也分不清手机上装的是哪一版。
    """
    code_env = os.environ.get("QINLIN_VERSION_CODE", "").strip()
    name_env = os.environ.get("QINLIN_VERSION_NAME", "").strip()
    count = git("rev-list", "--count", "HEAD")
    sha = git("rev-parse", "--short", "HEAD")
    dirty = "dirty" if git("status", "--porcelain") else ""

    try:
        code = int(code_env) if code_env else (int(count) if count else FALLBACK_VERSION_CODE)
    except ValueError:
        code = FALLBACK_VERSION_CODE
    if code < 1:
        code = FALLBACK_VERSION_CODE

    if name_env:
        name = name_env
    elif sha:
        name = "1.1-" + sha + ("-" + dirty if dirty else "")
    else:
        name = FALLBACK_VERSION_NAME
    return code, name


def clean():
    for d in (GEN, CLASSES, DEX):
        if d.exists():
            shutil.rmtree(d)
    for name in ("res.zip", "base.apk", "aligned.apk", "signed.apk"):
        p = BUILD / name
        if p.exists():
            p.unlink()
    # 刻意不删 qinlin-open.apk：旧实现在构建一开始就把上一次的可用包删掉，
    # 中途失败等于「新包没出来、旧包也没了」。产物在签名成功后才原子覆盖。
    for d in (GEN, CLASSES, DEX):
        d.mkdir(parents=True, exist_ok=True)


def main():
    missing = []
    if JDK is None:
        missing.append("JDK（设置 JAVA_HOME，或把 javac 放进 PATH）")
    if ANDROID_JAR is None or not ANDROID_JAR.exists():
        missing.append("tools/android.jar（API 33，见 README「构建」）")
    if BT is None:
        missing.append("Android build-tools（设置 ANDROID_SDK_ROOT / ANDROID_BUILD_TOOLS）")
    for label, p in (("aapt2", AAPT2), ("zipalign", ZIPALIGN),
                     ("d8.jar", D8JAR), ("apksigner.jar", APKSIGNERJAR),
                     ("java", JAVA), ("javac", JAVAC), ("keytool", KEYTOOL)):
        if p is None or not Path(p).exists():
            missing.append("{}: {}".format(label, p))
    if missing:
        print("构建环境不完整：", flush=True)
        for m in missing:
            print("  - " + m, flush=True)
        raise SystemExit(2)

    print(">> JDK      =", JDK, flush=True)
    print(">> SDK      =", SDK, flush=True)
    print(">> build-tools =", BT, flush=True)

    clean()
    version_code, version_name = build_version()
    print(">> versionCode =", version_code, "| versionName =", version_name, flush=True)

    # 1) 编译资源
    run([AAPT2, "compile", "--dir", str(ROOT / "res"), "-o", str(BUILD / "res.zip")])

    # 2) 链接出基础 APK + R.java
    base = [AAPT2, "link", "-o", str(BUILD / "base.apk"),
            "-I", str(ANDROID_JAR),
            "--manifest", str(ROOT / "AndroidManifest.xml"),
            "-R", str(BUILD / "res.zip"),
            "--java", str(GEN),
            "--auto-add-overlay",
            "--min-sdk-version", "26", "--target-sdk-version", "33",
            # 注意：aapt2 默认只在 Manifest「没有」版本号时才注入，
            # 我们的 Manifest 里写了兜底值，所以必须显式 --replace-version 才会覆盖。
            "--replace-version",
            "--version-code", str(version_code),
            "--version-name", version_name]
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

    # 7) 签名密钥（本地生成，仅用于自安装）
    #    ⚠ 这份 keystore 一旦丢失，就再也无法覆盖升级（只能卸载，本机凭证一起丢）。
    #    换正式分发时应通过 QINLIN_KEYSTORE 指定，并离线备份。
    env_ks = os.environ.get("QINLIN_KEYSTORE", "").strip()
    ks = Path(env_ks) if env_ks else (BUILD / "debug.keystore")
    if not ks.exists():
        if env_ks:
            raise SystemExit("QINLIN_KEYSTORE 指向的文件不存在：{}".format(ks))
        run([KEYTOOL, "-genkeypair", "-keystore", str(ks),
             "-storepass", "android", "-keypass", "android",
             "-alias", "androiddebugkey", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000",
             "-storetype", "PKCS12",
             "-dname", "CN=Android Debug,O=Android,C=US"])
        print(">> 已生成调试签名密钥：{}".format(ks), flush=True)
        print(">> ⚠ 请备份该文件：丢失后将无法覆盖升级（只能卸载重装，本机登录凭证会一起丢失）",
              flush=True)

    # 8) 签名 + 校验：先写到 signed.apk，校验通过后再原子替换正式产物。
    #    这样任何一步失败，桌面上那份可用安装包都还在。
    out = ROOT / "qinlin-open.apk"
    signed = BUILD / "signed.apk"
    run([JAVA, "-jar", str(APKSIGNERJAR), "sign",
         "--ks", str(ks), "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--out", str(signed), str(BUILD / "aligned.apk")])
    run([JAVA, "-jar", str(APKSIGNERJAR), "verify", "--verbose", str(signed)])
    os.replace(str(signed), str(out))

    print("\n=== BUILD OK ===")
    print("APK:", out)
    print("versionCode:", version_code, "| versionName:", version_name)
    print("size:", out.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
