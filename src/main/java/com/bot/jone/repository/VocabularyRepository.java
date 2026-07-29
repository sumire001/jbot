package com.bot.jone.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.bot.jone.model.Vocabulary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 词汇数据持久层
 * 负责将词汇数据存储到JSON文件
 */
@Slf4j
@Repository
public class VocabularyRepository {

    @Value("${bot.vocabulary.data-dir:./data/vocabulary}")
    private String dataDir;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @PostConstruct
    public void init() {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            log.info("词汇数据目录初始化: {}, 结果: {}", dataDir, created ? "成功" : "失败");
        }
    }

    /**
     * 保存词汇数据到JSON文件
     * 如果文件不存在则创建，存在则覆盖
     *
     * @param vocabulary 词汇数据
     * @param fileName   文件名（不含扩展名）
     * @return 是否保存成功
     */
    public boolean save(Vocabulary vocabulary, String fileName) {
        lock.writeLock().lock();
        try {
            Path filePath = Paths.get(dataDir, fileName + ".json");
            File file = filePath.toFile();

            // 确保父目录存在
            File parentDir = file.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 如果文件不存在，创建新文件
            if (!file.exists()) {
                file.createNewFile();
            }

            // 写入JSON（格式化输出）
            String json = JSON.toJSONString(vocabulary, JSONWriter.Feature.PrettyFormat);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));

            log.info("词汇数据已保存: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("保存词汇数据失败: fileName={}", fileName, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 读取词汇数据
     *
     * @param fileName 文件名（不含扩展名）
     * @return 词汇数据，不存在则返回null
     */
    public Vocabulary load(String fileName) {
        lock.readLock().lock();
        try {
            Path filePath = Paths.get(dataDir, fileName + ".json");
            File file = filePath.toFile();

            if (!file.exists()) {
                return null;
            }

            String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            return JSON.parseObject(json, Vocabulary.class);
        } catch (IOException e) {
            log.error("读取词汇数据失败: fileName={}", fileName, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检查词汇文件是否存在
     *
     * @param fileName 文件名（不含扩展名）
     * @return 是否存在
     */
    public boolean exists(String fileName) {
        Path filePath = Paths.get(dataDir, fileName + ".json");
        return filePath.toFile().exists();
    }

    /**
     * 获取所有词汇文件列表
     *
     * @return 文件名列表（不含扩展名）
     */
    public List<String> listAll() {
        List<String> fileNames = new ArrayList<>();
        File dir = new File(dataDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return fileNames;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                fileNames.add(name.substring(0, name.length() - 5)); // 移除.json后缀
            }
        }
        return fileNames;
    }

    /**
     * 删除词汇文件
     *
     * @param fileName 文件名（不含扩展名）
     * @return 是否删除成功
     */
    public boolean delete(String fileName) {
        lock.writeLock().lock();
        try {
            Path filePath = Paths.get(dataDir, fileName + ".json");
            File file = filePath.toFile();

            if (!file.exists()) {
                return false;
            }

            boolean deleted = file.delete();
            if (deleted) {
                log.info("词汇数据已删除: {}", filePath);
            }
            return deleted;
        } finally {
            lock.writeLock().unlock();
        }
    }
}