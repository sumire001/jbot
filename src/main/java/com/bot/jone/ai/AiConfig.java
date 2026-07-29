package com.bot.jone.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bot.ai")
public class AiConfig {
    private boolean enabled = false;
    private String apiKey;
    private String apiUrl;
    private String model;
    private int maxTokens = 500;
    private double temperature = 0.7;
    private int timeoutSeconds = 60;
    private String systemPrompt;
    private int historyMaxContext = 10;

    public int getTimeoutMillis() {
        return timeoutSeconds * 1000;
    }
}
