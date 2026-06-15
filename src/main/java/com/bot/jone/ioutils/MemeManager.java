package com.bot.jone.ioutils;

import com.bot.jone.config.ImagesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MemeManager {
    
    @Autowired
    private ImagesConfig imagesConfig;

    private Map<String, List<File>> memeCache = new ConcurrentHashMap<>();
    private String defaultCategory = "memes";
    private Random random = new Random();

    private String getImagesRootDir() {
        return imagesConfig.getRootDir();
    }

    private String getTempDir() {
        return imagesConfig.getTempDir();
    }
    
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
        File temp = new File(getTempDir());
        if (!temp.exists()) {
            temp.mkdirs();
            log.info("创建临时目录: {}", temp.getAbsolutePath());
        }
    }
    
    private void loadMemes() {
        File imagesRoot = new File(getImagesRootDir());
        
        if (!imagesRoot.exists() || !imagesRoot.isDirectory()) {
            log.warn("图片根目录不存在: {}", imagesRoot.getAbsolutePath());
            return;
        }
        
        File[] categoryDirs = imagesRoot.listFiles(File::isDirectory);
        if (categoryDirs == null || categoryDirs.length == 0) {
            log.warn("没有找到任何分类目录");
            return;
        }
        
        memeCache.clear();
        List<File> allFiles = new ArrayList<>();
        
        for (File categoryDir : categoryDirs) {
            String categoryName = categoryDir.getName();
            if ("temp".equals(categoryName)) {
                continue;
            }
            
            File[] files = categoryDir.listFiles();
            if (files != null && files.length > 0) {
                List<File> fileList = Arrays.asList(files);
                memeCache.put(categoryName, fileList);
                allFiles.addAll(fileList);
                log.info("加载分类 [{}]: {} 个文件", categoryName, fileList.size());
            }
        }
        
        memeCache.put("all", allFiles);
        log.info("表情包加载完成，共 {} 个文件", allFiles.size());
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
        List<File> files = memeCache.get("memes");
        if (files == null || files.isEmpty()) {
            log.warn("没有可用的表情包");
            return null;
        }
        return files.get(random.nextInt(files.size()));
    }
    
    /**
     * 保存图片到默认表情包目录（永久保存）
     * @param imageData 图片字节数据
     * @param category 分类名称（已废弃，保留兼容性）
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveMeme(byte[] imageData, String category, String fileName, String extension) {
        return saveMemeToCategory(imageData, fileName, extension, defaultCategory);
    }
    
    /**
     * 保存图片到指定分类目录
     * @param imageData 图片字节数据
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @param category 分类名称（如 memes, pjsk_images 等）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveMemeToCategory(byte[] imageData, String fileName, String extension, String category) {
        String categoryDir = getImagesRootDir() + "/" + category;
        return saveToDirectory(imageData, categoryDir, fileName, extension);
    }
    
    /**
     * 保存图片到temp目录（临时保存）
     * @param imageData 图片字节数据
     * @param fileName 文件名（不含扩展名）
     * @param extension 文件扩展名（如 jpg, png, gif）
     * @return 保存后的文件对象，失败返回 null
     */
    public File saveToTemp(byte[] imageData, String fileName, String extension) {
        return saveToDirectory(imageData, getTempDir(), fileName, extension);
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
            
            // 如果不是保存到temp目录，更新缓存
            if (!targetDir.equals(getTempDir())) {
                refreshAllCache();
            }
            
            return targetFile;
        } catch (Exception e) {
            log.error("保存文件失败", e);
            return null;
        }
    }
    
    /**
     * 从temp目录复制文件到默认分类目录
     * @param tempFilePath temp中的文件路径
     * @param userId 用户ID（用于生成stolen_格式的文件名）
     * @return 复制后的文件对象，失败返回 null
     */
    public File copyFromTempToMemes(String tempFilePath, Long userId) {
        return copyFromTempToCategory(tempFilePath, userId, defaultCategory);
    }
    
    /**
     * 从temp目录复制文件到指定分类目录
     * @param tempFilePath temp中的文件路径
     * @param userId 用户ID（用于生成stolen_格式的文件名）
     * @param category 分类名称（如 memes, pjsk_images 等）
     * @return 复制后的文件对象，失败返回 null
     */
    public File copyFromTempToCategory(String tempFilePath, Long userId, String category) {
        try {
            File sourceFile = new File(tempFilePath);
            if (!sourceFile.exists()) {
                log.error("源文件不存在: {}", tempFilePath);
                return null;
            }
            
            // 构建目标分类目录路径
            String categoryDirPath = getImagesRootDir() + "/" + category;
            File categoryDir = new File(categoryDirPath);
            
            // 确保分类目录存在
            if (!categoryDir.exists()) {
                categoryDir.mkdirs();
                log.info("创建分类目录: {}", categoryDir.getAbsolutePath());
            }
            
            // 提取原文件的扩展名
            String originalName = sourceFile.getName();
            String extension = "jpg";
            int lastDotIndex = originalName.lastIndexOf('.');
            if (lastDotIndex != -1 && lastDotIndex < originalName.length() - 1) {
                extension = originalName.substring(lastDotIndex + 1);
            }
            
            // 生成新的文件名：stolen_{userId}_{时间戳}.{扩展名}
            String newFileName = "stolen_" + userId + "_" + System.currentTimeMillis() + "." + extension;
            File targetFile = new File(categoryDir, newFileName);
            
            // 安全检查
            String canonicalTarget = targetFile.getCanonicalPath();
            String expectedPrefix = categoryDir.getCanonicalPath();
            
            if (!canonicalTarget.startsWith(expectedPrefix)) {
                log.error("目标路径超出允许范围: {}", canonicalTarget);
                return null;
            }
            
            // 复制文件
            java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            log.info("从temp复制到分类[{}]成功: {} -> {}", category, sourceFile.getName(), targetFile.getName());
            
            // 更新缓存
            refreshAllCache();
            
            return targetFile;
        } catch (Exception e) {
            log.error("从temp复制到分类[{}]失败", category, e);
            return null;
        }
    }
    
    /**
     * 检查分类是否存在
     * @param category 分类名称
     * @return 是否存在
     */
    public boolean categoryExists(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        File categoryDir = new File(getImagesRootDir() + "/" + category);
        return categoryDir.exists() && categoryDir.isDirectory();
    }
    
    /**
     * 创建新分类目录
     * @param category 分类名称
     * @return 是否创建成功
     */
    public boolean createCategory(String category) {
        if (category == null || category.isEmpty()) {
            return false;
        }
        File categoryDir = new File(getImagesRootDir() + "/" + category);
        if (categoryDir.exists()) {
            return true;
        }
        boolean created = categoryDir.mkdirs();
        if (created) {
            log.info("创建分类目录: {}", categoryDir.getAbsolutePath());
            refreshAllCache();
        }
        return created;
    }
    
    /**
     * 刷新所有缓存
     */
    public void refreshAllCache() {
        loadMemes();
    }
}
