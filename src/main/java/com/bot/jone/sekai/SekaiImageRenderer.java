package com.bot.jone.sekai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B30 图片渲染：HTML 模板 -> 本地无头 Chromium 截图 -> PNG 字节。
 *
 * 部署环境是 Android/Termux(aarch64, bionic)。Playwright 自带的 Node 驱动是 glibc 二进制
 * （PT_INTERP=/lib/ld-linux-aarch64.so.1），在 Android 上无法执行，故不再用 Playwright，
 * 改为直接调用 Debian(proot-distro 容器) 里的 Chromium 无头截图：
 *   proot-distro login debian -- chromium --headless --no-sandbox ... --screenshot=out.png file://in.html
 * HTML/PNG 放在 Termux home 下（proot 已 bind 该目录进容器），Termux 与容器两端可读写。
 *
 * 两段式渲染：先 --dump-dom 量出真实内容高度，再按该高度 --screenshot，保证 30 项不被裁切
 * （不同字号/字体下内容高度会变，实测校准优于写死高度）。
 *
 * 可移植性：若 renderProotBin 指向的二进制不存在（如在普通 Linux、chromium 在 PATH），
 * 自动退化为直接调用 "chromium"。
 */
@Slf4j
@Service
public class SekaiImageRenderer {

    private static final int HEIGHT_SLACK = 12;        // 截图高度冗余，防亚像素裁切
    private static final long PROC_TIMEOUT_SEC = 90;    // 单次 chromium 进程超时
    private static final Pattern DATA_H = Pattern.compile("data-h=\"(\\d+)\"");

    // 注入到 HTML 的测量脚本：load 后把真实内容高度写到 <html data-h="..">
    // 注意：headless 下 documentElement.scrollHeight 会被视口高度污染（实测 2400 窗口报 2257），
    // 只信 body.scrollHeight（内容真实高度）；body 缺失时才回退 documentElement。
    private static final String MEASURE_SCRIPT =
            "<script>window.addEventListener('load',function(){var h=document.body?"
                    + "Math.ceil(document.body.scrollHeight):"
                    + "Math.ceil(document.documentElement.scrollHeight);"
                    + "document.documentElement.setAttribute('data-h',h);});</script>";

    private final SekaiConfig config;

    /** 串行化渲染，避免并发 chromium 相互干扰（b30 命令走异步线程） */
    private final Object lock = new Object();

    public SekaiImageRenderer(SekaiConfig config) {
        this.config = config;
    }

    /** @return PNG 字节；失败抛 IllegalStateException（由 CommandHandler 转成提示文本） */
    public byte[] render(String html) {
        synchronized (lock) {
            Path dir;
            try {
                dir = Path.of(config.getRenderTempDir()).toAbsolutePath();
                Files.createDirectories(dir);
            } catch (Exception e) {
                throw new IllegalStateException("渲染临时目录创建失败: " + config.getRenderTempDir(), e);
            }
            String tag = System.currentTimeMillis() + "-" + Thread.currentThread().getId();
            Path htmlFile = dir.resolve("b30_" + tag + ".html");
            Path pngFile = dir.resolve("b30_" + tag + ".png");
            Path profDir = dir.resolve("prof_" + tag);
            try {
                Files.writeString(htmlFile, injectMeasure(html), StandardCharsets.UTF_8);

                int contentH = measure(htmlFile, profDir);
                if (contentH <= 0) {
                    contentH = estimateHeight(html);
                    log.warn("[Sekai] 内容高度测量失败，改用估算高度 {}", contentH);
                }
                int shotH = contentH + HEIGHT_SLACK;

                boolean ok = screenshot(htmlFile, pngFile, profDir, shotH);
                if (!ok || !Files.exists(pngFile) || Files.size(pngFile) == 0) {
                    throw new IllegalStateException("本地 Chromium 截图失败（proot/chromium 未就绪？）");
                }
                byte[] bytes = Files.readAllBytes(pngFile);
                log.info("[Sekai] B30 渲染成功 {}x{}, {} bytes", config.getRenderWidth(), shotH, bytes.length);
                return bytes;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("B30 渲染异常: " + e.getMessage(), e);
            } finally {
                deleteQuietly(htmlFile);
                deleteQuietly(pngFile);
                deleteTree(profDir);
            }
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

    private List<String> chromiumCommonArgs(Path profDir) {
        List<String> a = new ArrayList<>();
        a.add("--headless");
        a.add("--no-sandbox");
        a.add("--disable-gpu");
        a.add("--disable-dev-shm-usage");
        a.add("--user-data-dir=" + profDir.toAbsolutePath());
        a.add("--hide-scrollbars");
        return a;
    }

    /** 第一段：量高度。用 --dump-dom 拿注入了 data-h 的 DOM */
    private int measure(Path htmlFile, Path profDir) throws Exception {
        List<String> cmd = commandPrefix();
        cmd.addAll(chromiumCommonArgs(profDir));
        cmd.add("--dump-dom");
        cmd.add("--window-size=" + config.getRenderWidth() + ",2400");
        cmd.add("file://" + htmlFile.toAbsolutePath());
        String out = runCapture(cmd);
        Matcher m = DATA_H.matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 第二段：按目标高度截图 */
    private boolean screenshot(Path htmlFile, Path pngFile, Path profDir, int height) throws Exception {
        List<String> cmd = commandPrefix();
        cmd.addAll(chromiumCommonArgs(profDir));
        cmd.add("--screenshot=" + pngFile.toAbsolutePath());
        cmd.add("--window-size=" + config.getRenderWidth() + "," + height);
        cmd.add("file://" + htmlFile.toAbsolutePath());
        String out = runCapture(cmd);
        boolean exists = Files.exists(pngFile) && Files.size(pngFile) > 0;
        if (!exists) {
            log.warn("[Sekai] chromium 截图无输出: {}", tail(out, 300));
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

    private static String injectMeasure(String html) {
        int i = html.toLowerCase().lastIndexOf("</body>");
        if (i >= 0) {
            return html.substring(0, i) + MEASURE_SCRIPT + html.substring(i);
        }
        int j = html.toLowerCase().lastIndexOf("</html>");
        if (j >= 0) {
            return html.substring(0, j) + MEASURE_SCRIPT + html.substring(j);
        }
        return html + MEASURE_SCRIPT;
    }

    /** 测量失败时的估算：header 常量 + 行数*行高（6 列网格）。按实测 30 项/5 行≈858 校准 */
    private static int estimateHeight(String html) {
        int n = countOccur(html, "class='item'") + countOccur(html, "class=\"item\"");
        int rows = Math.max(1, (int) Math.ceil(n / 6.0));
        return 210 + rows * 130;
    }

    private static int countOccur(String s, String sub) {
        int c = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            c++;
            idx += sub.length();
        }
        return c;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private static void deleteTree(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // 忽略
                        }
                    });
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
