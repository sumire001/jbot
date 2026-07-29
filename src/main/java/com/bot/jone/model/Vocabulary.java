package com.bot.jone.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * 词汇数据模型
 */
@Data
public class Vocabulary {
    /**
     * 标题
     */
    private String title;

    /**
     * 描述
     */
    private String description;

    /**
     * 生命周期覆盖范围
     */
    @JsonProperty("lifecycle_coverage")
    private String lifecycleCoverage;

    /**
     * 单词列表
     */
    private List<Word> words;
}