#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
画廊生成 + 整页截图工具
========================

扫描指定目录里的图片，按「N 列 × M 行」分页填充进毛玻璃画廊模板
（对齐 mydemo.html 的风格），并用系统 Microsoft Edge（Chromium 内核）
做"从头到底部"的整页截图。

特性
----
- 图片以 base64 内嵌进 HTML，不依赖任何路径，截图 100% 可靠
- 自动分页，页脚显示「共X页；第Y页；每页Z张画」
- 支持中文/emoji 文件名
- 支持 jpg/jpeg/png/gif/webp/bmp/tiff

用法
----
    python gallery.py --dir data/images/memes
    python gallery.py --dir data/images/memes --cols 6 --rows 7 --title 画廊
    python gallery.py --dir data/images/memes --width 897 --scale 2 --out gallery_output

参数
----
    --dir      图片目录（必填，默认 data/images/memes）
    --out      截图与 HTML 输出目录（默认 ./gallery_output）
    --cols     每行列数（默认 6）
    --rows     每页行数（默认 7，故每页 42 张）
    --title    画廊标题（默认 画廊）
    --width    视口宽度/页宽像素（默认 897，对齐 demo）
    --scale    设备像素比，2=视网膜清晰，1=严格 897px 宽（默认 2）
    --maxdim   内嵌前把图片最长边缩放到此值以加速渲染（默认 360，0=不缩放）
"""

import argparse
import base64
import mimetypes
import os
import sys

# 系统 Microsoft Edge 的路径（Chromium 内核，免下载浏览器）
EDGE_PATHS = [
    r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    r"C:/Program Files/Microsoft/Edge/Application/msedge.exe",
    r"C:/Program Files/Google/Chrome/Application/chrome.exe",
]

IMG_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tiff", ".tif"}

# 毛玻璃画廊模板，仅替换 __TITLE__ / __ROWS__ / __FOOTER__ 三个占位符
TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>__TITLE__</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  min-height:100vh;
  display:flex; justify-content:center; align-items:center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  padding: 22px 0;
}
.glass-container {
  width:100%; max-width:900px; padding:26px;
  background: rgba(255,255,255,0.15);
  backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px);
  border-radius:20px; border:1px solid rgba(255,255,255,0.3);
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.glass-container h2 {
  color:#191970; text-align:center; margin-bottom:20px;
  font-size:1.4rem; font-weight:500; text-shadow:0 2px 4px rgba(0,0,0,0.35);
}
.glass-container p {
  color:#191970; text-align:center; margin-top:18px;
  font-size:1.13rem; font-weight:500; text-shadow:0 2px 4px rgba(0,0,0,0.1);
}
.emoji-table { width:100%; border-collapse:separate; border-spacing:8px; table-layout:fixed; }
.emoji-table td {
  background: rgba(255,255,255,0.05); padding:8px; border-radius:10px;
  text-align:center; transition: all 0.3s ease; cursor:default;
  height:150px; vertical-align:middle; word-break:break-all;
}
.emoji-table td:hover {
  background: rgba(255,255,255,0.25); transform: scale(1.04);
  box-shadow:0 4px 12px rgba(0,0,0,0.1);
}
.emoji-table td figure { margin:0; padding:0; display:flex; flex-direction:column; align-items:center; height:100%; }
.emoji-table td img { width:100%; height:100%; object-fit:cover; border-radius:10px; display:block; }
.emoji-table td figcaption {
  margin-top:6px; font-size:0.8rem; color:#fff; text-align:center;
  text-shadow:0 1px 2px rgba(0,0,0,0.3);
  max-width:100%; overflow:hidden; white-space:nowrap; text-overflow:ellipsis;
}
</style>
</head>
<body>
<div class="glass-container">
  <h2>__TITLE__</h2>
  <table class="emoji-table"><tbody>__ROWS__</tbody></table>
  <p>__FOOTER__</p>
</div>
</body>
</html>
"""


def find_edge():
    for p in EDGE_PATHS:
        if os.path.isfile(p):
            return p
    # 兜底：交给 Playwright 自己找
    return None


def collect_images(directory):
    files = []
    for name in os.listdir(directory):
        full = os.path.join(directory, name)
        if os.path.isfile(full) and os.path.splitext(name)[1].lower() in IMG_EXT:
            files.append(full)
    files.sort()
    return files


def mime_of(path):
    m, _ = mimetypes.guess_type(path)
    return m or "image/jpeg"


