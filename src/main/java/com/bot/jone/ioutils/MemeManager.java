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
    private String baseDir = "src/main/java/com/bot/jone/ioutils/images/memes";
    private String tempDir = "src/main/java/com/bot/jone/ioutils/images/temp";
    private Random random = new Random();
    
    @PostConstruct
    public void init() {
        // 启动时预加载所有表情文件列表
        loadMemes();
        ensureTempDirExists();
    }
    
    /**
     * 确保temp目录存在
     */
    private void ensureTempDirExists() {
        File temp = new File(tempDir);
        if (!temp.exists()) {
            temp.mkdirs();
            log.info("创建临时目录: {}", temp.getAbsolutePath());
        }
    }
    
    private void loadMemes() {
        File memesRoot = new File(baseDir);
        
        if (!memesRoot.exists() || !memesRoot.isDirectory()) {
            log.warn("表情包目录不存在: {}", memesRoot.getAbsolutePath());
            return;
        }
        
        // 直接加载所有文件（平铺结构，无子分类）
        File[] files = memesRoot.listFiles();
        if (files != null && files.length > 0) {
            List<File> fileList = Arrays.asList(files);
            memeCache.put("all", fileList);
            log.info("加载表情包数量: {}", fileList.size());
        }
        
        log.info("表情包加载完成");
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
     * 获取随机表情
     * @return 随机表情文件
     */
    public File getRandomHappyMeme() {
        List<File> files = memeCache.get("all");
        if (files == null || files.isEmpty()) {
            log.warn("没有可用的表情包");
            return null;
        }
        return files.get(random.nextInt(files.size()));
    }
    
    /**
     * 保存图片到表情包目录（永久保存）
     * @param imageData 图片字节数据
     * @param category 分类名称（已废弃，保留兼容性）
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveMeme(byte[] imageData, String category, String fileName, String extension) {
        return saveToDirectory(imageData, baseDir, fileName, extension);
    }
    
    /**
     * 保存图片到temp目录（临时保存）
     * @param imageData 图片字节数据
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveToTemp(byte[] imageData, String fileName, String extension) {
        return saveToDirectory(imageData, tempDir, fileName, extension);
    }
    
    /**
     * 通用保存方法
     * @param imageData 图片字节数据
     * @param targetDir 目标目录路径
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名
     * @return 保存后的文件对象，失败返回 null
     */
    private File saveToDirectory(byte[] imageData, String targetDir, String fileName, String extension) {
        try {
            // 验证扩展名是否合法
            if (!extension.matches("[a-zA-Z0-9]+")) {
                log.error("非法的文件扩展名: {}", extension);
                extension = "jpg"; // 使用默认扩展名
            }
            
            // 确保目录存在
            File dir = new File(targetDir);
            if (!dir.exists()) {
                dir.mkdirs();
                log.info("创建目录: {}", dir.getAbsolutePath());
            }
            
            // 生成唯一文件名（避免冲突）
            String uniqueFileName = fileName + "_" + System.currentTimeMillis() + "." + extension;
            File targetFile = new File(dir, uniqueFileName);
            
            // 安全检查：确保文件在预期目录下
            String canonicalPath = targetFile.getCanonicalPath();
            String expectedPrefix = dir.getCanonicalPath();
            if (!canonicalPath.startsWith(expectedPrefix)) {
                log.error("文件路径超出允许范围: {}", canonicalPath);
                return null;
            }
            
            // 写入文件
            java.nio.file.Files.write(targetFile.toPath(), imageData);
            log.info("保存文件成功: {}", targetFile.getAbsolutePath());
            
            // 如果是保存到memes目录，更新缓存
            if (targetDir.equals(baseDir)) {
                refreshAllCache();
            }
            
            return targetFile;
        } catch (Exception e) {
            log.error("保存文件失败", e);
            return null;
        }
    }
    
    /**
     * 从temp目录复制文件到memes目录
     * @param tempFilePath temp中的文件路径
     * @param userId 用户ID（用于生成stolen_格式的文件名）
     * @return 复制后的文件对象，失败返回 null
     */
    public File copyFromTempToMemes(String tempFilePath, Long userId) {
        try {
            File sourceFile = new File(tempFilePath);
            if (!sourceFile.exists()) {
                log.error("源文件不存在: {}", tempFilePath);
                return null;
            }
            
            // 确保memes目录存在
            File memesDir = new File(baseDir);
            if (!memesDir.exists()) {
                memesDir.mkdirs();
            }
            
            // 提取原文件的扩展名
            String originalName = sourceFile.getName();
            String extension = "jpg"; // 默认扩展名
            int lastDotIndex = originalName.lastIndexOf('.');
            if (lastDotIndex != -1 && lastDotIndex < originalName.length() - 1) {
                extension = originalName.substring(lastDotIndex + 1);
            }
            
            // 生成新的文件名：stolen_{userId}_{时间戳}.{扩展名}
            String newFileName = "stolen_" + userId + "_" + System.currentTimeMillis() + "." + extension;
            File targetFile = new File(memesDir, newFileName);
            
            // 安全检查
            String canonicalSource = sourceFile.getCanonicalPath();
            String canonicalTarget = targetFile.getCanonicalPath();
            String expectedPrefix = memesDir.getCanonicalPath();
            
            if (!canonicalTarget.startsWith(expectedPrefix)) {
                log.error("目标路径超出允许范围: {}", canonicalTarget);
                return null;
            }
            
            // 复制文件
            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            log.info("从temp复制到memes成功: {} -> {}", sourceFile.getName(), targetFile.getName());
            
            // 更新缓存
            refreshAllCache();
            
            return targetFile;
        } catch (Exception e) {
            log.error("从temp复制到memes失败", e);
            return null;
        }
    }
    
    /**
     * 刷新所有缓存
     */
    public void refreshAllCache() {
        loadMemes();
    }
}
