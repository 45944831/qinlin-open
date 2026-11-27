#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成桌面小部件选择器用的预览图（android:previewImage）。

为什么需要它：previewLayout 是 API 31 才有的字段，旧启动器和部分第三方桌面只认
previewImage，缺了它选择器里就是一块空白。本机没有图像库（无 PIL / ImageMagick），
于是用系统自带的 Edge 无头模式渲染一段复刻真实布局的 HTML，再截图为透明 PNG。

用法（在项目根目录）：
    python tools/make-widget-previews.py

产物：res/drawable-xxhdpi/widget_preview{,_bar,_mini,_square}.png（3 倍图）。
尺寸取「声明的最小尺寸」，单行版式高度按 64dp 画——40dp 塞三行字会糊成一团，
而桌面实际给的单格高度普遍在 60~80dp。

改了布局或配色后重跑本脚本即可，产物随源码一起提交，打包时不需要它。
"""

import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "res" / "drawable-xxhdpi"
WORK = ROOT / "build" / "preview"

S = 3  # xxhdpi

# 与 res/values/colors.xml 保持一致（预览图是静态位图，不随深色模式切换）
INK = "#16233B"
SUB = "#4F5F78"
MUTED = "#5F6E86"
GREEN = "#137333"
EDGE = "#E2E8F2"
SURFACE = "#FFFFFF"
RED = "#D93025"

EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]

FONT = "'Microsoft YaHei','PingFang SC','Noto Sans CJK SC',sans-serif"


def dp(v):
    return round(v * S)


def card(inner, w_dp, h_dp, pad=""):
    """白底 + 极浅描边 + 16dp 圆角，对应 drawable/widget_bg.xml"""
    return f"""<!doctype html><meta charset="utf-8">
<style>
  html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}
  .card{{box-sizing:border-box;width:{dp(w_dp)}px;height:{dp(h_dp)}px;
        background:{SURFACE};border:{dp(1)}px solid {EDGE};border-radius:{dp(16)}px;
        font-family:{FONT};-webkit-font-smoothing:antialiased;{pad}}}
</style>
<div class="card">{inner}</div>"""


def build_big():
    """4x2：标签行 + 门名行 + 弹性按钮 + 状态行"""
    inner = f"""
  <div style="font-size:{dp(11)}px;line-height:{dp(15)}px;color:{MUTED}">家门</div>
  <div style="display:flex;align-items:center;margin-top:{dp(1)}px">
    <span style="font-size:{dp(11)}px;color:{GREEN};line-height:1">&#9679;</span>
    <span style="flex:1;margin-left:{dp(5)}px;font-size:{dp(17)}px;font-weight:700;
                 color:{INK};line-height:{dp(23)}px">1栋2单元</span>
    <span style="margin-left:{dp(6)}px;font-size:{dp(12)}px;color:{MUTED};
                 line-height:{dp(17)}px">楼栋门</span>
  </div>
  <div style="flex:1;margin-top:{dp(7)}px;border-radius:{dp(12)}px;background:{RED};
              color:#fff;display:flex;align-items:center;justify-content:center;
              font-size:{dp(17)}px;font-weight:700;letter-spacing:.06em">开 门</div>
  <div style="margin-top:{dp(5)}px;font-size:{dp(12)}px;color:{SUB};
              line-height:{dp(17)}px">点按钮直接开门</div>"""
    return card(inner, 250, 110,
                pad=f"padding:{dp(9)}px {dp(12)}px {dp(8)}px {dp(12)}px;"
                    "display:flex;flex-direction:column")


