package com.bot.jone.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.websocket.Session;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

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
            sendSync(session, action.toJSONString());
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
            sendSync(session, jsonPayload);
            log.info("发送图片: {}, payload: {}", effectivePath, jsonPayload.length() > 200 ? jsonPayload.substring(0, 200) + "..." : jsonPayload);
        } catch (IOException e) {
            log.error("发送图片失败: {}", effectivePath, e);
        }
    }

    /**
     * 通过 WebSocket 发送本地图片（Base64 内嵌）。
     * 发送前会把图片重编码为受控质量 JPEG(q88) 以显著缩小体积，避免超大 base64 帧
     * 把跨机器的 OneBot 客户端（如 LLBot/LLOneBot）WebSocket 冲垮
     * （invalid status code / Write timeout / TEXT_FULL_WRITING）。
     * 原图 PNG 缓存文件不动，仅传输链路做有损压缩，画廊缩略图网格几乎无感。
     *
     * @param session    WebSocket 会话
     * @param msgType    消息类型: "group" 或 "private"
     * @param targetId   目标ID（群号或用户ID）
     * @param imageFile  本地图片文件（PNG/JPG 等）
     * @return true 表示已成功送出；false 表示读取/发送失败（调用方不应再发后续文本）
     */
    public boolean sendImageBase64(Session session, String msgType, Long targetId, File imageFile) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(imageFile.toPath());
        } catch (IOException e) {
            log.error("发送图片(base64) 读取文件失败: {}", imageFile, e);
            return false;
        }
        if (raw.length == 0) {
            log.error("发送图片(base64) 文件为空: {}", imageFile);
            return false;
        }
        // 受控重编码为 JPEG(q88) 缩小传输体积
        byte[] data = toJpegBytes(raw, 0.88f);
        String file = "base64://" + Base64.getEncoder().encodeToString(data);

        JSONObject action = new JSONObject();
        action.put("action", "send_msg");

        JSONObject params = new JSONObject();
        params.put(msgType.equals("group") ? "group_id" : "user_id", targetId);

        JSONArray msgArray = new JSONArray();
        msgArray.add(new JSONObject()
                .fluentPut("type", "image")
                .fluentPut("data", new JSONObject().fluentPut("file", file)));

        params.put("message", msgArray);
        action.put("params", params);

        try {
            sendSync(session, action.toJSONString());
            log.info("发送图片(base64): {}, 原始 {}KB -> 传输 {}KB", imageFile.getName(),
                    raw.length / 1024, data.length / 1024);
            return true;
        } catch (IOException e) {
            log.error("发送图片(base64)失败: {}", imageFile, e);
            return false;
        }
    }

    /**
     * 通过 WebSocket 发送本地图片（Base64 内嵌，**保留原图字节不重编码**）。
     * 用于表情等小图：跨机器部署时本地路径在对方机器上不存在，必须 base64:// 内嵌；
     * 但表情常含 GIF 动图/透明通道，故不重编码为 JPEG，保持原始格式与清晰度。
     * 调用方需自行把控体积（表情一般较小，风险低）。
     *
     * @return true 表示已送出；false 表示读取/发送失败
     */
    public boolean sendImageBase64Raw(Session session, String msgType, Long targetId, File imageFile) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(imageFile.toPath());
        } catch (IOException e) {
            log.error("发送图片(base64) 读取文件失败: {}", imageFile, e);
            return false;
        }
        if (raw.length == 0) {
            log.error("发送图片(base64) 文件为空: {}", imageFile);
            return false;
        }
        String file = "base64://" + Base64.getEncoder().encodeToString(raw);

        JSONObject action = new JSONObject();
        action.put("action", "send_msg");

        JSONObject params = new JSONObject();
        params.put(msgType.equals("group") ? "group_id" : "user_id", targetId);

        JSONArray msgArray = new JSONArray();
        msgArray.add(new JSONObject()
                .fluentPut("type", "image")
                .fluentPut("data", new JSONObject().fluentPut("file", file)));

        params.put("message", msgArray);
        action.put("params", params);

        try {
            sendSync(session, action.toJSONString());
            log.info("发送图片(base64-原图): {}, {}KB", imageFile.getName(), raw.length / 1024);
            return true;
        } catch (IOException e) {
            log.error("发送图片(base64-原图)失败: {}", imageFile, e);
            return false;
        }
    }

    /**
     * 串行化同一会话的发送，避免并发/重叠写入导致 Tomcat WS 报
     * "The remote endpoint was in state [TEXT_FULL_WRITING]" 异常。
     */
    private void sendSync(Session session, String json) throws IOException {
        synchronized (session) {
            session.getBasicRemote().sendText(json);
        }
    }

    /**
     * 将图片字节重编码为 JPEG（受控质量），用于缩小传输体积。
     * 解码失败、无法编码、或压缩后未明显变小则回退原始字节。
     */
    private static byte[] toJpegBytes(byte[] src, float quality) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) {
                return src;
            }
            // JPEG 不支持透明通道，先转 RGB
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(rgb, null, null), param);
            writer.dispose();
            byte[] out = baos.toByteArray();
            // 若压缩后反而更大或没明显变小，保持原样
            return out.length < src.length ? out : src;
        } catch (Exception e) {
            return src;
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
            sendSync(session, jsonPayload);
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
            sendSync(session, action.toJSONString());
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
            sendSync(session, action.toJSONString());
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
