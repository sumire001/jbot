package com.bot.jone.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * 密码管理器
 * 管理WebUI的密码认证，密码以MD5哈希形式存储在JSON文件中
 */
@Component
@Slf4j
public class PasswordManager {

    private static final String PASSWORD_FILE = "src/main/resources/static/auth.json";
    private static final String RUNTIME_PASSWORD_FILE = "./data/auth.json";

    private String storedHash;
    private String salt;
    private boolean hasPassword = false;

    @PostConstruct
    public void init() {
        loadPassword();
    }

    /**
     * 加载密码配置
     */
    private void loadPassword() {
        File file = getPasswordFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[1024];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, len);
                }
                JSONObject json = JSON.parseObject(sb.toString());
                if (json.containsKey("hash") && json.containsKey("salt")) {
                    this.storedHash = json.getString("hash");
                    this.salt = json.getString("salt");
                    this.hasPassword = true;
                    log.info("[Auth] 已加载密码配置");
                }
            } catch (Exception e) {
                log.error("[Auth] 加载密码配置失败", e);
            }
        } else {
            log.info("[Auth] 未找到密码配置文件，首次启动");
        }
    }

    /**
     * 获取密码文件路径
     */
    private File getPasswordFile() {
        // 优先使用运行时目录
        Path runtimePath = Paths.get(RUNTIME_PASSWORD_FILE);
        if (runtimePath.toFile().exists()) {
            return runtimePath.toFile();
        }
        // 回退到源代码目录
        Path sourcePath = Paths.get(PASSWORD_FILE);
        if (sourcePath.toFile().exists()) {
            return sourcePath.toFile();
        }
        // 返回运行时路径用于创建新文件
        return runtimePath.toFile();
    }

    /**
     * 是否已设置密码
     */
    public boolean hasPassword() {
        return hasPassword;
    }

    /**
     * 设置新密码
     */
    public boolean setPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            return false;
        }
        try {
            this.salt = UUID.randomUUID().toString();
            this.storedHash = hashPassword(password, salt);
            saveToFile();
            this.hasPassword = true;
            log.info("[Auth] 密码设置成功");
            return true;
        } catch (Exception e) {
            log.error("[Auth] 设置密码失败", e);
            return false;
        }
    }

    /**
     * 验证密码
     */
    public boolean verifyPassword(String password) {
        if (!hasPassword || storedHash == null || salt == null) {
            return false;
        }
        String hash = hashPassword(password, salt);
        return storedHash.equals(hash);
    }

    /**
     * 密码哈希
     */
    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String combined = salt + password + salt;
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    /**
     * 保存到JSON文件
     */
    private void saveToFile() throws Exception {
        File file = getPasswordFile();
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            Files.createDirectories(parentDir.toPath());
        }

        AuthConfig config = new AuthConfig();
        config.setHash(storedHash);
        config.setSalt(salt);

        String json = JSON.toJSONString(config, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(json);
        }
        log.info("[Auth] 密码配置已保存到: {}", file.getAbsolutePath());
    }

    /**
     * 清除密码（重置）
     */
    public void clearPassword() {
        File file = getPasswordFile();
        if (file.exists()) {
            file.delete();
        }
        this.storedHash = null;
        this.salt = null;
        this.hasPassword = false;
        log.info("[Auth] 密码已清除");
    }

    @Data
    public static class AuthConfig {
        private String hash;
        private String salt;
    }
}