def b64_of(path, maxdim):
    data = open(path, "rb").read()
    if maxdim and maxdim > 0:
        # 可选：用 Pillow 缩放以加速渲染；没有就原样内嵌
        try:
            from io import BytesIO
            from PIL import Image
            img = Image.open(BytesIO(data))
            img.thumbnail((maxdim, maxdim))
            buf = BytesIO()
            ext = os.path.splitext(path)[1].lower()
            fmt = "PNG" if ext in (".png", ".gif", ".webp", ".bmp", ".tiff", ".tif") else "JPEG"
            if fmt == "JPEG":
                img = img.convert("RGB")
            img.save(buf, fmt)
            data = buf.getvalue()
        except Exception:
            pass
    return base64.b64encode(data).decode("ascii")


def build_html(title, page_images, page, total, per_page, cols, maxdim):
    cells = []
    for p in page_images:
        name = os.path.basename(p)
        src = "data:%s;base64,%s" % (mime_of(p), b64_of(p, maxdim))
        # 转义，避免文件名里的 < > & 破坏 HTML
        safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        cells.append(
            '<td><figure><img src="%s" alt="%s">'
            '<figcaption>%s</figcaption></figure></td>' % (src, safe, safe)
        )
    rows = []
    for i in range(0, len(cells), cols):
        rows.append("<tr>" + "".join(cells[i:i + cols]) + "</tr>")
    footer = "共%d页；第%d页；每页%d张画" % (total, page, per_page)
    return TEMPLATE.replace("__TITLE__", title).replace("__ROWS__", "".join(rows)).replace("__FOOTER__", footer)


def screenshot(html, out_png, width, scale):
    from playwright.sync_api import sync_playwright
    edge = find_edge()
    launch_kwargs = dict(headless=True, args=["--no-sandbox", "--disable-gpu", "--force-color-profile=srgb"])
    if edge:
        launch_kwargs["executable_path"] = edge
    with sync_playwright() as p:
        browser = p.chromium.launch(**launch_kwargs)
        page = browser.new_page(viewport={"width": width, "height": 1200}, device_scale_factor=scale)
        page.set_content(html, wait_until="load")
        page.wait_for_timeout(400)
        page.screenshot(path=out_png, full_page=True)
        browser.close()


def main():
    ap = argparse.ArgumentParser(description="画廊生成 + 整页截图工具")
    ap.add_argument("--dir", default="./data/images/memes", help="图片目录")
    ap.add_argument("--out", default="gallery_output", help="输出目录")
    ap.add_argument("--cols", type=int, default=6, help="每行列数")
    ap.add_argument("--rows", type=int, default=7, help="每页行数")
    ap.add_argument("--title", default="画廊", help="画廊标题")
    ap.add_argument("--width", type=int, default=897, help="页宽像素")
    ap.add_argument("--scale", type=int, default=2, help="设备像素比(2=清晰,1=严格页宽)")
    ap.add_argument("--maxdim", type=int, default=360, help="内嵌前缩放最长边(0=不缩放)")
    args = ap.parse_args()

    directory = args.dir
    if not os.path.isdir(directory):
        print("目录不存在: %s" % directory, file=sys.stderr)
        sys.exit(1)

    images = collect_images(directory)
    if not images:
        print("目录里没有图片: %s" % directory, file=sys.stderr)
        sys.exit(1)

    per_page = args.cols * args.rows
    total = (len(images) + per_page - 1) // per_page
    os.makedirs(args.out, exist_ok=True)

    print("目录: %s" % os.path.abspath(directory))
    print("图片总数: %d，每页 %d 张，共 %d 页" % (len(images), per_page, total))
    print("页宽 %dpx，缩放 %dx，输出目录: %s" % (args.width, args.scale, os.path.abspath(args.out)))

    for idx in range(total):
        page_imgs = images[idx * per_page:(idx + 1) * per_page]
        html = build_html(args.title, page_imgs, idx + 1, total, per_page, args.cols, args.maxdim)
        html_path = os.path.join(args.out, "gallery_p%d.html" % (idx + 1))
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html)
        png_path = os.path.join(args.out, "gallery_p%d.png" % (idx + 1))
        screenshot(html, png_path, args.width, args.scale)
        print("  [%-2d/%d] %s  (%d 张)" % (idx + 1, total, os.path.basename(png_path), len(page_imgs)))

    print("完成 ✓")


if __name__ == "__main__":
    main()
