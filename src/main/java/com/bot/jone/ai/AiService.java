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

    @Autowired
    private ConversationManager conversationManager;

    @Autowired
    private LongTermMemoryManager longTermMemoryManager;

    /**
     * 发送对话请求
     * @param userId 用户ID（用于区分不同用户的历史）
     * @param messageType 消息类型：group 或 private
     * @param userMessage 用户消息
     * @param speaker 发言者昵称（群聊时使用）
     * @return AI回复文本，失败返回null
     */
    public String chat(Long userId, String messageType, Long senderId, String userMessage, String speaker) {
        try {
            log.info("{}用户【{}/{}】:{}",messageType,speaker,senderId,userMessage);
            // 构建请求
            URL url = new URL(aiConfig.getApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + aiConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(aiConfig.getTimeoutMillis());
            conn.setReadTimeout(aiConfig.getTimeoutMillis());

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", aiConfig.getModel());

            JSONArray messages = new JSONArray();

            // 系统提示词 + 长期记忆上下文
            StringBuilder systemPromptBuilder = new StringBuilder();
            systemPromptBuilder.append(promptManager.getSystemPrompt());

            if ("group".equals(messageType)) {
                String groupContext = longTermMemoryManager.getGroupContext(userId);
                if (!groupContext.isEmpty()) {
                    systemPromptBuilder.append("\n\n").append(groupContext);
                }
            } else {
                String userContext = longTermMemoryManager.getUserContext(userId);
                if (!userContext.isEmpty()) {
                    systemPromptBuilder.append("\n\n").append(userContext);
                }
            }

            // 注入当前发言者信息（实时上下文）
            // 只把昵称作为"已知背景"注入，不把 QQ 号/群号等标识符写进提示词，
            // 避免模型像读身份证一样复述，也防止在群聊里泄露隐私。
            if (speaker != null && !speaker.isEmpty()) {
                StringBuilder speakerInfo = new StringBuilder();
                speakerInfo.append("\n\n# 你正在和 ").append(speaker).append(" 对话");
                speakerInfo.append("\n（这是你本来就认识的人，正常聊天即可；不要特意提起或复述对方的QQ号、ID、群号等标识，也不要像刚加上好友一样表演「记下你的资料」）");
                if ("group".equals(messageType)) {
                    speakerInfo.append("\n# 当前是群聊场景");
                } else {
                    speakerInfo.append("\n# 当前是私聊场景");
                }
                systemPromptBuilder.append(speakerInfo);
            }

            messages.add(new JSONObject()
                .fluentPut("role", "system")
                .fluentPut("content", systemPromptBuilder.toString()));

            // 加载历史消息（短期记忆）
            JSONArray history = conversationManager.getHistory(userId, messageType);
            if (!history.isEmpty()) {
                messages.addAll(history);
                log.debug("[AI] 加载历史消息 {} 条", history.size());
            }

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
            
            String trimmedContent = content.trim();
            
            // 保存到短期记忆
            conversationManager.addMessage(userId, messageType, "user", userMessage, speaker);
            conversationManager.addMessage(userId, messageType, "assistant", trimmedContent, "ichikaBot");
            
            // 检查是否需要触发长期记忆摘要
            JSONArray fullHistory = conversationManager.getHistory(userId, messageType);
            longTermMemoryManager.triggerSummary(userId, messageType, senderId, speaker, fullHistory);
            
            return trimmedContent;

        } catch (Exception e) {
            log.error("[AI] 调用失败", e);
            return "AI服务暂时不可用，请稍后再试~";
        }
    }

    /**
     * 调用AI进行摘要（不保存记忆，纯工具调用）
     */
    public String summarize(String prompt) {
        try {
            URL url = new URL(aiConfig.getApiUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + aiConfig.getApiKey());
            conn.setDoOutput(true);
            conn.setConnectTimeout(aiConfig.getTimeoutMillis());
            conn.setReadTimeout(aiConfig.getTimeoutMillis());

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", aiConfig.getModel());

            JSONArray messages = new JSONArray();
            messages.add(new JSONObject()
                .fluentPut("role", "user")
                .fluentPut("content", prompt));

            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 512);
            requestBody.put("temperature", 0.3);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toJSONString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

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

            if (responseCode != 200) {
                log.error("[AI] 摘要请求失败: HTTP {}", responseCode);
                return null;
            }

            JSONObject jsonResponse = JSONObject.parseObject(response.toString());
            JSONArray choices = jsonResponse.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject message = firstChoice.getJSONObject("message");
            String content = message != null ? message.getString("content") : null;

            return content != null ? content.trim() : null;

        } catch (Exception e) {
            log.error("[AI] 摘要调用失败", e);
            return null;
        }
    }
}
