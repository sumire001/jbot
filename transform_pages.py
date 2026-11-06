#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把各内容页重构成软导航：头部注入 app.js + 给页面专属 <style> 加 id；
把页面脚本包成 IIFE、移入 .main-content、暴露内联 onclick 函数、加 destroy 清理定时器。"""
import re, os

BASE = r"D:\Code\QQBot\my-j-one\J-One\src\main\resources\templates"

EXPOSE = {
    'command': ['addCommandRow', 'updateCommandConfig'],
    'ai-models': ['downloadModel', 'loadModels', 'toggleApiKeyVisibility', 'updateAi'],
    'search-source': ['updateBaikeConfig', 'updateSearchEngine'],
    'memes': ['createCategory', 'deleteMeme', 'hideCreateCategory', 'loadMemes', 'selectCategory', 'showCreateCategory'],
    'batch-upload': ['uploadFiles'],
    'general-settings': ['deleteUserMemory', 'downloadModel', 'getUserMemory', 'hideUserMemoryDetails',
                          'loadUserMemories', 'resetSystemPrompt', 'saveUserMemory', 'setGeneralTheme',
                          'toggleGalleryApiKeyVisibility', 'updateAdmin', 'updateCache', 'updateCompression',
                          'updateDownload', 'updateGallery', 'updateProxy', 'updateSystemPrompt'],
}

APPJS_LINE = '    <script src="/js/lucide.min.js?v=20261031g" defer></script>'
APPJS_SNIPPET = '    <script src="/js/app.js?v=20261031h"></script>'

# 匹配裸 <script> ... </script>（theme.js / app.js 都是带 src 的 <script src=...>，不会命中）
SCRIPT_RE = re.compile(r'<script>(.*?)</script>', re.DOTALL)

def transform(path, expose):
    with open(path, 'r', encoding='utf-8') as f:
        html = f.read()

    # 1) 头部：注入 app.js（若未存在）；其后的页面专属 <style> 加 id（无则跳过）
    if APPJS_SNIPPET not in html:
        if APPJS_LINE not in html:
            raise RuntimeError('头部 lucide 脚本模式未命中: ' + path)
        html = html.replace(APPJS_LINE, APPJS_LINE + '\n' + APPJS_SNIPPET, 1)
    if '<style id="page-style">' not in html:
        html = html.replace(APPJS_SNIPPET + '\n    <style>', APPJS_SNIPPET + '\n    <style id="page-style">', 1)

    # 2) 抽取页面裸脚本（取最后一个匹配，即页面专属脚本）
    matches = list(SCRIPT_RE.finditer(html))
    if not matches:
        raise RuntimeError('未找到页面裸脚本: ' + path)
    m = matches[-1]
    body = m.group(1)
    html = html[:m.start()] + html[m.end():]  # 删除原脚本

    # 3) 包成 IIFE，移入 .main-content，暴露内联函数，加 destroy
    expose_lines = '\n'.join('        if (typeof %s !== \'undefined\') window.%s = %s;' % (n, n, n) for n in expose)
    wrapped = (
        '    <script>\n'
        '    (function () {\n'
        '        var _timers = [];\n'
        '        var _origSetInterval = window.setInterval;\n'
        '        function setInterval(fn, ms) { var id = _origSetInterval(fn, ms); _timers.push(id); return id; }\n'
        + body +
        '\n' + expose_lines + '\n'
        '        window.JOnePage = { destroy: function () { _timers.forEach(function (id) { clearInterval(id); }); _timers = []; } };\n'
        '    })();\n'
        '    </script>\n'
    )

    # 4) 插入到 </main> 之前（作为 .main-content 的最后一个子节点）
    idx = html.rfind('</main>')
    if idx == -1:
        raise RuntimeError('未找到 </main>: ' + path)
    html = html[:idx] + wrapped + html[idx:]

    with open(path, 'w', encoding='utf-8') as f:
        f.write(html)
    print('OK ', os.path.basename(path))

for name, exp in EXPOSE.items():
    transform(os.path.join(BASE, name + '.html'), exp)
print('全部完成')
