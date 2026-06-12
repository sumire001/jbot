package com.bot.jone.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Slf4j
@Data
@Component
@ConfigurationProperties("bot.admin")
public class BotAdminConfiguration {
/**
 *bot的管理员id列表
 */
private ArrayList<Long> qqList = new ArrayList<>();
}
