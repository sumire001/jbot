package com.bot.jone.service;

import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.config.GalleryConfig;
import com.bot.jone.config.ImagesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 画廊渲染服务：
 * 扫描分类目录 → 生成毛玻璃画廊 HTML（图片 base64 内嵌）→ 调用外部 HTML→图片 API
 * （hcti.io）渲染并下载 PNG → 按 (目录, 文件数, 最新修改时间) 缓存，避免重复烧额度。
 *
 * 与 tools/gallery.py 的区别：不再依赖本地 Python / 浏览器，纯 Java + 外部 API。
 */
@Service
public class GalleryService {

    private static final Logger log = LoggerFactory.getLogger(GalleryService.class);

    private static final Set<String> IMG_EXT = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tiff", ".tif");

    /** 毛玻璃画廊模板（与 data/images/memes/mydemo.html 同款风格，仅替换三个占位符） */
    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <title>__TITLE__</title>
            <style>
            * { margin:0; padding:0; box-sizing:border-box; }
            
            /* 1. 让 body 撑满全屏，作为毛玻璃的载体 */
            body {
            min-height: 100vh;
            /* 背景渐变直接铺满全屏 */
            background: linear-gradient(135deg, #FFFFFF 20%, #FFDCF4 100%);
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            /* 内容整体居中，但背景是全屏的 */
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            padding: 40px 20px;
            }
            
            /* 2. 去掉固定宽度限制，让毛玻璃铺满全屏 */
            .glass-container {
            width: 100%;
            max-width: 100%; /* 关键：取消 900px 限制 */
            padding: 40px 20px;
            background: rgba(255, 255, 255, 0.15);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border-radius: 0; /* 全屏时通常不需要圆角，或改为 0 */
            border: none; /* 全屏时去掉边框更自然 */
            box-shadow: none; /* 全屏时阴影效果不明显，可去掉 */
            /* 如果希望内容区有内边距，但背景铺满，可以用 min-height */
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            }
            
            /* 3. 给表格加一个最大宽度，避免超宽屏下表格里内容太稀疏 */
            .table-wrapper {
            width: 100%;
            max-width: 1200px; /* 可根据需要调整 */
            }
            
            .glass-container h2 {
            color: #191970;
            text-align: center;
            margin-bottom: 30px;
            font-size: 1.6rem;
            font-weight: 500;
            text-shadow: 0 2px 4px rgba(0, 0, 0, 0.35);
            }
            
            .glass-container p {
            color: #191970;
            text-align: center;
            margin-top: 30px;
            font-size: 1.13rem;
            font-weight: 500;
            text-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
            }
            
            .emoji-table {
            width: 100%;
            border-collapse: separate;
            border-spacing: 12px; /* 适当增大间距，全屏下更舒展 */
            table-layout: fixed;
            }
            
            .emoji-table td {
            background: rgba(255, 255, 255, 0.05);
            padding: 12px;
            border-radius: 12px;
            text-align: center;
            transition: all 0.3s ease;
            cursor: default;
            height: 160px; /* 适当增加高度 */
            vertical-align: middle;
            word-break: break-all;
            }
            
            .emoji-table td:hover {
            background: rgba(255, 255, 255, 0.25);
            transform: scale(1.04);
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
            }
            
            .emoji-table td figure {
            margin: 0;
            padding: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            height: 100%;
            }
            
            .emoji-table td img {
            width: 100%;
            height: 100%;
            max-width: 300px;
            max-height: 300px;
            object-fit: cover;
            border-radius: 10px;
            display: block;
            margin: 0 auto;
            }
            
            .emoji-table td figcaption {
            margin-top: 8px;
            font-size: 0.85rem;
            color: #7D7C7D;
            text-align: center;
            text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
            max-width: 100%;
            overflow: hidden;
            white-space: nowrap;
            text-overflow: ellipsis;
            }
            </style>
            </head>
            <body>
            <div class="glass-container">
            <h2>__TITLE__</h2>
            <div class="table-wrapper">
            <table class="emoji-table"><tbody>__ROWS__</tbody></table>
            </div>
            <p>__FOOTER__</p>
            </div>
            </body>
            </html>
            """;

    @Autowired
    private GalleryConfig galleryConfig;

    @Autowired
    private ImagesConfig imagesConfig;

    /**
     * 生成指定分类的某一页画廊，返回 PNG 文件（命中缓存则直接返回）。
     * @param category 分类名（对应 images 根目录下的子目录）
     * @param page 页码（1 基，已在外面校验过范围）
     * @return PNG 文件，失败返回 null
     */
    public File generatePage(String category, int page) {
        if (!galleryConfig.isEnabled()) {
            log.warn("[Gallery] 功能未启用");
            return null;
        }
        File dir = new File(imagesConfig.getRootDir(), category);
        File[] all = dir.listFiles((d, n) -> isImageName(n));
        if (all == null) {
            return null;
        }
        List<File> images = new ArrayList<>(Arrays.asList(all));
        images.sort(Comparator.comparing(File::getName));

        int perPage = galleryConfig.getCols() * galleryConfig.getRows();
        int total = (images.size() + perPage - 1) / perPage;
        if (page < 1 || page > total) {
            return null;
        }

        File cacheDir = new File(new File(imagesConfig.getRootDir()).getParentFile(), "gallery_cache/" + category);
        cacheDir.mkdirs();
        File meta = new File(cacheDir, ".meta");
        File png = new File(cacheDir, "p" + page + ".png");

        long lastMod = images.stream().mapToLong(File::lastModified).max().orElse(0);
        if (png.exists() && metaValid(meta, images.size(), lastMod, perPage)) {
            return png;
        }

        List<File> pageImgs = images.subList((page - 1) * perPage, Math.min(page * perPage, images.size()));
        String html = buildHtml(category, pageImgs, page, total, perPage, images.size());
        byte[] bytes = renderViaApi(html);
        if (bytes == null) {
            return null;
        }
        try {
            Files.write(png.toPath(), bytes);
            writeMeta(meta, images.size(), lastMod, perPage);
            return png;
        } catch (IOException e) {
            log.error("[Gallery] 写入 PNG 失败: {}", png, e);
            return null;
        }
    }

    /**
     * 调用外部 API 渲染 HTML 并下载 PNG 字节。
     */
    private byte[] renderViaApi(String html) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(galleryConfig.getApiUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            String auth = Base64.getEncoder().encodeToString(
                    (galleryConfig.getApiUser() + ":" + galleryConfig.getApiKey()).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);

            JSONObject req = new JSONObject();
            req.put("html", html);
            req.put("full_screen", true);
            req.put("device_scale", galleryConfig.getScale());
            req.put("width", galleryConfig.getWidth());
            try (OutputStream os = conn.getOutputStream()) {
                os.write(req.toJSONString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                log.error("[Gallery] hcti 返回错误 {}: {}", code, readString(conn.getErrorStream()));
                return null;
            }
            String resp = readString(conn.getInputStream());
            String imageUrl = JSONObject.parseObject(resp).getString("url");
            if (imageUrl == null) {
                log.error("[Gallery] hcti 响应缺少 url: {}", resp);
                return null;
            }

            HttpURLConnection imgConn = (HttpURLConnection) new URL(imageUrl).openConnection();
            imgConn.setConnectTimeout(30000);
            imgConn.setReadTimeout(60000);
            try (InputStream is = imgConn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.error("[Gallery] 调用 hcti 渲染失败", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 生成单页画廊 HTML（图片以 base64 data URI 内嵌）。
     */
    private String buildHtml(String title, List<File> pageImgs, int page, int total, int perPage, int totalImages) {
        List<String> cells = new ArrayList<>();
        for (File f : pageImgs) {
            String b64 = b64Thumb(f, galleryConfig.getThumbDim());
            if (b64 == null) {
                continue;
            }
            String name = f.getName();
            // 仅用于显示：去掉扩展名（取最后一个点之前的部分）
            String safe = escapeHtml(name);
            int dotIndex = safe.lastIndexOf(".");
            if (dotIndex > 0) {
                safe = safe.substring(0, dotIndex);
            }
            cells.add("<td><figure><img src=\"" + b64 + "\" alt=\"" + safe + "\"><figcaption>"
                    + safe + "</figcaption></figure></td>");
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < cells.size(); i += galleryConfig.getCols()) {
            List<String> slice = cells.subList(i, Math.min(i + galleryConfig.getCols(), cells.size()));
            rows.append("<tr>").append(String.join("", slice)).append("</tr>");
        }
        String footer = String.format("第 %d / %d 页 · 本页 %d 张 · 共 %d 张图",
                page, total, cells.size(), totalImages);
        return TEMPLATE
                .replace("__TITLE__", escapeHtml(title))
                .replace("__ROWS__", rows.toString())
                .replace("__FOOTER__", footer);
    }

    /**
     * 把图片缩放后编码为 base64（JPEG）。若 ImageIO 无法解码或解码抛异常
     * （如 webp、部分 GIF 的 JDK bug），则直接内嵌原始字节，交由浏览器渲染。
     */
    private String b64Thumb(File f, int maxDim) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img != null) {
                int w = img.getWidth(), h = img.getHeight();
                if (maxDim > 0) {
                    double sc = Math.min(1.0, (double) maxDim / Math.max(w, h));
                    if (sc < 1.0) {
                        int nw = Math.max(1, (int) (w * sc));
                        int nh = Math.max(1, (int) (h * sc));
                        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = scaled.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(img, 0, 0, nw, nh, null);
                        g.dispose();
                        img = scaled;
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", baos);
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
            }
        } catch (Exception e) {
            // GIF 等 ImageIO 解码会抛异常（已知 JDK bug），落到下方原始字节兜底
            log.warn("[Gallery] 缩略图解码失败，改用原始字节内嵌: {}", f.getName(), e);
        }
        // 兜底：直接内嵌原始字节（webp / gif / 其它 ImageIO 无法解码的格式）
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            return "data:" + mimeOf(f.getName()) + ";base64," + Base64.getEncoder().encodeToString(raw);
        } catch (IOException e) {
            log.warn("[Gallery] 缩略图原始字节读取失败: {}", f.getName(), e);
            return null;
        }
    }

    private static boolean isImageName(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) {
            return false;
        }
        return IMG_EXT.contains(name.substring(i).toLowerCase());
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean metaValid(File meta, long fileCount, long lastMod, int perPage) {
        if (!meta.exists()) {
            return false;
        }
        try {
            String[] lines = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8).split("\n");
            if (lines.length < 3) {
                return false;
            }
            long c = Long.parseLong(lines[0].trim());
            long m = Long.parseLong(lines[1].trim());
            int pp = Integer.parseInt(lines[2].trim());
            return c == fileCount && m == lastMod && pp == perPage;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeMeta(File meta, long fileCount, long lastMod, int perPage) throws IOException {
        String content = fileCount + "\n" + lastMod + "\n" + perPage + "\n";
        Files.write(meta.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
