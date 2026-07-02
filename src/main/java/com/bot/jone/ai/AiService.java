package com.bot.jone.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class AiService {

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private PromptManager promptManager;

    private static final int TIMEOUT = 60_000; // 60秒超时

    /**
     * 发送对话请求
     * @param userId 用户ID（用于区分不同用户的历史）
     * @param userMessage 用户消息
     * @return AI回复文本，失败返回null
     */
    public String chat(Long userId, String userMessage) {
        try {
            // 构建请求
            URL url = new URL(aiConfig.getApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + aiConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", aiConfig.getModel());

            JSONArray messages = new JSONArray();

            // 系统提示词
            String systemPrompt = promptManager.getSystemPrompt();
            messages.add(new JSONObject()
                .fluentPut("role", "system")
                .fluentPut("content", systemPrompt));

            // 用户消息
            messages.add(new JSONObject()
                .fluentPut("role", "user")
                .fluentPut("content", userMessage));

            requestBody.put("messages", messages);
            requestBody.put("max_tokens", aiConfig.getMaxTokens());
            requestBody.put("temperature", aiConfig.getTemperature());

            log.info("[AI] 请求URL: {}", aiConfig.getApiUrl());
            log.info("[AI] 请求模型: {}", aiConfig.getModel());
            log.info("[AI] 用户消息: {}", userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage);
            log.debug("[AI] 请求体: {}", requestBody.toJSONString());

            // 发送请求
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toJSONString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            StringBuilder response = new StringBuilder();

            java.io.InputStream inputStream = responseCode == 200 
                ? conn.getInputStream() 
                : conn.getErrorStream();
            
            if (inputStream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }

            log.debug("[AI] 响应码: {}, 内容: {}", responseCode, response);

            if (responseCode != 200) {
                log.error("[AI] 请求失败: HTTP {}", responseCode);
                log.error("[AI] 错误信息: {}", response);
                return "AI服务暂时不可用，请稍后再试~";
            }

            // 解析响应
            JSONObject jsonResponse = JSONObject.parseObject(response.toString());
            JSONArray choices = jsonResponse.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[AI] 响应中没有choices");
                return "AI没有生成回复~";
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject message = firstChoice.getJSONObject("message");
            String content = message != null ? message.getString("content") : null;

            if (content == null || content.isEmpty()) {
                log.warn("[AI] 响应内容为空");
                return "AI没有生成回复~";
            }

            log.info("[AI] 回复: {}", content.length() > 100
                ? content.substring(0, 100) + "..."
                : content);
            return content.trim();

        } catch (Exception e) {
            log.error("[AI] 调用失败", e);
            return "AI服务暂时不可用，请稍后再试~";
        }
    }
}
