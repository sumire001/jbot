/*
 * app.js — 共享工具 + 轻量客户端路由（软导航）
 *
 * 目标：侧边栏(.sidebar)、顶栏、favicon 等常驻不重载；点菜单只替换 .main-content。
 * 每个内容页把自身初始化逻辑包进 IIFE，暴露 window.JOnePage = { init, destroy }，
 * 路由在换页前调用上一页 destroy()（清理定时器/监听器），换页后脚本自动 init()。
 */
(function () {
    'use strict';

    /* ===================== 共享工具（全局，供常驻顶栏/底栏与各页调用） ===================== */
    window.showToast = function (msg, type) {
        type = type || 'info';
        var c = document.getElementById('toast-container');
        if (!c) {
            c = document.createElement('div');
            c.id = 'toast-container';
            c.className = 'toast-container';
            document.body.appendChild(c);
        }
        var t = document.createElement('div');
        t.className = 'toast ' + type;
        t.textContent = msg;
        c.appendChild(t);
        setTimeout(function () { t.remove(); }, 3000);
    };

    window.handleLogout = async function () {
        try {
            var res = await fetch('/api/auth/logout', { method: 'POST' });
            if (res.ok) window.location.href = '/login';
        } catch (e) {
            window.location.href = '/login';
        }
    };

    window.checkAuth = async function () {
        try {
            var res = await fetch('/api/auth/status');
            var json = await res.json();
            if (!json.authenticated) window.location.href = '/login';
        } catch (e) { /* 网络错误忽略，交由服务端拦截兜底 */ }
    };

    /* ===================== 轻量客户端路由 ===================== */
    var CONTENT = '.main-content';

    function getContent() { return document.querySelector(CONTENT); }

    function isNavigable(a) {
        if (!a) return false;
        if (a.target && a.target !== '_self') return false;
        if (a.hasAttribute('download')) return false;
        var href = a.getAttribute('href');
        if (!href || href.charAt(0) === '#' || href.indexOf('javascript:') === 0) return false;
        var url;
        try { url = new URL(href, location.href); } catch (e) { return false; }
        if (url.origin !== location.origin) return false;          // 外链不走软导航
        if (url.pathname === '/login') return false;               // 登录页无壳，走整页
        if (url.pathname === location.pathname) return false;      // 同页不处理
        return true;
    }

    function runScripts(container) {
        // innerHTML 不会执行 <script>，需手动替换为新节点触发执行
        var scripts = container.querySelectorAll('script');
        for (var i = 0; i < scripts.length; i++) {
            var old = scripts[i];
            var s = document.createElement('script');
            if (old.src) {
                s.src = old.src;
            } else {
                s.textContent = old.textContent;
            }
            if (old.parentNode) old.parentNode.replaceChild(s, old);
        }
    }

    function updateActiveNav(path) {
        var url = new URL(path, location.href);
        document.querySelectorAll('.nav-item, .bottom-nav-item').forEach(function (el) {
            var href = el.getAttribute('href');
            if (!href) return;
            var elUrl = new URL(href, location.href);
            if (elUrl.pathname === url.pathname) el.classList.add('active');
            else el.classList.remove('active');
        });
    }

    async function navigate(path, opts) {
        opts = opts || {};
        var push = opts.push !== false;
        try {
            // 1) 清理上一页（定时器 / 全局监听器）
            if (window.JOnePage && typeof window.JOnePage.destroy === 'function') {
                try { window.JOnePage.destroy(); } catch (e) { console.error('[router] destroy 失败', e); }
            }
            window.JOnePage = null;

            // 2) 拉取目标页
            var res = await fetch(path, { credentials: 'same-origin' });
            if (!res.ok) { window.location.href = path; return; }
            var text = await res.text();
            var doc = new DOMParser().parseFromString(text, 'text/html');

            // 鉴权失败：服务端把页面重定向到 /login（无 .sidebar 壳）
            if (!doc.querySelector('.sidebar')) { window.location.href = path; return; }

            var newContent = doc.querySelector(CONTENT);
            var newTitle = doc.querySelector('title');
            var cur = getContent();
            if (!newContent || !cur) { window.location.href = path; return; }

            // 3) 切换页面专属样式（共享样式在 theme.css 常驻，不随此切换）
            var oldStyle = document.getElementById('page-style');
            var newStyle = doc.getElementById('page-style');
            if (oldStyle) oldStyle.remove();
            if (newStyle) document.head.appendChild(newStyle);

            // 3b) 补全目标页 <head> 里的共享样式表（config.css 等）。
            //     软导航只换 .main-content，若来源页没加载该样式表，目标页的
            //     <link rel="stylesheet"> 不会自动生效，必须手动补齐，否则样式丢失。
            var headLinks = {};
            document.head.querySelectorAll('link[rel="stylesheet"]').forEach(function (l) {
                if (l.href) headLinks[l.href] = true;
            });
            doc.head.querySelectorAll('link[rel="stylesheet"]').forEach(function (l) {
                if (l.href && !headLinks[l.href]) {
                    var nl = document.createElement('link');
                    nl.rel = 'stylesheet';
                    nl.href = l.href;
                    document.head.appendChild(nl);
                    headLinks[l.href] = true;
                }
            });

            // 3c) 同理补全目标页 <head> 里缺失的共享脚本（<script src>，如某页专属的第三方库）。
            //     软导航只换内容区，head 里的 <script src> 不会被拉取/执行，若来源页没加载过就会漏掉。
            //     仅当该 src 在当前文档（head 或 body）中尚不存在时才补，避免重复执行；
            //     app.js / lucide / theme.js 等全局脚本因已存在会被自然跳过。
            var existingScripts = {};
            document.querySelectorAll('script[src]').forEach(function (s) {
                if (s.src) existingScripts[s.src] = true;
            });
            doc.head.querySelectorAll('script[src]').forEach(function (s) {
                if (s.src && !existingScripts[s.src]) {
                    var ns = document.createElement('script');
                    ns.src = s.src;
                    if (s.async) ns.async = true;
                    if (s.defer) ns.defer = true;
                    document.head.appendChild(ns);
                    existingScripts[s.src] = true;
                }
            });

            // 4) 只换内容区
            cur.innerHTML = newContent.innerHTML;

            // 5) 重新执行内容区里的脚本（包成 IIFE，会自动 init）
            runScripts(cur);

            // 6) 渲染新内容里的 Lucide 图标
            if (window.lucide && typeof window.lucide.createIcons === 'function') {
                window.lucide.createIcons();
            }

            // 7) 同步标题 / 高亮 / 滚动 / 历史
            if (newTitle) document.title = newTitle.textContent;
            updateActiveNav(path);
            window.scrollTo(0, 0);
            if (push) history.pushState({ path: path }, '', path);
        } catch (e) {
            console.error('[router] 导航失败，回退整页跳转', e);
            window.location.href = path;
        }
    }

    function onClick(e) {
        if (e.defaultPrevented || e.button !== 0 ||
            e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        var a = e.target.closest('a[href]');
        if (!isNavigable(a)) return;
        e.preventDefault();
        navigate(a.getAttribute('href'));
    }

    function onPopState() {
        navigate(location.pathname + location.search, { push: false });
    }

    function initRouter() {
        document.addEventListener('click', onClick);
        window.addEventListener('popstate', onPopState);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initRouter);
    } else {
        initRouter();
    }
})();
