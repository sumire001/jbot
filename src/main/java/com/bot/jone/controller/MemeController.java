package com.bot.jone.controller;

import com.bot.jone.ioutils.MemeManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Meme管理API
 */
@RestController
@RequestMapping("/api/memes")
@Slf4j
public class MemeController {

    @Autowired
    private MemeManager memeManager;

    /**
     * 列出所有分类
     */
    @GetMapping("/categories")
    public Map<String, Object> listCategories() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);

        Map<String, List<Map<String, Object>>> categories = new LinkedHashMap<>();
        Map<String, Integer> stats = new LinkedHashMap<>();

        Map<String, List<File>> cache = getMemeCache();
        for (Map.Entry<String, List<File>> entry : cache.entrySet()) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (File file : entry.getValue()) {
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("name", file.getName());
                fileInfo.put("size", file.length());
                fileInfo.put("sizeKB", String.format("%.1f", file.length() / 1024.0));
                fileInfo.put("url", "/api/memes/file?category=" + entry.getKey() + "&name=" + file.getName());
                files.add(fileInfo);
            }
            categories.put(entry.getKey(), files);
            stats.put(entry.getKey(), entry.getValue().size());
        }

        result.put("categories", categories);
        result.put("stats", stats);
        result.put("totalCount", stats.values().stream().mapToInt(Integer::intValue).sum());
        return result;
    }

    /**
     * 创建新分类
     */
    @PostMapping("/category")
    public Map<String, Object> createCategory(@RequestParam String name) {
        Map<String, Object> result = new HashMap<>();
        if (memeManager.createCategory(name)) {
            result.put("success", true);
            result.put("message", "分类创建成功: " + name);
        } else {
            result.put("success", false);
            result.put("message", "分类创建失败");
        }
        return result;
    }

    /**
     * 上传表情包（用短ID命名）
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadMeme(@RequestParam("file") MultipartFile file,
                                           @RequestParam("category") String category) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 确保分类存在
            if (!memeManager.categoryExists(category)) {
                memeManager.createCategory(category);
            }

            // 获取原始文件名
            String originalName = file.getOriginalFilename();
            if (originalName == null) {
                result.put("success", false);
                result.put("message", "文件名无效");
                return result;
            }

            // 提取扩展名
            String extension = "";
            int lastDot = originalName.lastIndexOf('.');
            if (lastDot > 0) {
                extension = originalName.substring(lastDot + 1).toLowerCase();
            }
            if (extension.isEmpty()) {
                extension = "jpg";
            }

            // 用短ID命名保存
            File savedFile = memeManager.saveWithShortId(file.getBytes(), extension, category);

            if (savedFile != null) {
                result.put("success", true);
                result.put("message", "上传成功");
                result.put("file", Map.of(
                    "name", savedFile.getName(),
                    "size", savedFile.length(),
                    "category", category
                ));
            } else {
                result.put("success", false);
                result.put("message", "保存失败");
            }
        } catch (Exception e) {
            log.error("上传表情包失败", e);
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 删除表情包
     */
    @DeleteMapping("/file")
    public Map<String, Object> deleteMeme(@RequestParam String category,
                                          @RequestParam String name) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 安全检查：防止路径穿越
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                result.put("success", false);
                result.put("message", "非法的文件名");
                return result;
            }

            String categoryDir = memeManager.getImagesRootDirPublic() + "/" + category;
            File file = new File(categoryDir, name);

            // 验证文件确实在分类目录下
            String canonicalPath = file.getCanonicalPath();
            String expectedPrefix = new File(categoryDir).getCanonicalPath();
            if (!canonicalPath.startsWith(expectedPrefix)) {
                result.put("success", false);
                result.put("message", "路径超出允许范围");
                return result;
            }

            if (file.exists() && file.delete()) {
                memeManager.refreshAllCache();
                result.put("success", true);
                result.put("message", "删除成功");
            } else {
                result.put("success", false);
                result.put("message", "文件不存在或删除失败");
            }
        } catch (Exception e) {
            log.error("删除表情包失败", e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取文件资源
     */
    @GetMapping("/file")
    public ResponseEntity<Resource> getFile(@RequestParam String category,
                                              @RequestParam String name) {
        try {
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                return ResponseEntity.badRequest().build();
            }

            String categoryDir = memeManager.getImagesRootDirPublic() + "/" + category;
            File file = new File(categoryDir, name);

            if (!file.exists() || !file.isFile()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);
            String contentType = Files.probeContentType(Paths.get(file.getAbsolutePath()));
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .body(resource);
        } catch (Exception e) {
            log.error("获取文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取随机表情
     */
    @GetMapping("/random")
    public Map<String, Object> getRandomMeme(@RequestParam(required = false) String category) {
        Map<String, Object> result = new HashMap<>();
        try {
            File file;
            if (category == null || category.isEmpty()) {
                file = memeManager.getRandomMemeFromAll();
            } else {
                file = memeManager.getRandomMeme(category);
            }

            if (file != null) {
                result.put("success", true);
                result.put("name", file.getName());
                result.put("size", file.length());
                // 反推分类
                String parentName = file.getParentFile().getName();
                result.put("category", parentName);
                result.put("url", "/api/memes/file?category=" + parentName + "&name=" + file.getName());
            } else {
                result.put("success", false);
                result.put("message", "没有可用的表情包");
            }
        } catch (Exception e) {
            log.error("获取随机表情失败", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 刷新缓存
     */
    @PostMapping("/refresh")
    public Map<String, Object> refreshCache() {
        Map<String, Object> result = new HashMap<>();
        memeManager.refreshAllCache();
        result.put("success", true);
        result.put("message", "缓存已刷新");
        return result;
    }

    /**
     * 获取根目录
     */
    @GetMapping("/root-dir")
    public Map<String, Object> getRootDir() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("rootDir", memeManager.getImagesRootDirPublic());
        return result;
    }

    /**
     * 获取meme缓存的辅助方法（通过反射）
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<File>> getMemeCache() {
        try {
            java.lang.reflect.Field field = MemeManager.class.getDeclaredField("memeCache");
            field.setAccessible(true);
            return (Map<String, List<File>>) field.get(memeManager);
        } catch (Exception e) {
            log.error("获取meme缓存失败", e);
            return new HashMap<>();
        }
    }
}