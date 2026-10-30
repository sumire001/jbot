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
import javax.websocket.OnOpen;
import javax.websocket.OnMessage;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.CloseReason;
import javax.websocket.server.ServerEndpoint;
import java.io.File;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@ServerEndpoint("/ws")
public class OneBotWebSocketHandler {

    private static final CopyOnWriteArraySet<Session> SESSIONS = new CopyOnWriteArraySet<>();

    /**
     * 消息处理线程池（单线程，保证消息顺序）。
     * 关键：CommandHandler.handle() 内部可能调用 get_forward_msg 并同步等待回包，
     * 若在 WebSocket 读线程上直接阻塞，会卡住后续帧（含回包本身）的接收 → 死锁。
     * 故将消息处理搬到此独立线程，释放读线程以接收 API 回包；单线程则维持处理顺序。
     */
    private static final ExecutorService MESSAGE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jone-msg-handler");
        t.setDaemon(true);
        return t;
    });

    /**
     * 获取所有活跃的WebSocket会话（供外部访问）
     */
    public static CopyOnWriteArraySet<Session> getSessions() {
        return SESSIONS;
    }

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
    
    @OnOpen
    public void onOpen(Session session) {
        // 调大接收缓冲上限：Tomcat(内嵌 WebSocket)默认仅 8192 字节(8KB)，
        // 群聊长文本 / 带 CQ 码 / 引用回复的消息常超此限，会被以 TOO_BIG(1009) 直接断开连接，
        // 表现为“一发长文本 websocket 就断”。提到 10MB 以容纳长消息。
        session.setMaxTextMessageBufferSize(10 * 1024 * 1024);
        session.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        SESSIONS.add(session);
        log.info("✅ WebSocket 连接建立 - Session ID: {}, 远程地址: {}", 
                 session.getId(), session.getRequestURI());
        log.info("当前在线连接数: {}", SESSIONS.size());
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        SESSIONS.remove(session);
        log.info("❌ WebSocket 连接断开 - Session ID: {}, 原因: {}", 
                 session.getId(), closeReason);
        log.info("当前在线连接数: {}", SESSIONS.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        // 对端(云端 OneBot 客户端)异常断开/网络抖动会触发 EOFException，并被包装为
        // WebSocket 1006 异常关闭码 —— 这是正常的网络层事件，并非业务异常。
        // 关键：若不配置 @OnError，Tomcat 的 PojoEndpointBase 会打印整页 ERROR 堆栈
        // ("No error handling configured ...") 噪音；此处降级为可控日志。
        String sessionId = (session != null) ? session.getId() : "?";
        if (throwable instanceof java.io.EOFException) {
            log.info("⚠️ WebSocket 连接被对端异常关闭(网络中断/客户端重启) - Session ID: {}, 1006", sessionId);
        } else {
            log.warn("⚠️ WebSocket 连接错误 - Session ID: {}, 原因: {}",
                    sessionId, throwable.getMessage(), throwable);
        }
        if (session != null) {
            SESSIONS.remove(session);
        }
    }
    
    
    @OnMessage
    public void onMessage(String message, Session session) {
// log.info("📩 收到消息: {}", message);

        try {
            // 先缓存消息（用于引用场景）
            JSONObject eventJson = JSON.parseObject(message);
            String postType = eventJson.getString("post_type");
            // API 响应（如 get_forward_msg 的回包）没有 post_type，但带有 echo 字段，
            // 需交给 ApiClient 按 echo 唤醒等待中的请求；不处理则会被静默丢弃，
            // 导致 get_forward_msg 永远拿不到结果。其余无 post_type 也无 echo 的消息直接忽略。
            if (postType == null || postType.isEmpty()) {
                if (eventJson.getString("echo") != null) {
                    getApiClient().completePendingResponse(eventJson);
                }
                return;
            }
            if ("message".equals(postType)) {
                MessageCacheManager cacheManager = getMessageCacheManager();
                cacheManager.cacheMessage(eventJson);
            }
            
            OneBotEvent event = JSON.parseObject(message, OneBotEvent.class);
            
            // 根据 post_type 分发到不同的处理器
            if ("message".equals(postType)) {
                // 消息事件：异步提交到独立线程处理。
                // 关键：handle() 内可能调用 get_forward_msg 并同步等待回包，
                // 若直接在 WebSocket 读线程上阻塞，会卡住后续帧（含回包本身）的接收 → 死锁。
                // 交由单线程执行器处理，既释放读线程、又保证消息顺序。
                final OneBotEvent finalEvent = event;
                MESSAGE_EXECUTOR.submit(() -> {
                    try {
                        applicationContext.getBean(CommandHandler.class).handle(finalEvent, session);
                    } catch (Exception e) {
                        log.error("消息处理异常(异步线程)", e);
                    }
                });
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
        
        // 跨机器部署：云服务器上的 QQ 框架(Linux)无法读取本机(Windows)的绝对路径，
        // 故以 base64:// 内嵌原图字节发送（保留 GIF 动图/透明通道），不再发送本地路径。
        log.info("选中表情: {}", randomMeme.getName());

        // 根据消息类型发送到群聊或私聊
        String msgType = event.getMessageType() != null ? event.getMessageType() : "group";
        Long targetId = "group".equals(msgType) ? event.getGroupId() : event.getUserId();

        if (targetId != null) {
            apiClient.sendImageBase64Raw(session, msgType, targetId, randomMeme);
        }
    }
}