package com.bot.jone.ioutils;

import com.bot.jone.config.ImagesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Component
@Slf4j
public class ImageDownloader {
    
    @Autowired
    private ImagesConfig imagesConfig;
    
    /**
     * 从 URL 下载图片
     * @param imageUrl 图片 URL
     * @return 图片字节数组，失败返回 null
     */
    public byte[] downloadImage(String imageUrl) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        ByteArrayOutputStream outputStream = null;
        
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            
            // 设置请求头（模拟浏览器）
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log.error("下载图片失败，HTTP状态码: {}", responseCode);
                return null;
            }
            
            // 检查Content-Length，如果超过限制则不下载
            long maxDownloadSize = imagesConfig.getDownload().getMaxSizeBytes();
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maxDownloadSize) {
                log.error("图片过大，拒绝下载: {} bytes (限制: {} bytes)", 
                    contentLength, maxDownloadSize);
                return null;
            }
            
            inputStream = connection.getInputStream();
            outputStream = new ByteArrayOutputStream();
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytes = 0;
            long maxSize = imagesConfig.getDownload().getMaxSizeBytes();
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > maxSize) {
                    log.error("图片下载超过大小限制，已读取: {} bytes", totalBytes);
                    return null;
                }
                outputStream.write(buffer, 0, bytesRead);
            }
            
            byte[] imageData = outputStream.toByteArray();
            log.info("下载图片成功，大小: {} bytes", imageData.length);
            return imageData;
            
        } catch (Exception e) {
            log.error("下载图片异常: {}", imageUrl, e);
            return null;
        } finally {
            // 关闭资源
            try {
                if (outputStream != null) outputStream.close();
                if (inputStream != null) inputStream.close();
                if (connection != null) connection.disconnect();
            } catch (Exception e) {
                log.error("关闭资源失败", e);
            }
        }
    }
    
    /**
     * 从 URL 中提取文件扩展名
     * @param imageUrl 图片 URL
     * @return 文件扩展名（如 jpg, png），默认返回 jpg
     */
    public String extractExtension(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return "jpg";
        }
        
        try {
            // 先去除 URL 参数部分
            String urlWithoutParams = imageUrl;
            int paramIndex = imageUrl.indexOf('?');
            if (paramIndex != -1) {
                urlWithoutParams = imageUrl.substring(0, paramIndex);
            }
            
            // 从路径中提取最后一个 / 之后的部分
            int lastSlashIndex = urlWithoutParams.lastIndexOf('/');
            String fileName = lastSlashIndex != -1 
                ? urlWithoutParams.substring(lastSlashIndex + 1) 
                : urlWithoutParams;
            
            // 从文件名中提取扩展名
            int lastDotIndex = fileName.lastIndexOf('.');
            if (lastDotIndex != -1 && lastDotIndex < fileName.length() - 1) {
                String extension = fileName.substring(lastDotIndex + 1);
                // 验证扩展名是否合法（只包含字母和数字）
                if (extension.matches("[a-zA-Z0-9]+")) {
                    return extension.toLowerCase();
                }
            }
        } catch (Exception e) {
            log.warn("提取文件扩展名失败，使用默认扩展名: {}", e.getMessage());
        }
        
        return "jpg"; // 默认扩展名
    }

    /**
     * 根据图片字节的「魔数」（文件头签名）识别真实格式，返回扩展名（不含点）。
     * 比单纯信任 URL 扩展名更可靠：QQ/OneBot 的图片 URL 通常没有扩展名，
     * 用 URL 识别会一律回落成 jpg，导致扩展名错标（真实 PNG 被存成 .jpg）。
     * @param data 图片字节
     * @return 扩展名（jpg/png/gif/webp/bmp/tiff），无法识别返回 null
     */
    public String detectImageExtension(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF, b2 = data[2] & 0xFF, b3 = data[3] & 0xFF;
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return "png";
        }
        // GIF: 47 49 46 38 (GIF8)
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') {
            return "gif";
        }
        // WEBP: 52 49 46 46 (RIFF) ... 57 45 42 50 (WEBP) @ offset 8
        if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        // BMP: 42 4D
        if (b0 == 'B' && b1 == 'M') {
            return "bmp";
        }
        // TIFF: 49 49 2A 00 (little-endian) 或 4D 4D 00 2A (big-endian)
        if ((b0 == 'I' && b1 == 'I' && b2 == 0x2A && b3 == 0x00)
                || (b0 == 'M' && b1 == 'M' && b2 == 0x00 && b3 == 0x2A)) {
            return "tiff";
        }
        return null;
    }
}
