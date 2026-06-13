package com.bot.jone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // 启用定时任务支持
public class JOneApplication {

    public static void main(String[] args) {
        SpringApplication.run(JOneApplication.class, args);
    }

}
