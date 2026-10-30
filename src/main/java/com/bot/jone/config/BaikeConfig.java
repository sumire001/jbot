package com.bot.jone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 百度百科配置（前缀 bot.baike）。
 *
 * 提供 AI 工具 baidu_baike 的后端能力：调用百度千帆 AppBuilder 的「百度百科」组件，
 * 按词条名查询权威词条内容（释义、概述、关系等）。
 *
 * API Key 取值优先级：配置项 bot.baike.api-key 优先，为空时回退到环境变量 BAIDU_API_KEY。
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot.baike")
public class BaikeConfig {

    /** 是否启用百度百科工具。默认开启；关闭后工具不再向 LLM 暴露查询能力。 */
    private boolean enabled = true;

    /**
     * 百度千帆 AppBuilder 的 API Key（形如 bce-v3/ALTAK-...）。
     * 留空时回退到环境变量 BAIDU_API_KEY，见 {@link #resolveApiKey()}。
     */
    private String apiKey;

    /**
     * 解析实际使用的 Key：配置项优先，为空则取环境变量 BAIDU_API_KEY，都没有返回 null。
     * 不只在 yml 里用 ${BAIDU_API_KEY:} 占位符的原因：外部 config/application.yml 一旦
     * 写过该项（哪怕是空值），就会盖掉 classpath 默认值里的占位符，导致环境变量失效。
     */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey.trim();
        }
        return envApiKey();
    }

    /** Key 来源：config（配置文件）/ env（环境变量）/ none（未配置），供 WebUI 展示。 */
    public String apiKeySource() {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return "config";
        }
        return envApiKey() == null ? "none" : "env";
    }

    private String envApiKey() {
        String value = System.getenv("BAIDU_API_KEY");
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