def build_bar():
    """4x1：圆点 + 三行文字 + 右侧胶囊按钮"""
    inner = f"""
  <span style="font-size:{dp(11)}px;color:{GREEN};line-height:1">&#9679;</span>
  <div style="flex:1;margin-left:{dp(6)}px;min-width:0">
    <div style="font-size:{dp(11)}px;line-height:{dp(14)}px;color:{MUTED}">家门</div>
    <div style="font-size:{dp(14)}px;line-height:{dp(18)}px;font-weight:700;color:{INK}">1栋2单元</div>
    <div style="font-size:{dp(11)}px;line-height:{dp(14)}px;color:{MUTED}">点右边开门</div>
  </div>
  <div style="flex:none;margin-left:{dp(6)}px;height:100%;border-radius:{dp(99)}px;
              background:{RED};color:#fff;display:flex;align-items:center;justify-content:center;
              min-width:{dp(72)}px;padding:0 {dp(16)}px;box-sizing:border-box;
              font-size:{dp(14)}px;font-weight:700;letter-spacing:.06em">开 门</div>"""
    return card(inner, 250, 64,
                pad=f"padding:{dp(4)}px {dp(5)}px {dp(4)}px {dp(12)}px;"
                    "display:flex;align-items:center")


def bare(style, label, w_dp, h_dp):
    """根节点本身就是按钮的版式：没有卡片底，形状直接铺满，透明背景"""
    return f"""<!doctype html><meta charset="utf-8">
<style>
  html,body{{margin:0;padding:0;background:transparent;overflow:hidden}}
  .btn{{box-sizing:border-box;width:{dp(w_dp)}px;height:{dp(h_dp)}px;background:{RED};
        color:#fff;display:flex;align-items:center;justify-content:center;
        font-family:{FONT};-webkit-font-smoothing:antialiased;{style}}}
</style>
<div class="btn">{label}</div>"""


def build_mini():
    """2x1：整条就是一个胶囊按钮"""
    return bare(f"border-radius:{dp(99)}px;font-size:{dp(14)}px;font-weight:700;"
                "letter-spacing:.06em", "开 门", 110, 64)


def build_square():
    """1x1：整格一个圆角方块按钮"""
    return bare(f"border-radius:{dp(12)}px;font-size:{dp(14)}px;font-weight:700",
                "开门", 64, 64)


PREVIEWS = [
    ("widget_preview", build_big, 250, 110),
    ("widget_preview_bar", build_bar, 250, 64),
    ("widget_preview_mini", build_mini, 110, 64),
    ("widget_preview_square", build_square, 64, 64),
]


def find_browser():
    env = os.environ.get("EDGE_BIN")
    if env and Path(env).exists():
        return env
    for p in EDGE_CANDIDATES:
        if Path(p).exists():
            return p
    return None


def png_size(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"不是 PNG：{path}")
    w, h = struct.unpack(">II", data[16:24])
    return w, h, data[25]


def main():
    browser = find_browser()
    if not browser:
        raise SystemExit("未找到 Edge / Chrome，可用 EDGE_BIN 环境变量指定路径")
    WORK.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for name, builder, w_dp, h_dp in PREVIEWS:
        html = WORK / f"{name}.html"
        png = WORK / f"{name}.png"
        html.write_text(builder(), encoding="utf-8")
        if png.exists():
            png.unlink()
        subprocess.run([
            browser,
            "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--default-background-color=00000000",
            f"--window-size={dp(w_dp)},{dp(h_dp)}",
            f"--screenshot={png}",
            html.resolve().as_uri(),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        w, h, ctype = png_size(png)
        if (w, h) != (dp(w_dp), dp(h_dp)):
            raise SystemExit(f"{name}: 截图尺寸 {w}x{h} 与预期 {dp(w_dp)}x{dp(h_dp)} 不符")
        if ctype != 6:
            raise SystemExit(f"{name}: 截图没有 alpha 通道（colortype={ctype}）")

        dst = OUT_DIR / f"{name}.png"
        shutil.copyfile(png, dst)
        print(f"{dst.relative_to(ROOT)}  {w}x{h}  {dst.stat().st_size} bytes")


if __name__ == "__main__":
    sys.exit(main())
