package com.bot.jone.ioutils;

import com.bot.jone.config.ImagesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

@Component
@Slf4j
public class ImageCompressor {

    @Autowired
    private ImagesConfig imagesConfig;

    // 超过此大小（字节）的图片才会被压缩
    private static final long SIZE_THRESHOLD = 1024 * 1024; // 1MB

    // 超过此分辨率的图片才会被缩小
    private static final int MAX_DIMENSION = 1600;

    // JPEG压缩质量（0.0-1.0）
    private static final float JPEG_QUALITY = 0.75f;

    /**
     * 压缩图片（如果需要）
     * @param imagePath 原图路径
     * @return 压缩后的图片路径；如果不需要压缩，返回原图路径
     */
    public String compressIfNeeded(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return imagePath;
        }

        File sourceFile = new File(imagePath);
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            log.warn("[图片压缩] 文件不存在: {}", imagePath);
            return imagePath;
        }

        long fileSize = sourceFile.length();
        String fileName = sourceFile.getName();
        String extension = getExtension(fileName).toLowerCase();

        // GIF不压缩（可能是动画）
        if ("gif".equals(extension)) {
            log.info("[图片压缩] 跳过GIF文件（可能是动画）: {} ({}KB)",
                fileName, fileSize / 1024);
            return imagePath;
        }

        // 检查是否需要压缩
        if (fileSize < SIZE_THRESHOLD) {
            // 小图片，检查分辨率
            try {
                BufferedImage img = ImageIO.read(sourceFile);
                if (img != null) {
                    int maxDim = Math.max(img.getWidth(), img.getHeight());
                    if (maxDim < MAX_DIMENSION) {
                        log.info("[图片压缩] 无需压缩: {} ({}KB, {}x{})",
                            fileName, fileSize / 1024, img.getWidth(), img.getHeight());
                        return imagePath;
                    }
                }
            } catch (Exception e) {
                log.warn("[图片压缩] 读取图片信息失败，不压缩: {}", fileName, e);
                return imagePath;
            }
        }

        // 需要压缩
        log.info("[图片压缩] 开始压缩: {} ({}KB)", fileName, fileSize / 1024);
        return compressImage(sourceFile, extension);
    }

    /**
     * 执行图片压缩
     */
    private String compressImage(File sourceFile, String extension) {
        try {
            // 读取原图
            BufferedImage originalImage = ImageIO.read(sourceFile);
            if (originalImage == null) {
                log.warn("[图片压缩] 无法读取图片: {}", sourceFile.getName());
                return sourceFile.getAbsolutePath();
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            int maxDim = Math.max(originalWidth, originalHeight);

            // 计算缩放比例
            double scale = 1.0;
            if (maxDim > MAX_DIMENSION) {
                scale = (double) MAX_DIMENSION / maxDim;
            }

            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);

            // 缩放图片
            BufferedImage resizedImage;
            if (scale < 1.0) {
                java.awt.Image scaled = originalImage.getScaledInstance(
                    newWidth, newHeight, java.awt.Image.SCALE_SMOOTH);
                resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2d = resizedImage.createGraphics();
                g2d.drawImage(scaled, 0, 0, null);
                g2d.dispose();
                log.info("[图片压缩] 分辨率: {}x{} -> {}x{} (缩放比例: {})",
                    originalWidth, originalHeight, newWidth, newHeight,
                    String.format("%.2f", scale));
            } else {
                // 不需要缩放，直接用原图，但需要转为RGB以支持JPEG
                resizedImage = new BufferedImage(
                    originalImage.getWidth(), originalImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2d = resizedImage.createGraphics();
                g2d.drawImage(originalImage, 0, 0, null);
                g2d.dispose();
            }

            // 确保压缩输出目录存在
            File compressDir = new File(imagesConfig.getTempDir());
            if (!compressDir.exists()) {
                compressDir.mkdirs();
            }

            // 生成压缩后的文件名（统一用jpg以获得最好压缩）
            String baseName = sourceFile.getName();
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot > 0) {
                baseName = baseName.substring(0, lastDot);
            }
            String outputFileName = "compressed_" + baseName + "_" + System.currentTimeMillis() + ".jpg";
            File outputFile = new File(compressDir, outputFileName);

            // 以JPEG格式写出（获得最好压缩率）
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                log.warn("[图片压缩] 系统没有JPEG编码器，使用默认方式写入");
                ImageIO.write(resizedImage, "jpg", outputFile);
            } else {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);

                try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(resizedImage, null, null), param);
                }
                writer.dispose();
            }

            long compressedSize = outputFile.length();
            long originalSize = sourceFile.length();
            double ratio = originalSize > 0 ? (100.0 * compressedSize / originalSize) : 100;

            log.info("[图片压缩] 完成: {}KB -> {}KB (压缩率: {}%)",
                originalSize / 1024, compressedSize / 1024,
                String.format("%.1f", ratio));

            return outputFile.getAbsolutePath();

        } catch (Exception e) {
            log.error("[图片压缩] 压缩失败，使用原图: {}", sourceFile.getName(), e);
            return sourceFile.getAbsolutePath();
        }
    }

    /**
     * 直接从字节数据压缩图片并保存到指定目录
     * 用于"随机XX图"命令：下载后直接压缩到 pixiv_temp，避免先存原图再读压缩
     * @param imageData 原图字节数据
     * @param baseFileName 基础文件名（不含扩展名）
     * @param targetDir 目标目录路径（如 pixiv_temp）
     * @return 压缩后的文件路径；如果失败或小图无需压缩，返回 null（调用方需处理原图保存）
     */
    public String compressBytesToDir(byte[] imageData, String baseFileName, String targetDir) {
        if (imageData == null || imageData.length == 0) {
            return null;
        }

        try {
            // 读取图片
            BufferedImage originalImage = ImageIO.read(new java.io.ByteArrayInputStream(imageData));
            if (originalImage == null) {
                log.warn("[图片压缩] 无法解析图片数据 ({}KB)", imageData.length / 1024);
                return null;
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            int maxDim = Math.max(originalWidth, originalHeight);

            // 检查是否需要压缩
            boolean needResize = maxDim > MAX_DIMENSION;
            boolean needQuality = imageData.length > SIZE_THRESHOLD;

            if (!needResize && !needQuality) {
                // 小图不需要压缩，直接返回 null 让调用方存原图
                log.info("[图片压缩] 小图无需压缩: {}KB, {}x{}",
                    imageData.length / 1024, originalWidth, originalHeight);
                return null;
            }

            // 计算缩放比例
            double scale = 1.0;
            int newWidth = originalWidth;
            int newHeight = originalHeight;

            if (needResize) {
                scale = (double) MAX_DIMENSION / maxDim;
                newWidth = (int) (originalWidth * scale);
                newHeight = (int) (originalHeight * scale);
            }

            // 缩放/重编码图片
            BufferedImage outputImage;
            if (scale < 1.0) {
                java.awt.Image scaled = originalImage.getScaledInstance(
                    newWidth, newHeight, java.awt.Image.SCALE_SMOOTH);
                outputImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2d = outputImage.createGraphics();
                g2d.drawImage(scaled, 0, 0, null);
                g2d.dispose();
                log.info("[图片压缩] 分辨率: {}x{} -> {}x{} (缩放: {})",
                    originalWidth, originalHeight, newWidth, newHeight,
                    String.format("%.2f", scale));
            } else {
                // 只做质量压缩，不改分辨率
                outputImage = new BufferedImage(
                    originalImage.getWidth(), originalImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2d = outputImage.createGraphics();
                g2d.drawImage(originalImage, 0, 0, null);
                g2d.dispose();
            }

            // 确保目标目录存在
            File dir = new File(targetDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 生成压缩后文件名（存到 pixiv_temp）
            String outputFileName = "pixiv_" + baseFileName + "_" + System.currentTimeMillis() + ".jpg";
            File outputFile = new File(dir, outputFileName);

            // 以 JPEG 格式写出，带质量压缩
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                log.warn("[图片压缩] 系统没有JPEG编码器，使用默认方式写入");
                ImageIO.write(outputImage, "jpg", outputFile);
            } else {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);

                try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(outputImage, null, null), param);
                }
                writer.dispose();
            }

            long compressedSize = outputFile.length();
            double ratio = imageData.length > 0
                ? (100.0 * compressedSize / imageData.length)
                : 100;

            log.info("[图片压缩] 完成: {}KB -> {}KB (压缩率: {}%) -> {}",
                imageData.length / 1024, compressedSize / 1024,
                String.format("%.1f", ratio), outputFile.getName());

            return outputFile.getAbsolutePath();

        } catch (Exception e) {
            log.error("[图片压缩] 从字节数据压缩失败", e);
            return null;
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }
}
