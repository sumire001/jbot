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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.util.Random;

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
    private ApplicationContext applicationContext;

    private final Random random = new Random();

    public void handle(OneBotEvent event, Session session) {
        // 提取消息内容（支持多种类型）
        String content = extractMessageContent(event.getMessage());
        //打印聊天日志
        log.info("[{}]{}/{}:{}", event.getGroupId(), event.getUserId(), event.getSender().getNickname(), content);
        
        // 调试：打印原始消息内容和命令匹配情况
        log.debug("消息内容长度: {}, startsWith(随机): {}", content.length(), content.startsWith("随机"));
        
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
            // 处理偷表情包命令，支持分类参数
            String[] categoryParams = extractCategoryParams(content);
            handleStealMeme(event, session, categoryParams[0], Boolean.parseBoolean(categoryParams[1]));
        } else if (content.startsWith("随机") && content.endsWith("图")) {
            // 处理随机二次元图片命令，格式：随机XX图
            handleRandomImageCommand(event, session, content);
        } else if (content.startsWith("/enable")) {
            // 动态控制AI功能开关，需要管理员权限
            handleToggleAi(event, session, content);
        } else if (isAtBot(event)) {
            // 处理AI聊天：at机器人后触发，需要检查AI功能是否启用
            if (!aiConfig.isEnabled()) {
                return;
            }
            String question = extractTextAfterAt(event);
            handleAiChat(event, session, question);
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

        Long targetId = "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId();

        log.info("[AI] 用户提问: {}", question);

        // 调用AI服务获取回复
        String reply = aiService.chat(event.getUserId(), question);

        if (reply != null && !reply.isEmpty()) {
            apiClient.sendText(session, event.getMessageType(), targetId, reply);
        } else {
            apiClient.sendText(session, event.getMessageType(), targetId, "AI暂时没有回复，请稍后再试~");
        }
    }

    /**
     * 处理随机二次元图片命令
     * 格式: "随机XX图"，提取中间的XX作为搜索关键词
     * 优化流程: 下载 → 内存中压缩 → 存 pixiv_temp → 发送压缩图
     */
    private void handleRandomImageCommand(OneBotEvent event, Session session, String content) {
        // 提取关键词（去掉开头的"随机"和结尾的"图"）
        String keyword = content.substring(2, content.length() - 1).trim();

        if (keyword.isEmpty()) {
            apiClient.sendText(session, event.getMessageType(),
                "group".equals(event.getMessageType()) ? event.getGroupId() : event.getUserId(),
                "请输入搜索关键词，例如：随机猫猫图 或 随机原神图");
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
     * 从"偷一下"命令中提取分类参数
     * 格式: "偷一下 [分类名] [new]"，如果没有分类则返回默认值 memes
     * @return String[] [0]=分类名, [1]=是否创建新分类(true/false)
     */
    private String[] extractCategoryParams(String content) {
        String defaultCategory = "memes";
        
        // 参数校验：如果内容为空或不包含"偷一下"命令，返回默认分类
        if (content == null || !content.contains("偷一下")) {
            return new String[]{defaultCategory, "false"};
        }
        
        // 找到"偷一下"在字符串中的位置
        int index = content.indexOf("偷一下");
        // 截取"偷一下"后面的内容并去除首尾空格
        String rest = content.substring(index + 3).trim();
        
        // 如果后面没有内容，使用默认分类
        if (rest.isEmpty()) {
            return new String[]{defaultCategory, "false"};
        }
        
        // 按空白字符分割
        String[] parts = rest.split("\\s+");
        
        // 只有"偷一下"命令，没有分类参数，使用默认分类
        if (parts.length == 1 && parts[0].isEmpty()) {
            return new String[]{defaultCategory, "false"};
        }
        
        // 只有一个参数（分类名）
        if (parts.length >= 1 && !parts[0].isEmpty()) {
            // 检查第二个参数是否为"new"
            boolean shouldCreate = parts.length >= 2 && "new".equalsIgnoreCase(parts[1]);
            return new String[]{parts[0], shouldCreate ? "true" : "false"};
        }
        
        return new String[]{defaultCategory, "false"};
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
                    "分类 [" + category + "] 不存在");
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
            
            String extension = imageDownloader.extractExtension(imageUrl);
            String fileName = "stolen_" + event.getUserId();
            savedFile = memeManager.saveMemeToCategory(imageData, fileName, extension, category);
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
        // 解析参数
        String param = content.substring("/enable".length()).trim().toLowerCase();

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