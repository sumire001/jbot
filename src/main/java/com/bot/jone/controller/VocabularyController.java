package com.bot.jone.controller;

import com.bot.jone.handler.WebSocketSessionManager;
import com.bot.jone.model.Vocabulary;
import com.bot.jone.service.VocabularyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.websocket.Session;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词汇控制器
 * 接收词汇数据，保存并推送到QQ
 */
@Slf4j
@RestController
@RequestMapping("/api/vocabulary")
@CrossOrigin(origins = "*")
public class VocabularyController {

    @Autowired
    private VocabularyService vocabularyService;

    @Autowired
    private WebSocketSessionManager sessionManager;

    /**
     * 接收词汇数据并保存，同时推送到QQ
     *
     * @param vocabulary 词汇数据
     * @return 操作结果
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Vocabulary vocabulary) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 仅保存数据，不推送
            boolean saved = vocabularyService.save(vocabulary);
            if (saved) {
                result.put("success", true);
                result.put("message", "词汇数据保存成功");
                result.put("title", vocabulary.getTitle());
            } else {
                result.put("success", false);
                result.put("message", "词汇数据保存失败");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("保存词汇数据失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 接收词汇数据，保存并推送到QQ群
     *
     * @param vocabulary 词汇数据
     * @param groupId    目标群号
     * @return 操作结果
     */
    @PostMapping("/push/group/{groupId}")
    public ResponseEntity<Map<String, Object>> pushToGroup(
            @RequestBody Vocabulary vocabulary,
            @PathVariable Long groupId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 检查WebSocket连接状态
            if (!sessionManager.hasActiveSession()) {
                result.put("success", false);
                result.put("message", "WebSocket未连接，无法推送消息");
                return ResponseEntity.ok(result);
            }

            Session session = sessionManager.getFirstSession();
            boolean saved = vocabularyService.saveAndPush(vocabulary, session, "group", groupId);

            if (saved) {
                result.put("success", true);
                result.put("message", "词汇数据保存成功并已推送到群 " + groupId);
                result.put("title", vocabulary.getTitle());
                result.put("groupId", groupId);
            } else {
                result.put("success", false);
                result.put("message", "词汇数据保存失败");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("推送词汇到群失败", e);
            result.put("success", false);
            result.put("message", "推送失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 接收词汇数据，保存并推送到私聊
     *
     * @param vocabulary 词汇数据
     * @param userId     目标用户ID
     * @return 操作结果
     */
    @PostMapping("/push/private/{userId}")
    public ResponseEntity<Map<String, Object>> pushToPrivate(
            @RequestBody Vocabulary vocabulary,
            @PathVariable Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 检查WebSocket连接状态
            if (!sessionManager.hasActiveSession()) {
                result.put("success", false);
                result.put("message", "WebSocket未连接，无法推送消息");
                return ResponseEntity.ok(result);
            }

            Session session = sessionManager.getFirstSession();
            boolean saved = vocabularyService.saveAndPush(vocabulary, session, "private", userId);

            if (saved) {
                result.put("success", true);
                result.put("message", "词汇数据保存成功并已推送到用户 " + userId);
                result.put("title", vocabulary.getTitle());
                result.put("userId", userId);
            } else {
                result.put("success", false);
                result.put("message", "词汇数据保存失败");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("推送词汇到私聊失败", e);
            result.put("success", false);
            result.put("message", "推送失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 获取所有词汇文件列表
     *
     * @return 文件名列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> files = vocabularyService.listAll();
            result.put("success", true);
            result.put("data", files);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取词汇列表失败", e);
            result.put("success", false);
            result.put("message", "获取列表失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 加载词汇数据
     *
     * @param fileName 文件名
     * @return 词汇数据
     */
    @GetMapping("/load/{fileName}")
    public ResponseEntity<Map<String, Object>> load(@PathVariable String fileName) {
        Map<String, Object> result = new HashMap<>();
        try {
            Vocabulary vocabulary = vocabularyService.load(fileName);
            if (vocabulary != null) {
                result.put("success", true);
                result.put("data", vocabulary);
            } else {
                result.put("success", false);
                result.put("message", "词汇数据不存在");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("加载词汇数据失败", e);
            result.put("success", false);
            result.put("message", "加载失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 删除词汇数据
     *
     * @param fileName 文件名
     * @return 操作结果
     */
    @DeleteMapping("/delete/{fileName}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String fileName) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean deleted = vocabularyService.delete(fileName);
            if (deleted) {
                result.put("success", true);
                result.put("message", "词汇数据删除成功");
            } else {
                result.put("success", false);
                result.put("message", "词汇数据不存在或删除失败");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("删除词汇数据失败", e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 检查WebSocket连接状态
     *
     * @return 连接状态
     */
    @GetMapping("/connection/status")
    public ResponseEntity<Map<String, Object>> getConnectionStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("connected", sessionManager.hasActiveSession());
        result.put("sessionCount", sessionManager.getSessionCount());
        return ResponseEntity.ok(result);
    }
}