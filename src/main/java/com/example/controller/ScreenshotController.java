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
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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
     * URL: /api/screenshots/file?path=xxx&taskId=yyy
     *
     * 新版 Python 输出格式示例（绝对路径）:
     * - screenshot_path: "D:/ysha/newwork/crawler-output/{taskId}/{domain}/screenshots/xxx.png"
     * - popup_login_screenshot_path: ["D:/ysha/newwork/crawler-output/{taskId}/{domain}/screenshots/xxx_login_popup.png"]
     *
     * crawl.json 可能存储相对路径如 "out/combined/census.gov/screenshots/xxx.png"，
     * 此时通过 taskId 参数在 {task-output-base}/{taskId}/ 下按文件名查找。
     *
     * 旧版兼容:
     * - screenshot_path: "screenshots\\www.baidu.com__20260203_181405.png"
     */
    @GetMapping("/file")
    public ResponseEntity<Resource> getScreenshotByPath(
            @RequestParam String path,
            @RequestParam(required = false) String taskId) {
        try {
            // 安全检查
            if (path.contains("..") || (taskId != null && taskId.contains(".."))) {
                log.warn("检测到路径遍历尝试: path={}, taskId={}", path, taskId);
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

            // 5. 如果提供了 taskId，在 {task-output-base}/{taskId}/ 下按文件名查找
            //    适用于 crawl.json 存储了不匹配的相对路径（如 out/combined/domain/screenshots/xxx.png）
            if (!file.exists() && taskId != null && !taskId.isBlank()) {
                String filename = Paths.get(normalizedPath).getFileName().toString();
                Path taskDir = Paths.get(taskOutputBasePath, taskId);
                if (Files.isDirectory(taskDir)) {
                    File found = findFileByName(taskDir, filename);
                    if (found != null) {
                        file = found;
                        log.debug("通过 taskId 查找找到截图: taskId={}, file={}", taskId, file.getAbsolutePath());
                    }
                }
            }

            if (!file.exists() || !file.isFile()) {
                log.debug("截图文件不存在: path={}, taskId={}, tried={}", path, taskId, file.getAbsolutePath());
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
            log.error("获取截图失败: path={}, taskId={}", path, taskId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 在目录树中按文件名查找（只搜索 screenshots 子目录以缩小范围）
     */
    private File findFileByName(Path rootDir, String targetFilename) {
        List<File> results = new ArrayList<>();
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!results.isEmpty()) return FileVisitResult.TERMINATE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(targetFilename)) {
                        results.add(file.toFile());
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("查找文件失败: dir={}, filename={}", rootDir, targetFilename, e);
        }
        return results.isEmpty() ? null : results.get(0);
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
