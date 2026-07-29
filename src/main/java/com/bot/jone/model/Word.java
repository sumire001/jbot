package com.bot.jone.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 单词数据模型
 */
@Data
public class Word {
    /**
     * 单词ID（可为空）
     */
    private Long id;

    /**
     * 单词
     */
    private String word;

    /**
     * 发音
     */
    private String pronunciation;

    /**
     * 含义
     */
    private String meaning;

    /**
     * 例句
     */
    @JsonProperty("example_sentence")
    private String exampleSentence;

    /**
     * 例句翻译
     */
    @JsonProperty("example_translation")
    private String exampleTranslation;

    /**
     * 记忆技巧
     */
    @JsonProperty("memory_tip")
    private String memoryTip;
}