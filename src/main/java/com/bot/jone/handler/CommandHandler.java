package com.bot.jone.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.client.OneBotApiClient;
import com.bot.jone.ioutils.ImageDownloader;
import com.bot.jone.ioutils.MemeManager;
import com.bot.jone.model.OneBotEvent;
import com.bot.jone.permission.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.Session;

@Component
@Slf4j
public class CommandHandler {
    
    @Autowired
    private OneBotApiClient apiClient;
    
    @Autowired
    private MemeManager memeManager;
    
    @Autowired
    private ImageDownloader imageDownloader;
    
    @Autowired
    private MessageCacheManager messageCacheManager;
    
    @Autowired
    private PermissionService permissionService;
    
    public void handle(OneBotEvent event, Session session) {
        // 提取消息内容（支持多种类型）
        String content = extractMessageContent(event.getMessage());
        //打印聊天日志
        log.info("[{}]{}/{}:{}", event.getGroupId(), event.getUserId(), event.getSender().getNickname(), content);
        
        if ("/测试".equals(content)) {
            if ("group".equals(event.getMessageType())) {
                apiClient.sendText(session, "group", event.getGroupId(), "你好呀！我是测试用的 Bot");
            } else {
                apiClient.sendText(session, "private", event.getUserId(), "你好呀！我是测试用的 Bot");
            }
        } else if (content.contains("/测试戳一戳")) {
            // 处理戳一戳命令
            handlePokeCommand(event, session);
        } else if (content.contains("偷一下")) {
            // 处理偷表情包命令
            handleStealMeme(event, session);
        }
        // 以后加新功能，直接在这里加 else if，或者使用策略模式/注解路由
    }
    
    /**
     * 从消息数组中提取消息内容（支持多种类型）
     * @param messageArray 消息数组
     * @return 格式化的消息内容字符串
     */
    private String extractMessageContent(JSONArray messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;
            
            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");
            
            if (data == null) continue;
            
            switch (type) {
                case "text":
                    // 文本消息
                    sb.append(data.getString("text"));
                    break;
                    
                case "image":
                    // 图片消息
                    String url = data.getString("url");
                    String file = data.getString("file");
                    if (url != null && !url.isEmpty()) {
                        sb.append("[图片]").append(url);
                    } else if (file != null && !file.isEmpty()) {
                        sb.append("[图片:").append(file).append("]");
                    } else {
                        sb.append("[图片]");
                    }
                    break;
                    
                case "face":
                    // QQ表情
                    Integer faceId = data.getInteger("id");
                    sb.append("[表情:").append(faceId).append("]");
                    break;
                    
                case "at":
                    // @某人
                    String qq = data.getString("qq");
                    sb.append("[@").append(qq).append("]");
                    break;
                    
                case "record":
                    // 语音
                    sb.append("[语音]");
                    break;
                    
                case "video":
                    // 视频
                    sb.append("[视频]");
                    break;
                    
                case "share":
                    // 分享链接
                    String title = data.getString("title");
                    sb.append("[分享:").append(title != null ? title : "链接").append("]");
                    break;
                    
                default:
                    // 其他类型，记录类型名
                    sb.append("[").append(type).append("]");
                    break;
            }
        }
        return sb.toString();
    }
    
    /**
     * 处理戳一戳命令
     */
    private void handlePokeCommand(OneBotEvent event, Session session) {
        if (!"group".equals(event.getMessageType())) {
            apiClient.sendText(session, "private", event.getUserId(), "戳一戳仅支持群聊使用");
            return;
        }
        
        Long groupId = event.getGroupId();
        Long userId = event.getUserId(); // 戳发消息的人
        
        // 通过 WebSocket 发送 action 执行戳一戳
        apiClient.groupPoke(session, groupId, userId);
        
        // 可选：发送确认消息
        // apiClient.sendText(session, "group", groupId, "已戳 " + event.getSender().getNickname());
    }
    
    /**
     * 处理偷表情包命令（支持引用消息）
     */
    private void handleStealMeme(OneBotEvent event, Session session) {
        // 权限检查：只有管理员可以偷图
        Long userId = event.getUserId();
        if (!permissionService.isAdmin(userId)) {
            apiClient.sendText(session, "group", event.getGroupId(), 
                "使用该功能需要管理员权限");
            log.warn("非管理员尝试偷图: user={}", userId);
            return;
        }
        
        String imageUrl = null;
        
        // 方式1：检查是否有引用消息
        Integer repliedMessageId = extractRepliedMessageId(event.getMessage());
        if (repliedMessageId != null) {
            log.info("检测到引用消息 ID: {}", repliedMessageId);
            // 从缓存中获取被引用的消息
            JSONObject repliedMessage = messageCacheManager.getCachedMessage(repliedMessageId);
            
            if (repliedMessage == null) {
                apiClient.sendText(session, "group", event.getGroupId(), 
                    "未找到引用的消息（可能已过期或未被缓存）\n提示：机器人需要收到过该消息才能偷图");
                return;
            }
            
            // 从被引用的消息中提取图片 URL
            JSONArray repliedMessageArray = repliedMessage.getJSONArray("message");
            imageUrl = extractImageUrl(repliedMessageArray);
            
            if (imageUrl == null || imageUrl.isEmpty()) {
                apiClient.sendText(session, "group", event.getGroupId(), 
                    "引用的消息中没有图片哦~");
                return;
            }
            
            log.info("从引用消息中提取到图片 URL: {}", imageUrl);
        } else {
            // 方式2：直接从当前消息中提取图片 URL（电脑端支持）
            imageUrl = extractImageUrl(event.getMessage());
        }
        
        if (imageUrl == null || imageUrl.isEmpty()) {
            apiClient.sendText(session, "group", event.getGroupId(), 
                "没有找到图片哦~\n使用方法：\n1. 电脑端：发送图片+文字'偷一下'\n2. 手机端：引用图片消息后发送'偷一下'");
            return;
        }
        
        log.info("开始偷表情包: {}", imageUrl);
        
        // 下载图片
        byte[] imageData = imageDownloader.downloadImage(imageUrl);
        if (imageData == null || imageData.length == 0) {
            apiClient.sendText(session, "group", event.getGroupId(), "下载图片失败，请重试");
            return;
        }
        
        // 提取文件扩展名
        String extension = imageDownloader.extractExtension(imageUrl);
        
        // 生成文件名（使用用户 QQ 号作为标识）
        String fileName = "stolen_" + event.getUserId();
        
        // 保存到旯给木表情包分类
        java.io.File savedFile = memeManager.saveMeme(imageData, "旮旯给木表情包", fileName, extension);
        
        if (savedFile != null) {
            apiClient.sendText(session, "group", event.getGroupId(), 
                "偷图成功！");
            log.info("偷图成功: {} -> {}", imageUrl, savedFile.getAbsolutePath());
        } else {
            apiClient.sendText(session, "group", event.getGroupId(), "保存表情包失败，请重试");
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
    
    /**
     * 从消息数组中提取被引用的消息 ID
     * @param messageArray 消息数组
     * @return 被引用的消息 ID，如果没有引用则返回 null
     */
    private Integer extractRepliedMessageId(JSONArray messageArray) {
        if (messageArray == null || messageArray.isEmpty()) {
            return null;
        }
        
        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;
            
            String type = segment.getString("type");
            if ("reply".equals(type)) {
                JSONObject data = segment.getJSONObject("data");
                if (data != null) {
                    return data.getInteger("id");
                }
            }
        }
        
        return null;
    }
}