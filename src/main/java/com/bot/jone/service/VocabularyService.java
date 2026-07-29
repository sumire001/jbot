package com.bot.jone.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bot.jone.client.OneBotApiClient;
import com.bot.jone.model.Vocabulary;
import com.bot.jone.model.Word;
import com.bot.jone.repository.VocabularyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.websocket.Session;
import java.util.List;

/**
 * 词汇服务层
 * 处理词汇数据并通过WebSocket发送消息
 */
@Slf4j
@Service
public class VocabularyService {

    @Autowired
    private VocabularyRepository vocabularyRepository;

    @Autowired
    private OneBotApiClient oneBotApiClient;

    /**
     * 保存词汇数据并推送到QQ
     *
     * @param vocabulary 词汇数据
     * @param session    WebSocket会话（用于发送消息到QQ）
     * @param msgType    消息类型 "group" 或 "private"
     * @param targetId   目标ID（群号或用户ID）
     * @return 保存结果
     */
    public boolean saveAndPush(Vocabulary vocabulary, Session session, String msgType, Long targetId) {
        // 1. 数据校验
        if (vocabulary == null) {
            log.warn("词汇数据为空");
            return false;
        }

        if (vocabulary.getTitle() == null || vocabulary.getTitle().trim().isEmpty()) {
            log.warn("词汇标题为空");
            return false;
        }

        // 2. 生成文件名（使用标题作为文件名，处理特殊字符）
        String fileName = sanitizeFileName(vocabulary.getTitle());

        // 3. 为单词生成ID（如果没有）
        if (vocabulary.getWords() != null) {
            long idCounter = System.currentTimeMillis();
            for (Word word : vocabulary.getWords()) {
                if (word.getId() == null) {
                    word.setId(idCounter++);
                }
            }
        }

        // 4. 保存到JSON文件
        boolean saved = vocabularyRepository.save(vocabulary, fileName);
        if (!saved) {
            log.error("保存词汇数据失败: {}", fileName);
            return false;
        }

        log.info("词汇数据保存成功: {}", fileName);

        // 5. 通过WebSocket发送消息到QQ
        if (session == null) {
            log.warn("WebSocket会话为空，跳过推送");
        } else if (!session.isOpen()) {
            log.warn("WebSocket会话已关闭，跳过推送");
        } else if (targetId == null) {
            log.warn("目标ID为空，跳过推送");
        } else {
            pushVocabularyToQQ(vocabulary, session, msgType, targetId);
        }

        return true;
    }

    /**
     * 仅保存词汇数据（不推送）
     *
     * @param vocabulary 词汇数据
     * @return 保存结果
     */
    public boolean save(Vocabulary vocabulary) {
        if (vocabulary == null || vocabulary.getTitle() == null || vocabulary.getTitle().trim().isEmpty()) {
            log.warn("词汇数据或标题为空");
            return false;
        }

        String fileName = sanitizeFileName(vocabulary.getTitle());

        // 为单词生成ID
        if (vocabulary.getWords() != null) {
            long idCounter = System.currentTimeMillis();
            for (Word word : vocabulary.getWords()) {
                if (word.getId() == null) {
                    word.setId(idCounter++);
                }
            }
        }

        return vocabularyRepository.save(vocabulary, fileName);
    }

    /**
     * 加载词汇数据
     *
     * @param fileName 文件名
     * @return 词汇数据
     */
    public Vocabulary load(String fileName) {
        return vocabularyRepository.load(fileName);
    }

    /**
     * 获取所有词汇文件列表
     *
     * @return 文件名列表
     */
    public List<String> listAll() {
        return vocabularyRepository.listAll();
    }

    /**
     * 删除词汇数据
     *
     * @param fileName 文件名
     * @return 是否删除成功
     */
    public boolean delete(String fileName) {
        return vocabularyRepository.delete(fileName);
    }

    /**
     * 推送词汇数据到QQ
     *
     * @param vocabulary 词汇数据
     * @param session    WebSocket会话
     * @param msgType    消息类型
     * @param targetId   目标ID
     */
    private void pushVocabularyToQQ(Vocabulary vocabulary, Session session, String msgType, Long targetId) {
        try {
            // 构建消息内容
            StringBuilder message = new StringBuilder();
            message.append("📚 词汇推送\n");
            message.append("━━━━━━━━━━━━━━\n");
            message.append("📖 标题: ").append(vocabulary.getTitle()).append("\n");

            if (vocabulary.getDescription() != null && !vocabulary.getDescription().isEmpty()) {
                message.append("📝 描述: ").append(vocabulary.getDescription()).append("\n");
            }

            if (vocabulary.getLifecycleCoverage() != null && !vocabulary.getLifecycleCoverage().isEmpty()) {
                message.append("📅 覆盖范围: ").append(vocabulary.getLifecycleCoverage()).append("\n");
            }

            message.append("━━━━━━━━━━━━━━\n");

            if (vocabulary.getWords() != null && !vocabulary.getWords().isEmpty()) {
                int count = 1;
                for (Word word : vocabulary.getWords()) {
                    message.append("\n【").append(count++).append("】").append(word.getWord() != null ? word.getWord() : "").append("\n");

                    if (word.getPronunciation() != null && !word.getPronunciation().isEmpty()) {
                        message.append("🔊 ").append(word.getPronunciation()).append("\n");
                    }

                    if (word.getMeaning() != null && !word.getMeaning().isEmpty()) {
                        message.append("💡 ").append(word.getMeaning()).append("\n");
                    }

                    if (word.getExampleSentence() != null && !word.getExampleSentence().isEmpty()) {
                        message.append("💬 ").append(word.getExampleSentence()).append("\n");
                    }

                    if (word.getExampleTranslation() != null && !word.getExampleTranslation().isEmpty()) {
                        message.append("🔄 ").append(word.getExampleTranslation()).append("\n");
                    }

                    if (word.getMemoryTip() != null && !word.getMemoryTip().isEmpty()) {
                        message.append("🧠 记忆: ").append(word.getMemoryTip()).append("\n");
                    }

                    message.append("───────────────\n");
                }
            }

            message.append("\n共 ").append(vocabulary.getWords() != null ? vocabulary.getWords().size() : 0).append(" 个单词");

            // 发送文本消息
            oneBotApiClient.sendText(session, msgType, targetId, message.toString());
            log.info("词汇推送已发送到QQ: msgType={}, targetId={}", msgType, targetId);

        } catch (Exception e) {
            log.error("推送词汇到QQ失败", e);
        }
    }

    /**
     * 清理文件名中的特殊字符
     *
     * @param fileName 原始文件名
     * @return 清理后的文件名
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "vocabulary_" + System.currentTimeMillis();
        }
        // 移除不允许的字符，替换为下划线
        String sanitized = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        // 移除首尾空格
        sanitized = sanitized.trim();
        // 如果清理后为空，使用时间戳
        if (sanitized.isEmpty()) {
            sanitized = "vocabulary_" + System.currentTimeMillis();
        }
        return sanitized;
    }
}