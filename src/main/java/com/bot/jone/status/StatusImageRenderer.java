package com.bot.jone.status;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 状态面板渲染：模板 + 实时数据 -> HTML -> 本地无头 Chromium 截图 -> PNG 字节。
 *
 * 截图链路与 SekaiImageRenderer 完全同款（proot-distro 包装 Debian chromium，
 * 普通 Linux 自动退化为裸 chromium）。区别在于状态面板是固定尺寸布局，
 * 无需两段式量高，一次 --screenshot 即可。
 *
 * data 的 key 与 resources/status/status-template.html 里的 {{占位符}} 一一对应，
 * 由 CommandHandler 组装（SystemStatusCollector 本机指标 + OneBot get_version_info 版本号）。
 */
@Slf4j
@Service
public class StatusImageRenderer {

    private static final String TEMPLATE_RESOURCE = "/status/status-template.html";
    private static final String BG_RESOURCE = "/status/status-bg.png";
    private static final String BG_FILENAME = "status-bg.png";
    private static final long PROC_TIMEOUT_SEC = 90;   // 单次 chromium 进程超时

    /** 头像下载失败时的占位：模板原版的 SVG 图标（浅色描边） */
    private static final String FALLBACK_AVATAR_SVG =
            "<svg viewBox=\"0 0 24 24\" width=\"28\" height=\"28\" fill=\"none\" "
                    + "stroke=\"rgba(0,0,0,0.35)\" stroke-width=\"1.5\" "
                    + "stroke-linecap=\"round\" stroke-linejoin=\"round\">"
                    + "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\" ry=\"2\"></rect>"
                    + "<circle cx=\"8.5\" cy=\"8.5\" r=\"1.5\"></circle>"
                    + "<polyline points=\"21 15 16 10 5 21\"></polyline></svg>";

    private final StatusConfig config;

    /** 串行化渲染，避免并发 chromium 相互干扰（与 SekaiImageRenderer 各自独立加锁） */
    private final Object lock = new Object();

    /** 模板缓存：只读一次 classpath */
    private volatile String templateCache;

    public StatusImageRenderer(StatusConfig config) {
        this.config = config;
    }

    /**
     * 渲染状态面板。
     * @param data 占位符 -> 值（缺哪个占位符就保留原文，便于肉眼发现问题）
     * @return PNG 字节；失败抛 IllegalStateException（由 CommandHandler 转成提示文本）
     */
    public byte[] render(Map<String, String> data) {
        synchronized (lock) {
            Path dir;
            try {
                dir = Path.of(config.getRenderTempDir()).toAbsolutePath();
                Files.createDirectories(dir);
            } catch (Exception e) {
                throw new IllegalStateException("渲染临时目录创建失败: " + config.getRenderTempDir(), e);
            }
            String tag = System.currentTimeMillis() + "-" + Thread.currentThread().getId();
            Path htmlFile = dir.resolve("status_" + tag + ".html");
            Path pngFile = dir.resolve("status_" + tag + ".png");
            Path profDir = dir.resolve("prof_" + tag);
            Path bgFile = dir.resolve(BG_FILENAME);
            try {
                Files.writeString(htmlFile, fillTemplate(data), StandardCharsets.UTF_8);
                ensureBgImage(bgFile);

                boolean ok = screenshot(htmlFile, pngFile, profDir);
                if (!ok || !Files.exists(pngFile) || Files.size(pngFile) == 0) {
                    throw new IllegalStateException("本地 Chromium 截图失败（proot/chromium 未就绪？）");
                }
                byte[] bytes = Files.readAllBytes(pngFile);
                log.info("[Status] 状态面板渲染成功 {}x{}, {} bytes",
                        config.getRenderWidth(), config.getRenderHeight(), bytes.length);
                return bytes;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("状态面板渲染异常: " + e.getMessage(), e);
            } finally {
                deleteQuietly(htmlFile);
                deleteQuietly(pngFile);
                deleteTree(profDir);
            }
        }
    }

    /** 模板填充：逐个替换 {{KEY}} 占位符；data 缺失的占位符保留原文 */
    private String fillTemplate(Map<String, String> data) {
        String html = template();
        // 头像：data 里带 AVATAR_B64（PNG base64，CommandHandler 下载）则以 data URI 内嵌，
        // 否则回退为模板原版 SVG 占位图。截图走本地 file://，内嵌 base64 不依赖网络。
        String avatarB64 = data.remove("AVATAR_B64");
        data.put("AVATAR_TAG", (avatarB64 != null && !avatarB64.isBlank())
                ? "<img class=\"profile-avatar-img\" alt=\"avatar\" src=\"data:image/png;base64," + avatarB64 + "\">"
                : FALLBACK_AVATAR_SVG);
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getValue() != null) {
                html = html.replace("{{" + e.getKey() + "}}", e.getValue());
            }
        }
        return html;
    }

    private String template() {
        String t = templateCache;
        if (t == null) {
            try (InputStream is = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
                if (is == null) {
                    throw new IllegalStateException("找不到模板资源 " + TEMPLATE_RESOURCE);
                }
                t = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                templateCache = t;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("读取状态模板失败: " + e.getMessage(), e);
            }
        }
        return t;
    }

    /** 把 classpath 里的背景图释放到临时目录（与 HTML 同目录，模板用相对路径引用） */
    private void ensureBgImage(Path target) throws Exception {
        if (Files.exists(target)) {
            return; // 固定资源，释放一次即可
        }
        try (InputStream is = new ClassPathResource(BG_RESOURCE.substring(1)).getInputStream()) {
            Files.copy(is, target);
        }
    }

    /** 组装 chromium 命令前缀：proot 包装（Android/Termux）或裸 chromium（普通 Linux） */
    private List<String> commandPrefix() {
        List<String> cmd = new ArrayList<>();
        String proot = config.getRenderProotBin();
        if (proot != null && !proot.isBlank() && Files.isExecutable(Path.of(proot))) {
            cmd.add(proot);
            cmd.add("login");
            cmd.add(config.getRenderContainer());
            cmd.add("--");
        }
        cmd.add("chromium");
        return cmd;
    }

    /** 一次性截图：固定窗口尺寸（模板为固定布局，无需两段式量高） */
    private boolean screenshot(Path htmlFile, Path pngFile, Path profDir) throws Exception {
        List<String> cmd = commandPrefix();
        cmd.add("--headless");
        cmd.add("--no-sandbox");
        cmd.add("--disable-gpu");
        cmd.add("--disable-dev-shm-usage");
        cmd.add("--user-data-dir=" + profDir.toAbsolutePath());
        cmd.add("--hide-scrollbars");
        cmd.add("--screenshot=" + pngFile.toAbsolutePath());
        cmd.add("--window-size=" + config.getRenderWidth() + "," + config.getRenderHeight());
        cmd.add("file://" + htmlFile.toAbsolutePath());
        String out = runCapture(cmd);
        boolean exists = Files.exists(pngFile) && Files.size(pngFile) > 0;
        if (!exists) {
            log.warn("[Status] chromium 截图无输出: {}", tail(out, 300));
        }
        return exists;
    }

    /** 执行命令并回收 stdout（过滤掉容器里 dbus/udev 的无害噪音） */
    private String runCapture(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("dbus") && !line.contains("udev") && !line.contains("object_proxy")) {
                    sb.append(line).append('\n');
                }
            }
        }
        boolean done = p.waitFor(PROC_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("Chromium 渲染超时");
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
        }
    }

    private static void deleteTree(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                        }
                    });
        } catch (Exception ignore) {
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
