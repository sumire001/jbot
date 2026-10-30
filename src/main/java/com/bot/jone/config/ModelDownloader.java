package com.bot.jone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.function.BiConsumer;

/**
 * 通用模型权重下载器。
 *
 * 复用 JVM 的 ProxySelector（与全项目出站一致）：若 bot.proxy 启用，下载流量经代理出口；
 * ModelScope 为国内站，可在 bot.proxy.non-proxy-hosts 追加 modelscope.cn 以直连提速。
 *
 * 行为：下载到同名 .part 临时文件 → 校验大小与可选 sha256 → 原子移动到目标路径。
 * 不做断点续传（模型文件一次拉取即可，失败由调用方重试）。失败时抛异常，由 ModelManager 降级处理。
 */
@Slf4j
@Component
public class ModelDownloader {

    /** 下载单个文件到 target（父目录自动创建）。progress 回调 (已下载, 总大小)。 */
    public void download(String url, File target, String expectedSha256,
                         BiConsumer<Long, Long> progress) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());

        File part = new File(target.getAbsolutePath() + ".part");
        Files.deleteIfExists(part.toPath());

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(0); // 大文件不限读超时
            conn.setRequestProperty("User-Agent", "J-One-ModelManager/1.0");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("下载失败 HTTP " + code + " : " + url);
            }

            long total = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (progress != null) progress.accept(downloaded, total);
                }
            }

            long fileSize = part.length();
            if (total > 0 && fileSize != total) {
                throw new IllegalStateException(
                        String.format("下载大小不符：期望 %d，实际 %d (%s)", total, fileSize, url));
            }
            if (fileSize == 0) {
                throw new IllegalStateException("下载文件为空: " + url);
            }

            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actual = sha256(part);
                if (!actual.equalsIgnoreCase(expectedSha256)) {
                    throw new IllegalStateException(
                            String.format("sha256 校验失败：期望 %s，实际 %s (%s)", expectedSha256, actual, url));
                }
            }

            // 原子移动覆盖
            Files.deleteIfExists(target.toPath());
            Files.move(part.toPath(), target.toPath());
            log.info("[model] 下载完成 {} ({} bytes) -> {}", url, fileSize, target.getAbsolutePath());
        } finally {
            if (conn != null) conn.disconnect();
            Files.deleteIfExists(part.toPath());
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }
}
