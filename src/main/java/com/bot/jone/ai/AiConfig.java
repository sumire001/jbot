package com.bot.jone.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "bot.ai")
public class AiConfig {
    private boolean enabled = false;
    private String apiKey;
    private String apiUrl;
    private String model;
    private int maxTokens = 500;
    private double temperature = 0.7;
}
