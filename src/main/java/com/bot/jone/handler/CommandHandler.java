package com.bot.jone.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.client.OneBotApiClient;
import com.bot.jone.config.ImagesConfig;
import com.bot.jone.ioutils.ImageCompressor;
import com.bot.jone.ioutils.ImageDownloader;
import com.bot.jone.ioutils.MemeManager;
import com.bot.jone.model.OneBotEvent;
import com.bot.jone.permission.PermissionService;
import com.bot.jone.service.AnimeImageService;
import com.bot.jone.ai.AiService;
import com.bot.jone.ai.AiConfig;
import com.bot.jone.ai.LongTermMemoryManager;
import com.bot.jone.ai.model.UserMemory;
import com.bot.jone.config.CommandConfig;
import com.bot.jone.config.GalleryConfig;
import com.bot.jone.service.GalleryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.io.File;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

    @Autowired
    private ImagesConfig imagesConfig;

    @Autowired
    private ImageCompressor imageCompressor;

    @Autowired
    private AiService aiService;

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private com.bot.jone.ai.ConversationManager conversationManager;

    @Autowired
    private LongTermMemoryManager longTermMemoryManager;

    @Autowired
    private CommandConfig commandConfig;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private GalleryConfig galleryConfig;

    private final Random random = new Random();

    // 上一次 /peek 的分类，供 /look 命令复用（重启后清空）
    private volatile String lastPeekCategory = null;

    public void handle(OneBotEvent event, Session session) {
        // 提取消息内容（支持多种类型）
        // trim 用于消除引用/at 段被跳过后的前导空格，避免前缀校验误判
        String content = extractMessageContent(event.getMessage()).trim();
        //打印聊天日志
        log.info("[{}]{}/{}:{}", event.getGroupId(), event.getUserId(), event.getSender().getNickname(), content);
        
        // 优先处理 @机器人（AI聊天，不需要命令前缀）
        if (isAtBot(event)) {
            if (!aiConfig.isEnabled()) {
                return;
            }
            String question = extractTextAfterAt(event);
            handleAiChat(event, session, question);
            return;
        }

        // 所有命令必须以配置的前缀开头
        if (!commandConfig.hasPrefix(content)) {
            return;
        }

        // 去掉前缀，得到命令体（可能含参数）
        String commandBody = commandConfig.stripPrefix(content);

        // 基于别名匹配命令
        if (commandConfig.matches(commandBody, "测试")) {
            handleTest(event, session);
        } else if (commandConfig.matches(commandBody, "戳一戳")) {
            handlePokeCommand(event, session);
        } else if (commandConfig.matches(commandBody, "偷一下")) {
            // 提取分类参数：去掉命令名后的部分
            String params = extractParams(commandBody, "偷一下");
            String[] categoryParams = extractCategoryParams(params);
            handleStealMeme(event, session, categoryParams[0], Boolean.parseBoolean(categoryParams[1]));
        } else if (isRandomImageCommand(commandBody)) {
            // 随机图特殊处理：格式为 {prefix}[随机|别名]{关键词}图
            Integer headLen = resolveRandomHead(commandBody);
            String keyword = commandBody.substring(headLen, commandBody.length() - 1).trim();
            handleRandomImageCommand(event, session, keyword);
        } else if (commandConfig.matches(commandBody, "enable")) {
            String params = extractParams(commandBody, "enable");
            handleToggleAi(event, session, commandConfig.getPrefix() + "enable " + params);
        } else if (commandConfig.matches(commandBody, "reset")) {
            handleResetConversation(event, session);
        } else if (commandConfig.matches(commandBody, "history")) {
            handleShowHistory(event, session);
        } else if (commandConfig.matches(commandBody, "summary")) {
            handleSummary(event, session);
        } else if (commandConfig.matches(commandBody, "profile")) {
            String params = extractParams(commandBody, "profile");
            handleProfile(event, session, commandConfig.getPrefix() + "profile " + params);
        } else if (commandConfig.matches(commandBody, "clear-memory")) {
            String params = extractParams(commandBody, "clear-memory");
            handleClearMemory(event, session, commandConfig.getPrefix() + "clear-memory " + params);
        } else if (commandConfig.matches(commandBody, "categories")) {
            handleCategories(event, session);
        } else if (commandConfig.matches(commandBody, "peek")) {
            handleGallery(event, session, commandBody);
        } else if (commandConfig.matches(commandBody, "look")) {
            handleLook(event, session, commandBody);
        }
    }

    /**
     * 从命令体中提取参数（去掉命令名部分）
     * @param commandBody 去掉前缀后的命令体
     * @param commandName 命令名
     * @return 命令名后面的参数部分（已 trim）
     */
    private String extractParams(String commandBody, String commandName) {
        for (String name : commandConfig.getMatchNames(commandName)) {
            if (commandBody.startsWith(name + " ")) {
                return commandBody.substring(name.length() + 1).trim();
            }
            if (commandBody.equals(name)) {
                return "";
            }
        }
        return "";
    }

    /**
     * 处理测试命令
     */
    private void handleTest(OneBotEvent event, Session session) {
        if ("group".equals(event.getMessageType())) {
            apiClient.sendText(session, "group", event.getGroupId(), "你好呀！我是测试用的 Bot");
        } else {
            apiClient.sendText(session, "private", event.getUserId(), "你好呀！我是测试用的 Bot");
        }
    }

    /**
     * 检查是否是随机图命令。
     * 支持两种形式：
     *   1) {prefix}随机{关键词}图      （默认/中文形式）
     *   2) {prefix}{别名}{关键词}图    （例如别名 random 时：/random猫猫图）
     * 关键词为「开头词」与结尾「图」之间的部分。
     */
    private boolean isRandomImageCommand(String commandBody) {
        return resolveRandomHead(commandBody) != null;
    }

    /**
     * 解析随机图命令的开头词（固定 "随机" 或该命令的任意别名），
     * 返回匹配到的开头词长度（用于截取关键词）；不是随机图命令则返回 null。
     */
    private Integer resolveRandomHead(String commandBody) {
        if (commandBody == null || !commandBody.endsWith("图")) {
            return null;
        }
        // 候选开头词：固定的 "随机" + 该命令配置的所有别名
        java.util.List<String> heads = new java.util.ArrayList<>();
        heads.add("随机");
        for (String alias : commandConfig.getMatchNames("随机图")) {
            // 命令名本身 "随机图" 含结尾的"图"，不是合法的开头词，跳过
            if (!"随机图".equals(alias)) {
                heads.add(alias);
            }
        }
        for (String h : heads) {
            // 至少保留 1 个结尾的"图"，开头词之后允许 0 个或多个关键词字符
            if (commandBody.startsWith(h) && commandBody.length() >= h.length() + 1) {
                return h.length();
            }
        }
        return null;
    }

    /**
     * 处理查看分类列表命令
     * 格式: {prefix}categories
     */
    private void handleCategories(OneBotEvent event, Session session) {
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        
        java.util.Map<String, java.util.List<java.io.File>> cache = memeManager.getMemeCache();
        
        if (cache.isEmpty()) {
            apiClient.sendText(session, event.getMessageType(), targetId, "暂无图片分类");
            return;
        }
        
        StringBuilder msg = new StringBuilder("图片分类列表：\n");
        int total = 0;
        for (java.util.Map.Entry<String, java.util.List<java.io.File>> entry : cache.entrySet()) {
            int count = entry.getValue().size();
            msg.append("• ").append(entry.getKey()).append("（").append(count).append("张）\n");
            total += count;
        }
        msg.append("共 ").append(cache.size()).append(" 个分类，").append(total).append(" 张图片");
        
        apiClient.sendText(session, event.getMessageType(), targetId, msg.toString());
        log.info("[分类列表] 用户 {} 查看了分类列表", event.getUserId());
    }

    /**
     * 画廊命令：/peek &lt;分类&gt; [页码]
     *  - 分类必填（如 memes / pjsk）
     *  - 页码可选，默认第 1 页；支持负数查倒数页（-1 = 最后一页，-2 = 倒数第 2 页）
     *  - 指定单图查看（第三个参数）暂未实现，给出提示
     * 生成通过外部 HTML→图片 API 完成，异步执行避免阻塞 WS 线程。
     */
    private void handleGallery(OneBotEvent event, Session session, String commandBody) {
        String msgType = event.getMessageType();
        Long targetId = "group".equals(msgType) ? event.getGroupId() : event.getUserId();
        String prefix = commandConfig.getPrefix();

        if (!galleryConfig.isEnabled()) {
            apiClient.sendText(session, msgType, targetId, "画廊功能未启用");
            return;
        }
        if (galleryConfig.getApiKey() == null || galleryConfig.getApiKey().isBlank()) {
            apiClient.sendText(session, msgType, targetId,
                    "画廊功能未配置 API Key，请在 config/application.yml 设置 bot.gallery.api-key");
            return;
        }

        String params = extractParams(commandBody, "peek").trim();
        String[] tokens = params.isEmpty() ? new String[0] : params.split("\\s+");

        if (tokens.length == 0) {
            apiClient.sendText(session, msgType, targetId,
                    "用法：" + prefix + "peek <分类> [页码]\n" +
                    "请选择分类；可用 /categories 查看分类");
            return;
        }

        String category = tokens[0];
        lastPeekCategory = category; // 记录，供 /look 复用

        File dir = new File(imagesConfig.getRootDir(), category);
        if (!dir.isDirectory()) {
            apiClient.sendText(session, msgType, targetId, "分类 [" + category + "] 不存在（可用 /categories 查看）");
            return;
        }
        File[] imgs = dir.listFiles((d, n) -> isGalleryImage(n));
        if (imgs == null || imgs.length == 0) {
            apiClient.sendText(session, msgType, targetId, "分类 [" + category + "] 里没有图片");
            return;
        }

        int perPage = galleryConfig.getCols() * galleryConfig.getRows();
        int total = (imgs.length + perPage - 1) / perPage;

        int page = 1;
        if (tokens.length >= 2) {
            try {
                int p = Integer.parseInt(tokens[1].trim());
                if (p < 0) {
                    p = total + p + 1; // 负数查倒数页：-1 -> 最后一页
                }
                page = p;
            } catch (NumberFormatException e) {
                apiClient.sendText(session, msgType, targetId,
                        "页码必须是数字，例如：" + prefix + "peek " + category + " 3  或  -1（倒数第1页）");
                return;
            }
        }
        if (page < 1 || page > total) {
            apiClient.sendText(session, msgType, targetId,
                    "页码超出范围，共 " + total + " 页（" + prefix + "peek " + category + " 1 ~ " + total + "）");
            return;
        }

        final int fPage = page;

        CompletableFuture.runAsync(() -> {
            try {
                File png = galleryService.generatePage(category, fPage);
                if (png == null || !png.exists()) {
                    apiClient.sendText(session, msgType, targetId,
                            "画廊生成失败（接口异常或额度不足，检查 bot.gallery.api-key）");
                    return;
                }
                // 以 base64 内嵌发送；客户端会在发送前重编码为受控 JPEG(q88) 缩小体积，
                // 避免超大帧冲垮跨机器 WebSocket。原图 PNG 缓存不动。
                boolean sent = apiClient.sendImageBase64(session, msgType, targetId, png);
                if (!sent) {
                    apiClient.sendText(session, msgType, targetId,
                            "画廊已生成，但图片发送超时/失败（可能是单帧过大）。可稍后重试，或调小 gallery.width / 每页张数。");
                    return;
                }
            } catch (Exception e) {
                log.error("[Gallery] 生成异常", e);
                apiClient.sendText(session, msgType, targetId, "画廊生成失败：" + e.getMessage());
            }
        });
    }

    /**
     * 单图查看命令：/look &lt;id&gt;
     *  - id 为图片文件名（可带或不带扩展名），如 1 / 1.jpg
     *  - 分类自动使用上一次 /peek 的分类（lastPeekCategory）
     *  - 直接读取本地原图以 base64 发送（受控 JPEG q88 缩小体积），不依赖 hcti
     */
    private void handleLook(OneBotEvent event, Session session, String commandBody) {
        String msgType = event.getMessageType();
        Long targetId = "group".equals(msgType) ? event.getGroupId() : event.getUserId();
        String prefix = commandConfig.getPrefix();

        if (lastPeekCategory == null) {
            apiClient.sendText(session, msgType, targetId, "请先 /peek 一个分类，再使用 /look <id>");
            return;
        }

        String params = extractParams(commandBody, "look").trim();
        if (params.isEmpty()) {
            apiClient.sendText(session, msgType, targetId,
                    "用法：" + prefix + "look <图片id>（id 为文件名，如 " + prefix + "look 1）");
            return;
        }

        File dir = new File(imagesConfig.getRootDir(), lastPeekCategory);
        if (!dir.isDirectory()) {
            apiClient.sendText(session, msgType, targetId, "上次查看的分类 [" + lastPeekCategory + "] 已不存在");
            return;
        }
        File[] imgs = dir.listFiles((d, n) -> isGalleryImage(n));
        if (imgs == null || imgs.length == 0) {
            apiClient.sendText(session, msgType, targetId, "分类 [" + lastPeekCategory + "] 里没有图片");
            return;
        }

        // 按文件名（去扩展名）或完整文件名匹配 id，大小写不敏感
        String id = params;
        File target = null;
        for (File f : imgs) {
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            String noExt = dot > 0 ? base.substring(0, dot) : base;
            if (noExt.equalsIgnoreCase(id) || base.equalsIgnoreCase(id)) {
                target = f;
                break;
            }
        }
        if (target == null) {
            apiClient.sendText(session, msgType, targetId,
                    "在分类 [" + lastPeekCategory + "] 中找不到 id 为 [" + id + "] 的图片；可先 " + prefix + "peek " + lastPeekCategory + " 查看编号");
            return;
        }

        // 单图查看：GIF 动图 / PNG 透明 / WebP / BMP 须保留原格式（重编码成 JPEG 会丢动图/透明），
        // 仅纯静态 JPG 才受控压缩以控制 base64 体积、避免冲垮跨机器 WebSocket。
        String lower = target.getName().toLowerCase();
        boolean keepRaw = lower.endsWith(".gif") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
        boolean sent = keepRaw
                ? apiClient.sendImageBase64Raw(session, msgType, targetId, target)
                : apiClient.sendImageBase64(session, msgType, targetId, target);
        if (!sent) {
            apiClient.sendText(session, msgType, targetId, "图片发送超时/失败（可能是单帧过大）");
        }
    }

    private static boolean isGalleryImage(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) {
            return false;
        }
        String ext = name.substring(i).toLowerCase();
        return Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tiff", ".tif").contains(ext);
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
                case "reply":
                    // 引用消息：仅携带被引用消息的 id，不纳入命令文本，
                    // 否则开头会出现 "[reply]" 导致命令前缀校验失败、整条命令被忽略
                    break;

                case "text":
                    // 文本消息
                    sb.append(data.getString("text"));
                    break;
                    
                case "image":
                    // 图片消息：不纳入命令文本（否则 "[图片]url" 会挡住命令前缀校验）。
                    // 图片 URL 由 handleStealMeme 通过 extractImageUrl(event.getMessage())
                    // 单独读取，与命令文本解析互不干扰。
                    break;
                    
                case "face":
                    // QQ表情
                    Integer faceId = data.getInteger("id");
                    sb.append("[表情:").append(faceId).append("]");
                    break;
                    
                case "at":
                    // @某人：仅作为元数据（如回复群友时自动带上 @群员），
                    // 不纳入命令文本，否则开头会出现 "[@qq]" 导致命令前缀校验失败、整条命令被忽略。
                    // 被 @ 的群员身份由事件本身携带，不影响命令解析。
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
     * 处理AI聊天命令
     * 格式: ai: 问题内容
     */
    private void handleAiChat(OneBotEvent event, Session session, String question) {
        if (question == null || question.trim().isEmpty()) {
            apiClient.sendText(session, event.getMessageType(),
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "请输入你的问题，例如：ai: 你好");
            return;
        }

        Long sessionId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        Long targetId = sessionId;

        log.info("[AI] 用户提问: {}", question);

        String speaker = resolveSpeaker(event);

        // 调用AI服务获取回复（传入sessionId用于区分群聊/私聊上下文）
        String reply = aiService.chat(sessionId, event.getMessageType(), event.getUserId(), question, speaker);

        if (reply != null && !reply.isEmpty()) {
            apiClient.sendText(session, event.getMessageType(), targetId, reply);
        } else {
            apiClient.sendText(session, event.getMessageType(), targetId, "AI暂时没有回复，请稍后再试~");
        }
    }

    /**
     * 解析发言者昵称（群聊优先群名片，私聊/无名片回退全局昵称）
     * 注意：群名片 card 在未设置时是空字符串""而非 null，必须按"非空"判断，
     * 否则会错误回退、导致昵称显示为未知。
     */
    private String resolveSpeaker(OneBotEvent event) {
        if (event.getSender() == null) {
            return null;
        }
        String card = event.getSender().getCard();
        String nickname = event.getSender().getNickname();
        if ("group".equals(event.getMessageType()) && card != null && !card.isEmpty()) {
            return card;
        }
        return nickname;
    }

    /**
     * 处理重置对话命令
     * 格式: /reset 或 /重置
     */
    private void handleResetConversation(OneBotEvent event, Session session) {
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        Long userId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        
        conversationManager.reset(userId, event.getMessageType());
        
        apiClient.sendText(session, event.getMessageType(), targetId, "哼，杂鱼终于想重新开始了？已重置对话~");
        log.info("[对话重置] 用户 {} 重置了 {} 对话", event.getUserId(), event.getMessageType());
    }

    /**
     * 处理查看对话历史命令
     * 格式: /history 或 /历史
     */
    private void handleShowHistory(OneBotEvent event, Session session) {
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        Long userId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        
        int count = conversationManager.getHistoryCount(userId, event.getMessageType());
        
        if (count == 0) {
            apiClient.sendText(session, event.getMessageType(), targetId, "切，还没有任何对话记录");
        } else {
            apiClient.sendText(session, event.getMessageType(), targetId, 
                String.format("当前会话共 %d 条消息记录", count));
        }
        log.info("[对话历史] 用户 {} 查看了 {} 对话历史，共 {} 条", 
            event.getUserId(), event.getMessageType(), count);
    }

    /**
     * 处理强制触发摘要命令（仅管理员）
     * 格式: /summary
     */
    private void handleSummary(OneBotEvent event, Session session) {
        if (!permissionService.isAdmin(event.getUserId())) {
            apiClient.sendText(session, event.getMessageType(), 
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "使用该功能需要管理员权限");
            return;
        }
        
        Long userId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        JSONArray history = conversationManager.getHistory(userId, event.getMessageType());
        
        if (history.size() < 4) {
            apiClient.sendText(session, event.getMessageType(), 
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "对话历史不足，需要至少4条消息");
            return;
        }
        
        String speaker = resolveSpeaker(event);
        
        longTermMemoryManager.triggerSummary(userId, event.getMessageType(), event.getUserId(), speaker, history);
        
        apiClient.sendText(session, event.getMessageType(), 
            "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
            "已触发摘要生成~");
        log.info("[摘要] 管理员 {} 强制触发了 {} 对话摘要", event.getUserId(), event.getMessageType());
    }

    /**
     * 处理查看用户画像命令（仅管理员）
     * 格式: /profile [QQ号] 或 /profile（查看自己）
     */
    private void handleProfile(OneBotEvent event, Session session, String content) {
        if (!permissionService.isAdmin(event.getUserId())) {
            apiClient.sendText(session, event.getMessageType(), 
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "使用该功能需要管理员权限");
            return;
        }
        
        // 去掉前缀和命令名，获取参数
        String param = commandConfig.stripPrefix(content);
        param = extractParams(param, "profile");
        Long targetQq;
        
        if (param.isEmpty()) {
            targetQq = event.getUserId();
        } else {
            try {
                targetQq = Long.parseLong(param);
            } catch (NumberFormatException e) {
                apiClient.sendText(session, event.getMessageType(), 
                    "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                    "QQ号格式错误");
                return;
            }
        }
        
        UserMemory user = longTermMemoryManager.getUserMemory(targetQq);
        
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        
        if (user == null || user.getTraits() == null || user.getTraits().isEmpty()) {
            apiClient.sendText(session, event.getMessageType(), targetId, 
                "还没有用户 " + targetQq + " 的画像数据");
        } else {
            String msg = "用户画像 [" + targetQq + "]\n";
            msg += "昵称: " + (user.getNickname() != null ? user.getNickname() : "未知") + "\n";
            msg += "特征: " + user.getTraits();
            apiClient.sendText(session, event.getMessageType(), targetId, msg);
        }
        
        log.info("[画像] 管理员 {} 查看了用户 {} 的画像", event.getUserId(), targetQq);
    }

    /**
     * 处理清空记忆命令（仅管理员）
     * 格式: /clear-memory [QQ号] 或 /clear-memory all（清空全部）
     */
    private void handleClearMemory(OneBotEvent event, Session session, String content) {
        if (!permissionService.isAdmin(event.getUserId())) {
            apiClient.sendText(session, event.getMessageType(), 
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "使用该功能需要管理员权限");
            return;
        }
        
        // 去掉前缀和命令名，获取参数
        String param = commandConfig.stripPrefix(content);
        param = extractParams(param, "clear-memory");
        
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        
        if ("all".equalsIgnoreCase(param)) {
            longTermMemoryManager.clearAllMemory();
            conversationManager.clearAll();
            apiClient.sendText(session, event.getMessageType(), targetId, "已清空全部记忆");
            log.info("[清空记忆] 管理员 {} 清空了全部记忆", event.getUserId());
        } else if (param.isEmpty()) {
            Long userId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
            conversationManager.reset(userId, event.getMessageType());
            if ("group".equals(event.getMessageType())) {
                longTermMemoryManager.clearGroupMemory(event.getGroupId());
            } else {
                longTermMemoryManager.clearUserMemory(event.getUserId());
            }
            apiClient.sendText(session, event.getMessageType(), targetId, "已清空当前会话记忆");
            log.info("[清空记忆] 管理员 {} 清空了 {} 会话记忆", event.getUserId(), event.getMessageType());
        } else {
            try {
                Long qq = Long.parseLong(param);
                longTermMemoryManager.clearUserMemory(qq);
                apiClient.sendText(session, event.getMessageType(), targetId, "已清空用户 " + qq + " 的画像");
                log.info("[清空记忆] 管理员 {} 清空了用户 {} 的画像", event.getUserId(), qq);
            } catch (NumberFormatException e) {
                apiClient.sendText(session, event.getMessageType(), targetId, 
                    "参数错误，可用: /clear-memory, /clear-memory all, /clear-memory [QQ号]");
            }
        }
    }

    /**
     * 处理随机二次元图片命令
     * 格式: "{prefix}随机XX图"，提取中间的XX作为搜索关键词
     * 优化流程: 下载 → 内存中压缩 → 存 pixiv_temp → 发送压缩图
     * @param keyword 搜索关键词（已从命令中提取）
     */
    private void handleRandomImageCommand(OneBotEvent event, Session session, String keyword) {
        // 提取关键词
        keyword = keyword.trim();

        if (keyword.isEmpty()) {
            apiClient.sendText(session, event.getMessageType(),
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "请输入搜索关键词，例如：" + commandConfig.getPrefix() + "随机猫猫图");
            return;
        }

        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();

        // 调用图片搜索服务
        AnimeImageService imageService = applicationContext.getBean(AnimeImageService.class);
        log.info("[随机图片] 关键词={}, 调用API搜索...", keyword);
        AnimeImageService.ImageResult result = imageService.searchImageWithInfo(keyword);
        log.info("[随机图片] API返回: result={}, url={}",
            result == null ? "null" : "OK",
            result == null ? "null" : result.getUrl());

        if (result == null) {
            apiClient.sendText(session, event.getMessageType(), targetId,
                "未找到 [" + keyword + "] 相关的图片");
            return;
        }

        // 下载图片到内存
        byte[] imageData = imageDownloader.downloadImage(result.getUrl());
        if (imageData == null || imageData.length == 0) {
            apiClient.sendText(session, event.getMessageType(), targetId, "图片下载失败");
            return;
        }

        log.info("[随机图片] 下载完成: {}KB, 开始压缩并保存...", imageData.length / 1024);

        // 在内存中直接压缩并保存到 pixiv_temp（不存原图，不走 temp 目录）
        String compressedPath = imageCompressor.compressBytesToDir(
            imageData, keyword, imagesConfig.getPixivTempDir());

        String sendPath;
        if (compressedPath != null) {
            // 压缩成功，发送压缩后的图片
            sendPath = compressedPath;
            log.info("[随机图片] 压缩并保存成功: {}", sendPath);
        } else {
            // 压缩失败或是小图无需压缩，直接存原图到 pixiv_temp 后发送
            String extension = imageDownloader.extractExtension(result.getUrl());
            String fileName = keyword + "_" + System.currentTimeMillis();
            java.io.File savedFile = memeManager.saveToCustomDir(
                imageData, imagesConfig.getPixivTempDir(), fileName, extension);
            if (savedFile == null) {
                apiClient.sendText(session, event.getMessageType(), targetId, "图片保存失败");
                return;
            }
            sendPath = savedFile.getAbsolutePath();
            log.info("[随机图片] 保存原图到pixiv_temp: {}", sendPath);
        }

        // 发送图片（已经压缩过，直接发送路径，不走二次压缩）
        apiClient.sendImageUrl(session, event.getMessageType(), targetId, sendPath);
        log.info("[随机图片] 发送完成: {} by {}", result.getTitle(), result.getAuthor());
    }
    
    /**
     * 从"偷一下"命令的参数中提取分类参数。
     * 注意：传入的已是"命令名之后的参数部分"（如 "sex new"），
     * 不再包含命令名本身（偷一下/steal/上传），因此直接按空白切分解析即可。
     * 格式: "<分类名> [new|新建]"
     *   - 第一段为分类名
     *   - 后续若出现 new / 新建，表示分类不存在时自动创建
     * 没有任何参数时返回默认值 memes。
     *
     * @param params 命令名后面的参数部分（例如 "sex new"）
     * @return String[] [0]=分类名, [1]=是否创建新分类("true"/"false")
     */
    private String[] extractCategoryParams(String params) {
        String defaultCategory = "memes";

        if (params == null || params.trim().isEmpty()) {
            return new String[]{defaultCategory, "false"};
        }

        // 按空白字符分割（中英文分类名均可，按空白切分）
        String[] parts = params.trim().split("\\s+");

        // 第一段为分类名
        String category = parts[0].trim();
        if (category.isEmpty()) {
            return new String[]{defaultCategory, "false"};
        }

        // 后续段若出现 new / 新建 表示需要创建新分类
        boolean shouldCreate = false;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if ("new".equalsIgnoreCase(p) || "新建".equals(p)) {
                shouldCreate = true;
                break;
            }
        }

        return new String[]{category, shouldCreate ? "true" : "false"};
    }
    
    /**
     * 处理偷表情包命令（支持引用消息和分类参数）
     * @param event 事件对象
     * @param session WebSocket会话
     * @param category 目标分类名
     * @param createNewCategory 是否创建新分类
     */
    private void handleStealMeme(OneBotEvent event, Session session, String category, boolean createNewCategory) {
        // 权限检查：只有管理员可以偷图
        Long userId = event.getUserId();
        if (!permissionService.isAdmin(userId)) {
            apiClient.sendText(session, "group", event.getGroupId(), 
                "使用该功能需要管理员权限");
            log.warn("非管理员尝试偷图: user={}", userId);
            return;
        }
        
        // 检查分类是否存在
        boolean isNewCategory = false;
        if (!memeManager.categoryExists(category)) {
            if (createNewCategory) {
                // 创建新分类
                memeManager.createCategory(category);
                isNewCategory = true;
                log.info("创建新分类: {}", category);
            } else {
                // 分类不存在且不创建，报错
                apiClient.sendText(session, "group", event.getGroupId(), 
                    "分类 [" + category + "] 不存在（在命令后加 new 可自动创建，如：" + commandConfig.getPrefix() + "偷一下 " + category + " new）");
                return;
            }
        }
        
        String imageUrl = null;
        
        // 方式1：检查是否有引用消息
        Integer repliedMessageId = extractRepliedMessageId(event.getMessage());
        if (repliedMessageId != null) {
            log.info("检测到引用消息 ID: {}", repliedMessageId);
            // 从缓存中获取被引用的消息
            JSONObject repliedMessage = messageCacheManager.getCachedMessage(repliedMessageId);
            
            if (repliedMessage == null) {
                // 缓存已过期，尝试直接在 temp 中查找（文件可能还在）
                String tempFilePath = findTempFileByMessageId(repliedMessageId);
                if (tempFilePath != null) {
                    log.info("缓存已过期，但 temp 中找到文件: {}，直接复制", tempFilePath);
                    java.io.File savedFile = memeManager.copyFromTempToCategory(tempFilePath, event.getUserId(), category);
                    if (savedFile != null) {
                        if (isNewCategory) {
                            apiClient.sendText(session, "group", event.getGroupId(),
                                "创建分类 [" + category + "] 成功！\n保存成功！");
                        } else {
                            apiClient.sendText(session, "group", event.getGroupId(), "偷图成功！");
                        }
                        log.info("偷图成功（从temp）: {} -> {}", tempFilePath, savedFile.getAbsolutePath());
                        return;
                    }
                }
                apiClient.sendText(session, "group", event.getGroupId(),
                    "未找到引用的消息（可能已过期或未被缓存）\n提示：只有收到过该消息才能偷图");
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
        
        // 从缓存中查找该图片是否已在temp中
        String tempFilePath = null;
        
        if (repliedMessageId != null) {
            tempFilePath = findTempFileByMessageId(repliedMessageId);
            if (tempFilePath == null) {
                log.info("未在temp中找到 message_id={} 的缓存文件，将直接下载", repliedMessageId);
            }
        }
        
        java.io.File savedFile = null;
        
        if (tempFilePath != null) {
            // 从temp复制到指定分类
            log.info("从temp复制图片到分类[{}]: {}", category, tempFilePath);
            savedFile = memeManager.copyFromTempToCategory(tempFilePath, event.getUserId(), category);
        } else {
            // 没有缓存，直接下载保存（电脑端或新图片）
            byte[] imageData = imageDownloader.downloadImage(imageUrl);
            if (imageData == null || imageData.length == 0) {
                apiClient.sendText(session, "group", event.getGroupId(), "下载图片失败，请重试");
                return;
            }

            // 优先按文件头魔数识别真实格式（URL 常无扩展名），识别不出再回退 URL 扩展名
            String extension = imageDownloader.detectImageExtension(imageData);
            if (extension == null) {
                extension = imageDownloader.extractExtension(imageUrl);
            }
            savedFile = memeManager.saveWithShortId(imageData, extension, category);
        }

        if (savedFile != null) {
            if (isNewCategory) {
                apiClient.sendText(session, "group", event.getGroupId(), 
                    "创建分类 [" + category + "] 成功！\n保存成功！");
            } else {
                apiClient.sendText(session, "group", event.getGroupId(), "偷图成功！");
            }
            log.info("偷图成功: {} -> {} (分类: {})", imageUrl, savedFile.getAbsolutePath(), category);
        } else {
            apiClient.sendText(session, "group", event.getGroupId(), "保存表情包失败，请重试");
        }
    }
    
    /**
     * 根据消息ID在temp目录中查找对应的缓存图片文件
     * @param messageId 消息ID
     * @return 文件路径，如果没找到返回 null
     */
    private String findTempFileByMessageId(Integer messageId) {
        try {
            java.io.File tempDir = new java.io.File(imagesConfig.getTempDir());
            if (!tempDir.exists() || !tempDir.isDirectory()) {
                return null;
            }
            
            java.io.File[] files = tempDir.listFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            
            // 精确匹配文件名中的 message_id 前缀
            String prefix = "auto_msg_" + messageId + "_";
            
            for (java.io.File file : files) {
                if (file.isFile() && file.getName().startsWith(prefix)) {
                    log.info("找到temp缓存文件: {} (对应 message_id={})", file.getName(), messageId);
                    return file.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.error("查找temp文件异常", e);
        }
        
        return null;
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

    /**
     * 检查消息是否at了机器人
     * @param event 事件对象
     * @return 是否at了机器人
     */
    private boolean isAtBot(OneBotEvent event) {
        JSONArray messageArray = event.getMessage();
        if (messageArray == null || messageArray.isEmpty()) {
            return false;
        }

        Long selfId = event.getSelfId();
        if (selfId == null) {
            return false;
        }

        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;

            String type = segment.getString("type");
            if ("at".equals(type)) {
                JSONObject data = segment.getJSONObject("data");
                if (data != null) {
                    Long atUserId = data.getLong("qq");
                    if (atUserId != null && atUserId.equals(selfId)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 提取at机器人后的文本内容
     * @param event 事件对象
     * @return at后的文本，如果没有则返回空字符串
     */
    private String extractTextAfterAt(OneBotEvent event) {
        JSONArray messageArray = event.getMessage();
        if (messageArray == null || messageArray.isEmpty()) {
            return "";
        }

        Long selfId = event.getSelfId();
        StringBuilder text = new StringBuilder();
        boolean foundAtBot = false;

        for (int i = 0; i < messageArray.size(); i++) {
            JSONObject segment = messageArray.getJSONObject(i);
            if (segment == null) continue;

            String type = segment.getString("type");
            JSONObject data = segment.getJSONObject("data");

            if ("at".equals(type) && data != null) {
                Long atUserId = data.getLong("qq");
                if (atUserId != null && atUserId.equals(selfId)) {
                    foundAtBot = true;
                    continue; // 跳过at段，继续提取后面的文本
                }
            }

            // 找到at机器人后，提取后续文本
            if (foundAtBot && "text".equals(type) && data != null) {
                String content = data.getString("text");
                if (content != null) {
                    text.append(content.trim());
                }
            }
        }

        return text.toString().trim();
    }

    /**
     * 处理AI功能开关命令，格式：/enable true 或 /enable false
     * 无参数时任何人都可查看状态，修改时需要管理员权限
     * @param event 消息事件
     * @param session WebSocket会话
     * @param content 消息内容
     */
    private void handleToggleAi(OneBotEvent event, Session session, String content) {
        // 去掉前缀和命令名，获取参数
        String param = commandConfig.stripPrefix(content);
        param = extractParams(param, "enable").toLowerCase();

        if (param.isEmpty()) {
            // 无参数时任何人都可以查看当前状态
            String status = aiConfig.isEnabled() ? "已开启" : "已关闭";
            Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
            apiClient.sendText(session, event.getMessageType(), targetId, "ai对话" + status);
            return;
        }

        // 有参数时需要管理员权限
        if (!permissionService.isAdmin(event.getUserId())) {
            log.info("[AI开关] 非管理员尝试操作: userId={}", event.getUserId());
            Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
            apiClient.sendText(session, event.getMessageType(), targetId, "使用该功能需要管理员权限");
            return;
        }

        // 根据参数设置开关
        boolean newValue;
        if ("true".equals(param) || "on".equals(param) || "1".equals(param)) {
            newValue = true;
        } else if ("false".equals(param) || "off".equals(param) || "0".equals(param)) {
            newValue = false;
        } else {
            Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
            apiClient.sendText(session, event.getMessageType(), targetId, "参数无效，请使用: /enable true 或 /enable false");
            return;
        }

        // 更新配置
        aiConfig.setEnabled(newValue);
        log.info("[AI开关] 管理员 userId={} 已将AI功能设置为: {}", event.getUserId(), newValue);

        // 发送反馈
        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();
        apiClient.sendText(session, event.getMessageType(), targetId, newValue ? "已开启ai对话" : "已关闭ai对话");
    }
}