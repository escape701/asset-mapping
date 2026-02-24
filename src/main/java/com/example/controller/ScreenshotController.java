package com.example.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Paths;

/**
 * 截图文件服务控制器
 * 
 * 新版截图存储位置: {task-output-base}/{taskId}/{domain}/screenshots/xxx.png
 * 截图路径格式 (crawl.json中): D:/ysha/newwork/crawler-output/{taskId}/{domain}/screenshots/xxx.png
 * 
 * 兼容旧版: D:/ysha/login_crawler/screenshots/xxx.png
 */
@Slf4j
@RestController
@RequestMapping("/api/screenshots")
public class ScreenshotController {
    
    @Value("${screenshots.base-dir:../login_crawler/screenshots}")
    private String screenshotBaseDir;
    
    @Value("${crawler.project-path:../login_crawler}")
    private String crawlerProjectPath;
    
    @Value("${crawler.task-output-base:./crawler-output}")
    private String taskOutputBasePath;
    
    /**
     * 根据完整路径获取截图（用于 crawl.json 中的 screenshot_path）
     * URL: /api/screenshots/file?path=xxx
     * 
     * 新版 Python 输出格式示例（绝对路径）:
     * - screenshot_path: "D:/ysha/newwork/crawler-output/{taskId}/{domain}/screenshots/xxx.png"
     * - popup_login_screenshot_path: ["D:/ysha/newwork/crawler-output/{taskId}/{domain}/screenshots/xxx_login_popup.png"]
     * 
     * 旧版兼容:
     * - screenshot_path: "screenshots\\www.baidu.com__20260203_181405.png"
     */
    @GetMapping("/file")
    public ResponseEntity<Resource> getScreenshotByPath(@RequestParam String path) {
        try {
            // 安全检查
            if (path.contains("..")) {
                log.warn("检测到路径遍历尝试: {}", path);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // 标准化路径分隔符
            String normalizedPath = path.replace("\\", "/");
            
            File file = null;
            
            // 尝试不同的基础路径解析
            // 1. 如果是绝对路径（新版格式）
            if (Paths.get(normalizedPath).isAbsolute()) {
                file = new File(normalizedPath);
                if (file.exists()) {
                    log.debug("使用绝对路径找到截图: {}", normalizedPath);
                }
            }
            
            // 2. 在任务输出目录下查找（新版格式的相对路径）
            if (file == null || !file.exists()) {
                file = Paths.get(taskOutputBasePath, normalizedPath).toFile();
            }
            
            // 3. 在 Python 项目目录下查找 (旧版: screenshots/xxx.png)
            if (!file.exists()) {
                file = Paths.get(crawlerProjectPath, normalizedPath).toFile();
            }
            
            // 4. 直接在旧版截图目录查找文件名
            if (!file.exists()) {
                String filename = Paths.get(normalizedPath).getFileName().toString();
                file = Paths.get(screenshotBaseDir, filename).toFile();
            }
            
            if (!file.exists() || !file.isFile()) {
                log.debug("截图文件不存在: path={}, tried={}", path, file.getAbsolutePath());
                return ResponseEntity.notFound().build();
            }
            
            // 验证文件在允许的目录内
            String canonicalPath = file.getCanonicalPath();
            String allowedDir1 = new File(crawlerProjectPath).getCanonicalPath();
            String allowedDir2 = new File(screenshotBaseDir).getCanonicalPath();
            String allowedDir3 = new File(taskOutputBasePath).getCanonicalPath();
            
            if (!canonicalPath.startsWith(allowedDir1) && 
                !canonicalPath.startsWith(allowedDir2) && 
                !canonicalPath.startsWith(allowedDir3)) {
                log.warn("文件路径越界: {} not in allowed directories", canonicalPath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            return serveImageFile(file);
                
        } catch (Exception e) {
            log.error("获取截图失败: path={}", path, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 提供图片文件
     */
    private ResponseEntity<Resource> serveImageFile(File file) {
        String filename = file.getName().toLowerCase();
        MediaType mediaType = MediaType.IMAGE_PNG;
        
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
        } else if (filename.endsWith(".gif")) {
            mediaType = MediaType.IMAGE_GIF;
        } else if (filename.endsWith(".webp")) {
            mediaType = MediaType.parseMediaType("image/webp");
        }
        
        Resource resource = new FileSystemResource(file);
        
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "max-age=3600") // 缓存1小时
            .body(resource);
    }
}
