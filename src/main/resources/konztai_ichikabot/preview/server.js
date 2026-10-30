/**
 * ichikaBot 前端预览服务
 * — 纯 Node.js 内置模块，零依赖
 * — 托管 static/ 静态资源 + templates/ 页面，并提供 Mock API
 *
 * 启动： node preview/server.js
 * 端口： 默认 3000，可用 `--port=8080` 或环境变量 PORT 覆盖
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

// ======================== 路径配置 ========================
const ROOT = path.resolve(__dirname, '..');        // konztai_ichikabot/
const STATIC_DIR = path.join(ROOT, 'static');
const TEMPLATES_DIR = path.join(ROOT, 'templates');

// ======================== 端口 ========================
const PORT = (process.env.PORT && parseInt(process.env.PORT, 10))
    || (process.argv.find(a => a.startsWith('--port=')) && parseInt(process.argv.find(a => a.startsWith('--port=')).split('=')[1], 10))
    || 3000;

// ======================== 页面路由 ========================
const PAGES = {
    '/': 'index.html',
    '/monitor-page': 'monitor.html',
    '/command-page': 'command.html',
    '/ai-models-page': 'ai-models.html',
    '/search-source-page': 'search-source.html',
    '/memes-page': 'memes.html',
    '/batch-upload-page': 'batch-upload.html',
    '/general-settings-page': 'general-settings.html',
    '/login': 'login.html'
};

// ======================== MIME 类型 ========================
const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2'
};

// ======================== Mock 数据 ========================
const MOCK = {
    // 认证
    '/api/auth/status': { authenticated: true, hasPassword: true },
    '/api/auth/login': { success: true, message: '登录成功（预览模式）' },
    '/api/auth/setup': { success: true, message: '初始密码已设置（预览模式）' },
    '/api/auth/logout': { success: true, message: '已登出（预览模式）' },

    // 监控
    '/monitor/memory': {
        success: true,
        data: { heapUsedMB: 256, heapMaxMB: 1024, jvmTotalMB: 512 }
    },
    '/monitor/jvm': {
        success: true,
        jvm: {
            javaVersion: '17.0.10 (mock)',
            javaVendor: 'Preview JVM',
            osName: 'Windows 11 (Preview)'
        },
        uptime: { uptimeMs: 7200000, formatted: '2小时0分0秒' }
    },
    '/monitor/gc': {
        success: true,
        currentMemory: { heapUsedMB: 180 }
    },

    // 通用配置
    '/api/config/all': {
        success: true,
        imageDownload: { maxSizeMb: 10 },
        imageCompression: { sizeThresholdMb: 3, maxDimension: 1920, jpegQuality: 0.85, skipGif: true },
        gallery: {
            enabled: false, apiUser: '', apiKey: '', apiUrl: '',
            cols: 3, rows: 3, width: 512, scale: 2, thumbDim: 256
        },
        cache: { messageMaxSize: 100 },
        proxy: { enabled: false, type: 'http', host: '', port: 7890, username: '', nonProxyHosts: '' },
        admin: { qqList: ['10001', '10002'] },
        ai: { enabled: true, provider: 'openai', apiKey: '', model: 'gpt-4o', baseUrl: '', systemPrompt: '', defaultPrompt: '' },
        searchEngine: { engine: 'bing' }
    },
    '/api/config/command': {
        success: true,
        prefix: '/',
        aliases: { 'ping': 'p', 'help': 'h', 'memes': 'm' },
        permissions: { 'ping': true, 'help': true, 'memes': false }
    },
    '/api/config/models': {
        success: true,
        models: []
    },
    '/api/config/ai-default-prompt': {
        success: true,
        defaultPrompt: '你是一个友好的 QQ 机器人（预览模式）。'
    },
    '/api/config/long-term-memory/users': {
        success: true,
        users: []
    },

    // 表情包
    '/api/memes/categories': {
        success: true,
        categories: {
            'funny': [{ name: 'sample1.jpg', url: '' }, { name: 'sample2.jpg', url: '' }],
            'cat': [{ name: 'cat1.gif', url: '' }]
        },
        stats: { 'funny': 2, 'cat': 1 }
    },
    '/api/memes/root-dir': {
        success: true,
        rootDir: '/preview/memes (mock)'
    }
};

// 默认的写入类接口返回
const OK_SAVE = { success: true, message: '已保存（预览模式，不会真正持久化）' };

// ======================== 工具函数 ========================
function sendJson(res, obj, status = 200) {
    const body = JSON.stringify(obj);
    res.writeHead(status, {
        'Content-Type': MIME['.json'],
        'Cache-Control': 'no-store',
        'Access-Control-Allow-Origin': '*'
    });
    res.end(body);
}

function sendFile(res, filePath, status = 200) {
    fs.readFile(filePath, (err, data) => {
        if (err) {
            sendJson(res, { error: 'Not Found', path: filePath }, 404);
            return;
        }
        const ext = path.extname(filePath).toLowerCase();
        res.writeHead(status, {
            'Content-Type': MIME[ext] || 'application/octet-stream',
            'Cache-Control': 'no-cache'
        });
        res.end(data);
    });
}

function readBody(req) {
    return new Promise(resolve => {
        let buf = '';
        req.on('data', c => { buf += c; if (buf.length > 1e6) req.destroy(); });
        req.on('end', () => resolve(buf));
        req.on('error', () => resolve(''));
    });
}

// ======================== Mock 路由判定 ========================
function handleMock(pathname, method) {
    // GET 类：直接命中静态 Mock
    if (method === 'GET' && Object.prototype.hasOwnProperty.call(MOCK, pathname)) {
        return MOCK[pathname];
    }
    // 其余 /api/* 与 /monitor/* 的写操作统一返回成功
    if (pathname.startsWith('/api/') || pathname.startsWith('/monitor/')) {
        return OK_SAVE;
    }
    return null;
}

// ======================== HTTP 服务 ========================
const server = http.createServer(async (req, res) => {
    const parsed = url.parse(req.url, true);
    const pathname = parsed.pathname;
    const method = req.method.toUpperCase();

    // CORS 预检
    if (method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type'
        });
        res.end();
        return;
    }

    // 1) API / Monitor 接口（Mock）
    if (pathname.startsWith('/api/') || pathname.startsWith('/monitor/')) {
        if (method !== 'GET') await readBody(req); // 消费请求体
        const mock = handleMock(pathname, method);
        if (mock !== null) {
            sendJson(res, mock);
        } else {
            sendJson(res, OK_SAVE);
        }
        console.log(`  [MOCK] ${method} ${pathname} → 200`);
        return;
    }

    // 2) 页面路由
    if (PAGES[pathname]) {
        const file = path.join(TEMPLATES_DIR, PAGES[pathname]);
        sendFile(res, file);
        console.log(`  [PAGE] ${method} ${pathname} → ${PAGES[pathname]}`);
        return;
    }

    // 3) 静态资源（/css/..., /js/..., /favicon.png, /123.jpg ...）
    const relPath = pathname.replace(/^\/+/, '');
    if (relPath) {
        const staticPath = path.join(STATIC_DIR, relPath);
        // 防止路径穿越
        if (staticPath.startsWith(STATIC_DIR) && fs.existsSync(staticPath) && fs.statSync(staticPath).isFile()) {
            sendFile(res, staticPath);
            console.log(`  [STATIC] ${method} ${pathname}`);
            return;
        }
    }

    // 4) 404
    console.log(`  [404] ${method} ${pathname}`);
    sendJson(res, { error: 'Not Found', path: pathname }, 404);
});

server.listen(PORT, () => {
    console.log('========================================================');
    console.log('  ichikaBot 前端预览服务已启动');
    console.log('--------------------------------------------------------');
    console.log(`  本地访问:  http://localhost:${PORT}/`);
    console.log(`  登录页:    http://localhost:${PORT}/login`);
    console.log('  可用页面:');
    Object.keys(PAGES).forEach(p => {
        console.log(`    ${p === '/' ? '/' : p}`);
    });
    console.log('--------------------------------------------------------');
    console.log('  说明: 所有 /api/* 与 /monitor/* 均返回 Mock 数据');
    console.log('  修改 static/css 或 templates 后刷新浏览器即可看到效果');
    console.log('  按 Ctrl+C 停止服务');
    console.log('========================================================');
});
