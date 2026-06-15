package com.bot.jone.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.ioutils.ImageDownloader;
import com.bot.jone.ioutils.MemeManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    
    @Autowired
    private ImageDownloader imageDownloader;
    
    @Autowired
    private MemeManager memeManager;
    
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
        
        // 【关键】收到图片消息时立即下载保存，避免链接过期
        downloadAndSaveImage(eventJson, messageArray);
        
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
    
    /**
     * 下载并保存图片到本地（防止链接过期）
     * @param eventJson 事件 JSON
     * @param messageArray 消息数组
     */
    private void downloadAndSaveImage(JSONObject eventJson, JSONArray messageArray) {
        try {
            // 提取图片 URL
            String imageUrl = extractImageUrl(messageArray);
            if (imageUrl == null || imageUrl.isEmpty()) {
                return;
            }
            
            // 获取发送者信息
            Long userId = eventJson.getLong("user_id");
            if (userId == null) {
                userId = 0L; // 匿名用户
            }
            
            // 提取文件扩展名
            String extension = imageDownloader.extractExtension(imageUrl);
            
            // 生成文件名（用 message_id 确保唯一性，避免按 userId 查找时错图）
            Integer messageId = eventJson.getInteger("message_id");
            String fileName = "auto_msg_" + (messageId != null ? messageId : userId);
            
            // 下载图片
            byte[] imageData = imageDownloader.downloadImage(imageUrl);
            if (imageData == null || imageData.length == 0) {
                log.warn("下载图片失败: {}", imageUrl);
                return;
            }
            
            // 保存到temp目录（临时保存）
            java.io.File savedFile = memeManager.saveToTemp(imageData, fileName, extension);
                        
            if (savedFile != null) {
                log.info("自动保存图片到temp成功: {} -> {}", imageUrl, savedFile.getName());
            }
        } catch (Exception e) {
            log.error("自动保存图片异常", e);
        }
    }
    
    /**
     * 从消息数组中提取图片 URL
     * @param messageArray 消息数组
     * @return 图片 URL，如果没有图片则返回 null
     */
    private String extractImageUrl(JSONArray messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return null;
        }
        
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;
            
            String type = segment.getString("type");
            if ("image".equals(type)) {
                JSONObject data = segment.getJSONObject("data");
                if (data != null) {
                    // 优先使用 url 字段
                    String url = data.getString("url");
                    if (url != null && !url.isEmpty()) {
                        return url;
                    }
                    // 其次使用 file 字段
                    String file = data.getString("file");
                    if (file != null && !file.isEmpty()) {
                        return file;
                    }
                }
            }
        }
        
        return null;
    }
}
