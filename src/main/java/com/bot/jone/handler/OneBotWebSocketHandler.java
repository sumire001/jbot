package com.bot.jone.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.client.OneBotApiClient;
import com.bot.jone.ioutils.MemeManager;
import com.bot.jone.model.OneBotEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import javax.websocket.Session;

import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;
import java.io.File;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
@ServerEndpoint("/ws")
public class OneBotWebSocketHandler {

    private static final CopyOnWriteArraySet<Session> SESSIONS = new CopyOnWriteArraySet<>();
    
    // 使用静态变量保存 ApplicationContext
    private static ApplicationContext applicationContext;
    
    @Autowired
    public void setApplicationContext(ApplicationContext context) {
        OneBotWebSocketHandler.applicationContext = context;
    }
    
    /**
     * 获取 MemeManager Bean
     */
    private static MemeManager getMemeManager() {
        return applicationContext.getBean(MemeManager.class);
    }
    
    /**
     * 获取 OneBotApiClient Bean
     */
    private static OneBotApiClient getApiClient() {
        return applicationContext.getBean(OneBotApiClient.class);
    }
    
    /**
     * 获取 MessageCacheManager Bean
     */
    private static MessageCacheManager getMessageCacheManager() {
        return applicationContext.getBean(MessageCacheManager.class);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
//        log.info("📩 收到消息: {}", message);

        try {
            // 先缓存消息（用于引用场景）
            JSONObject eventJson = JSON.parseObject(message);
            String postType = eventJson.getString("post_type");
            if ("message".equals(postType)) {
                MessageCacheManager cacheManager = getMessageCacheManager();
                cacheManager.cacheMessage(eventJson);
            }
            
            OneBotEvent event = JSON.parseObject(message, OneBotEvent.class);
            
            // 根据 post_type 分发到不同的处理器
            if ("message".equals(postType)) {
                // 消息事件：交给 CommandHandler 处理
                CommandHandler commandHandler = applicationContext.getBean(CommandHandler.class);
                commandHandler.handle(event, session);
            } else if ("notice".equals(postType)) {
                // 通知事件：处理戳一戳等
                handleNoticeEvent(event, session);
            } else if ("request".equals(postType)) {
                // 请求事件：处理好友申请、群邀请等
                log.info("收到请求事件: type={}, user={}", event.getRequestType(), event.getUserId());
                // TODO: 实现请求处理逻辑
            }
        } catch (Exception e) {
            log.error("消息处理异常", e);
        }
    }
    
    /**
     * 处理通知事件（如戳一戳）
     */
    private void handleNoticeEvent(OneBotEvent event, Session session) {
        String noticeType = event.getNoticeType();
        String subType = event.getSubType();
        
        if ("notify".equals(noticeType) && "poke".equals(subType)) {
            // 戳一戳通知
            Long groupId = event.getGroupId();
            Long userId = event.getUserId();       // 戳人者
            Long targetId = event.getTargetId();   // 被戳者
            
            log.info("收到戳一戳: 群={}, 用户={} 戳了 目标={}", groupId, userId, targetId);
            
            // 如果戳的是机器人，随机发送 happy 表情
            if (targetId != null && targetId.equals(event.getSelfId())) {
                log.info("机器人被戳，准备发送随机表情...");
                handlePokeWithRandomMeme(event, session);
            }
        }
    }
    
    /**
     * 处理戳一戳并随机发送 happy 表情
     */
    private void handlePokeWithRandomMeme(OneBotEvent event, Session session) {
        MemeManager memeManager = getMemeManager();
        OneBotApiClient apiClient = getApiClient();
        
        // 获取随机 happy 表情
        File randomMeme = memeManager.getRandomHappyMeme();
        if (randomMeme == null) {
            log.warn("无法获取 happy 表情");
            return;
        }
        
        // 获取图片绝对路径
        String imagePath = randomMeme.getAbsolutePath();
        log.info("选中表情: {}", imagePath);
        
        // 根据消息类型发送到群聊或私聊
        String msgType = event.getMessageType() != null ? event.getMessageType() : "group";
        Long targetId = "group".equals(msgType) ? event.getGroupId() : event.getUserId();
        
        if (targetId != null) {
            apiClient.sendImage(session, msgType, targetId, imagePath);
        }
    }
}