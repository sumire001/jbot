package com.bot.jone.ioutils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Component
@Slf4j
public class ImageDownloader {
    
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
            
            inputStream = connection.getInputStream();
            outputStream = new ByteArrayOutputStream();
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
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
}
