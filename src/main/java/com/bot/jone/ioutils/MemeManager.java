package com.bot.jone.ioutils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MemeManager {
    
    private Map<String, List<File>> memeCache = new ConcurrentHashMap<>();
    private String baseDir = "src/main/java/com/bot/jone/ioutils/memes";
    private Random random = new Random();
    
    @PostConstruct
    public void init() {
        // 启动时预加载所有表情文件列表
        loadMemes();
    }
    
    private void loadMemes() {
        File memesRoot = new File(baseDir);
        
        if (!memesRoot.exists() || !memesRoot.isDirectory()) {
            log.warn("表情包目录不存在: {}", memesRoot.getAbsolutePath());
            return;
        }
        
        File[] categories = memesRoot.listFiles();
        if (categories == null) {
            return;
        }
        
        for (File category : categories) {
            if (category.isDirectory()) {
                File[] files = category.listFiles();
                if (files != null && files.length > 0) {
                    List<File> fileList = Arrays.asList(files);
                    memeCache.put(category.getName(), fileList);
                    log.info("加载表情包分类: {}, 数量: {}", category.getName(), fileList.size());
                }
            }
        }
        
        log.info("表情包加载完成，共 {} 个分类", memeCache.size());
    }
    
    /**
     * 从指定分类获取随机表情
     * @param category 分类名称（如 happy, sad 等）
     * @return 随机表情文件，如果分类不存在则返回 null
     */
    public File getRandomMeme(String category) {
        List<File> files = memeCache.get(category);
        if (files == null || files.isEmpty()) {
            log.warn("表情包分类不存在或为空: {}", category);
            return null;
        }
        return files.get(random.nextInt(files.size()));
    }
    
    /**
     * 从所有分类中随机获取一个表情
     * @return 随机表情文件
     */
    public File getRandomMemeFromAll() {
        if (memeCache.isEmpty()) {
            log.warn("没有可用的表情包");
            return null;
        }
        
        List<File> allFiles = new ArrayList<>();
        for (List<File> files : memeCache.values()) {
            allFiles.addAll(files);
        }
        
        if (allFiles.isEmpty()) {
            return null;
        }
        
        return allFiles.get(random.nextInt(allFiles.size()));
    }
    
    /**
     * 获取旮旯给木表情包的随机表情
     * @return 随机表情文件
     */
    public File getRandomHappyMeme() {
        return getRandomMeme("旮旯给木表情包");
    }
    
    /**
     * 保存图片到指定分类目录
     * @param imageData 图片字节数据
     * @param category 分类名称（如 happy, sad 等）
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveMeme(byte[] imageData, String category, String fileName, String extension) {
        try {
            // 安全校验：防止路径穿越攻击
            if (category.contains("..") || category.contains("/") || category.contains("\\")) {
                log.error("非法的分类名称: {}", category);
                return null;
            }
            
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                log.error("非法的文件名: {}", fileName);
                return null;
            }
            
            // 验证扩展名是否合法
            if (!extension.matches("[a-zA-Z0-9]+")) {
                log.error("非法的文件扩展名: {}", extension);
                extension = "jpg"; // 使用默认扩展名
            }
            
            // 构建目录路径
            File categoryDir = new File(baseDir, category);
            if (!categoryDir.exists()) {
                categoryDir.mkdirs();
                log.info("创建表情包分类目录: {}", categoryDir.getAbsolutePath());
            }
            
            // 生成唯一文件名（避免冲突）
            String uniqueFileName = fileName + "_" + System.currentTimeMillis() + "." + extension;
            File targetFile = new File(categoryDir, uniqueFileName);
            
            // 安全检查：确保文件在预期目录下
            String canonicalPath = targetFile.getCanonicalPath();
            String expectedPrefix = new File(baseDir, category).getCanonicalPath();
            if (!canonicalPath.startsWith(expectedPrefix)) {
                log.error("文件路径超出允许范围: {}", canonicalPath);
                return null;
            }
            
            // 写入文件
            java.nio.file.Files.write(targetFile.toPath(), imageData);
            log.info("保存表情包成功: {}", targetFile.getAbsolutePath());
            
            // 更新缓存
            refreshCategoryCache(category);
            
            return targetFile;
        } catch (Exception e) {
            log.error("保存表情包失败", e);
            return null;
        }
    }
    
    /**
     * 刷新指定分类的缓存
     * @param category 分类名称
     */
    private void refreshCategoryCache(String category) {
        File categoryDir = new File(baseDir, category);
        if (categoryDir.exists() && categoryDir.isDirectory()) {
            File[] files = categoryDir.listFiles();
            if (files != null && files.length > 0) {
                memeCache.put(category, Arrays.asList(files));
                log.info("刷新表情包分类缓存: {}, 数量: {}", category, files.length);
            }
        }
    }
    
    /**
     * 刷新所有分类缓存
     */
    public void refreshAllCache() {
        loadMemes();
    }
}
