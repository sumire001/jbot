package com.bot.jone.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息缓存管理器
 * 用于缓存最近收到的消息，支持引用消息场景
 */
@Component
@Slf4j
public class MessageCacheManager {
    
    // 缓存最近的消息，key 为 message_id
    private final Map<Integer, JSONObject> messageCache = new ConcurrentHashMap<>();
    
    // 每个群最后一条图片消息
    private final Map<Long, JSONObject> lastImageMessageInGroup = new ConcurrentHashMap<>();
    
    // 最大缓存数量
    private static final int MAX_CACHE_SIZE = 100;
    
    /**
     * 缓存消息（只缓存包含图片的消息）
     * @param eventJson 完整的事件 JSON
     */
    public void cacheMessage(JSONObject eventJson) {
        Integer messageId = eventJson.getInteger("message_id");
        if (messageId == null) {
            return;
        }
        
        // 检查是否包含图片，只缓存图片消息
        JSONArray messageArray = eventJson.getJSONArray("message");
        if (messageArray == null || !containsImage(messageArray)) {
            return; // 非图片消息不缓存
        }
        
        // 添加到缓存
        messageCache.put(messageId, eventJson);
        
        // 限制缓存大小
        if (messageCache.size() > MAX_CACHE_SIZE) {
            // 删除最早的消息（简单策略：随机删除）
            messageCache.keySet().stream().findFirst().ifPresent(messageCache::remove);
        }
        
        // 更新群最后一条图片消息
        Long groupId = eventJson.getLong("group_id");
        if (groupId != null) {
            lastImageMessageInGroup.put(groupId, eventJson);
            log.debug("更新群 {} 的最后一条图片消息: {}", groupId, messageId);
        }
    }
    
    /**
     * 获取缓存的消息
     * @param messageId 消息 ID
     * @return 消息 JSON，如果不存在则返回 null
     */
    public JSONObject getCachedMessage(Integer messageId) {
        return messageCache.get(messageId);
    }
    
    /**
     * 获取群内最后一条图片消息
     * @param groupId 群号
     * @return 消息 JSON，如果不存在则返回 null
     */
    public JSONObject getLastImageMessage(Long groupId) {
        return lastImageMessageInGroup.get(groupId);
    }
    
    /**
     * 检查消息数组是否包含图片
     * @param messageArray 消息数组
     * @return 是否包含图片
     */
    private boolean containsImage(JSONArray messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment != null && "image".equals(segment.getString("type"))) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 清理缓存
     */
    public void clearCache() {
        messageCache.clear();
        lastImageMessageInGroup.clear();
        log.info("消息缓存已清理");
    }
}
