package com.bot.jone.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.io.IOException;

@Slf4j
@Component
public class OneBotApiClient {

    @org.springframework.beans.factory.annotation.Autowired
    private com.bot.jone.ioutils.ImageCompressor imageCompressor;

    /**
     * 通过 WebSocket 发送文本消息
     */
    public void sendText(Session session, String msgType, Long targetId, String text) {
        JSONObject action = new JSONObject();
        action.put("action", "send_msg");
        
        JSONObject params = new JSONObject();
        params.put(msgType.equals("group") ? "group_id" : "user_id", targetId);
        
        // 封装消息段
        JSONArray msgArray = new JSONArray();
        msgArray.add(new JSONObject()
            .fluentPut("type", "text")
            .fluentPut("data", new JSONObject().fluentPut("text", text)));
            
        params.put("message", msgArray);
        action.put("params", params);
        
        // 统一发送
        try {
            session.getBasicRemote().sendText(action.toJSONString());
        } catch (IOException e) {
            log.error("发送消息失败", e);
        }
    }
    
    /**
     * 通过 WebSocket 发送图片消息（发送本地文件路径）
     * 发送前自动压缩大图片，避免 LLOneBot 超时
     * @param session WebSocket 会话
     * @param msgType 消息类型: "group" 或 "private"
     * @param targetId 目标ID（群号或用户ID）
     * @param imagePath 图片文件路径
     */
    public void sendImage(Session session, String msgType, Long targetId, String imagePath) {
        // 发送前自动压缩大图片
        String effectivePath = imagePath;
        if (imageCompressor != null) {
            effectivePath = imageCompressor.compressIfNeeded(imagePath);
            if (!effectivePath.equals(imagePath)) {
                log.info("发送图片: 已压缩 ({} -> {})", imagePath, effectivePath);
            }
        }

        JSONObject action = new JSONObject();
        action.put("action", "send_msg");
        
        JSONObject params = new JSONObject();
        params.put(msgType.equals("group") ? "group_id" : "user_id", targetId);
        
        // 封装图片消息段
        JSONArray msgArray = new JSONArray();
        msgArray.add(new JSONObject()
            .fluentPut("type", "image")
            .fluentPut("data", new JSONObject().fluentPut("file", effectivePath)));
            
        params.put("message", msgArray);
        action.put("params", params);
        
        try {
            String jsonPayload = action.toJSONString();
            session.getBasicRemote().sendText(jsonPayload);
            log.info("发送图片: {}, payload: {}", effectivePath, jsonPayload.length() > 200 ? jsonPayload.substring(0, 200) + "..." : jsonPayload);
        } catch (IOException e) {
            log.error("发送图片失败: {}", effectivePath, e);
        }
    }

    /**
     * 通过 WebSocket 发送图片消息（直接发送 URL）
     * @param session WebSocket 会话
     * @param msgType 消息类型: "group" 或 "private"
     * @param targetId 目标ID（群号或用户ID）
     * @param imageUrl 图片 URL
     */
    public void sendImageUrl(Session session, String msgType, Long targetId, String imageUrl) {
        JSONObject action = new JSONObject();
        action.put("action", "send_msg");

        JSONObject params = new JSONObject();
        params.put(msgType.equals("group") ? "group_id" : "user_id", targetId);

        // 封装图片消息段，使用 URL 方式发送
        JSONArray msgArray = new JSONArray();
        msgArray.add(new JSONObject()
            .fluentPut("type", "image")
            .fluentPut("data", new JSONObject().fluentPut("file", imageUrl)));

        params.put("message", msgArray);
        action.put("params", params);

        try {
            String jsonPayload = action.toJSONString();
            session.getBasicRemote().sendText(jsonPayload);
            log.info("发送图片URL: {}, payload: {}", imageUrl, jsonPayload.length() > 200 ? jsonPayload.substring(0, 200) + "..." : jsonPayload);
        } catch (IOException e) {
            log.error("发送图片URL失败: {}", imageUrl, e);
        }
    }
    
    /**
     * 群聊戳一戳（通过 WebSocket 发送 action）
     * @param session WebSocket 会话
     * @param groupId 群号
     * @param userId 要戳的用户ID
     */
    public void groupPoke(Session session, Long groupId, Long userId) {
        JSONObject action = new JSONObject();
        action.put("action", "group_poke");
        
        JSONObject params = new JSONObject();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        action.put("params", params);
        
        try {
            session.getBasicRemote().sendText(action.toJSONString());
            log.info("戳一戳请求已发送: 群={}, 用户={}", groupId, userId);
        } catch (IOException e) {
            log.error("戳一戳失败: 群={}, 用户={}", groupId, userId, e);
        }
    }
    
    /**
     * 获取消息详情（扩展 API，需要客户端支持）
     * @param session WebSocket 会话
     * @param messageId 消息 ID
     * @return 消息的 JSONObject，失败返回 null
     */
    public JSONObject getMessage(Session session, Integer messageId) {
        JSONObject action = new JSONObject();
        action.put("action", "get_msg");
        
        JSONObject params = new JSONObject();
        params.put("message_id", messageId);
        action.put("params", params);
        
        try {
            session.getBasicRemote().sendText(action.toJSONString());
            log.info("获取消息请求已发送: message_id={}", messageId);
            // 注意：这是异步请求，响应会通过 WebSocket 返回
            // 实际项目中需要使用 echo 字段匹配请求和响应
            return null; // 暂时返回 null，需要改进为同步等待响应
        } catch (IOException e) {
            log.error("获取消息失败: message_id={}", messageId, e);
            return null;
        }
    }
}