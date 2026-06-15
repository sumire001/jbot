package com.bot.jone.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class AnimeImageService {

    @Value("${bot.anime-api.url:https://api.lolicon.app/setu/v2}")
    private String apiUrl;

    /**
     * 搜索二次元图片
     * @param keyword 搜索关键词（如"猫"、"碧蓝航线"）
     * @return 图片 URL，如果失败返回 null
     */
    public String searchImage(String keyword) {
        try {
            // 构建请求 URL
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String requestUrl = apiUrl + "?tag=" + encodedKeyword + "&num=1";

            log.info("请求二次元图片 API: {}", requestUrl);

            // 发送请求
            URL url = new URL(requestUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // 读取响应
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String responseStr = response.toString();
            log.debug("API 响应内容: {}", responseStr);

            // 解析 JSON
            JSONObject json = JSONObject.parseObject(responseStr);
            String error = json.getString("error");

            // Lolicon API 用 error 字段表示状态，空字符串表示成功
            if (error != null && !error.isEmpty()) {
                log.warn("API 返回错误: error={}, 响应: {}", error, responseStr.length() > 200 ? responseStr.substring(0, 200) + "..." : responseStr);
                return null;
            }

            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.warn("未找到匹配的图片");
                return null;
            }

            JSONObject image = data.getJSONObject(0);
            // Lolicon API 返回的 URL 在 urls.original 字段
            JSONObject urls = image.getJSONObject("urls");
            String imageUrl = urls != null ? urls.getString("original") : null;

            log.info("获取到图片 URL: {}", imageUrl);
            return imageUrl;

        } catch (Exception e) {
            log.error("调用二次元图片 API 失败", e);
            return null;
        }
    }

    /**
     * 搜索二次元图片（带附加信息）
     * @param keyword 搜索关键词
     * @return ImageResult 包含图片URL和附加信息，失败返回 null
     */
    public ImageResult searchImageWithInfo(String keyword) {
        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String requestUrl = apiUrl + "?tag=" + encodedKeyword + "&num=1";

            log.info("[图片API] 请求URL: {}", requestUrl);

            URL url = new URL(requestUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            int responseCode = conn.getResponseCode();
            log.info("[图片API] HTTP响应码: {}", responseCode);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String responseStr = response.toString();
            log.info("[图片API] 响应内容: {}", responseStr.length() > 300 ? responseStr.substring(0, 300) + "..." : responseStr);

            JSONObject json = JSONObject.parseObject(responseStr);
            String error = json.getString("error");

            // Lolicon API 用 error 字段表示状态，空字符串表示成功
            if (error != null && !error.isEmpty()) {
                log.warn("[图片API] API返回错误: error={}", error);
                return null;
            }

            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.warn("[图片API] 未找到匹配的图片, data为空");
                return null;
            }

            JSONObject image = data.getJSONObject(0);
            // Lolicon API 返回的 URL 在 urls.original 字段
            JSONObject urls = image.getJSONObject("urls");
            String imageUrl = urls != null ? urls.getString("original") : null;
            String title = image.getString("title");
            String author = image.getString("author");
            Integer pid = image.getInteger("pid");

            log.info("[图片API] 获取成功: url={}, title={}, author={}", imageUrl, title, author);

            return new ImageResult(imageUrl, title, author, pid);

        } catch (Exception e) {
            log.error("[图片API] 调用失败", e);
            return null;
        }
    }

    /**
     * 图片搜索结果
     */
    @lombok.Data
    public static class ImageResult {
        private String url;
        private String title;
        private String author;
        private Integer pid;

        public ImageResult(String url, String title, String author, Integer pid) {
            this.url = url;
            this.title = title;
            this.author = author;
            this.pid = pid;
        }
    }
}
