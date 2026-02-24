package com.example.controller;

import com.example.dto.ApiResponse;
import com.example.dto.TaskRequest;
import com.example.dto.TaskResponse;
import com.example.entity.Task;
import com.example.service.TaskService;
import com.example.service.CrawlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    
    private final TaskService taskService;
    private final CrawlerService crawlerService;
    
    public TaskController(TaskService taskService, CrawlerService crawlerService) {
        this.taskService = taskService;
        this.crawlerService = crawlerService;
    }
    
    /**
     * 获取所有任务
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getAll() {
        List<Task> tasks = taskService.findAll();
        List<TaskResponse> responses = tasks.stream()
            .map(TaskResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    
    /**
     * 根据ID获取任务（包含完整的 discovery 和 crawl 数据）
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskResponse>> getById(@PathVariable String id) {
        return taskService.findById(id)
            .map(task -> {
                TaskResponse response = TaskResponse.fromEntity(task);
                
                // 为每个域名填充完整的 discovery 和 crawl 数据
                if (response.getDomains() != null) {
                    for (TaskResponse.DomainInfo domainInfo : response.getDomains()) {
                        try {
                            Map<String, Object> discovery = crawlerService.readDiscoveryResult(id, domainInfo.getDomain());
                            Map<String, Object> crawl = crawlerService.readCrawlResult(id, domainInfo.getDomain());
                            domainInfo.setDiscovery(discovery);
                            domainInfo.setCrawl(crawl);
                        } catch (Exception e) {
                            // 忽略读取失败
                        }
                    }
                }
                
                return ResponseEntity.ok(ApiResponse.success(response));
            })
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 直接从 Python 输出文件读取结果（用于演示/测试）
     * 无需数据库中存在任务记录
     */
    @GetMapping("/demo/{domain}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<TaskResponse>> getDemoByDomain(@PathVariable String domain) {
        try {
            // 读取 Python 输出的结果
            Map<String, Object> discovery = crawlerService.readDiscoveryResult("demo", domain);
            Map<String, Object> crawl = crawlerService.readCrawlResult("demo", domain);
            
            if (discovery.isEmpty() && crawl.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            // 构建响应
            TaskResponse response = new TaskResponse();
            response.setId("demo-" + domain);
            response.setTaskId("demo-" + domain);
            response.setStatus("completed");
            response.setTotalDomains(1);
            response.setCompletedDomains(1);
            response.setCreatedAt(java.time.LocalDateTime.now());
            
            // 从 crawl 结果获取统计信息
            Map<String, Object> stats = (Map<String, Object>) crawl.get("statistics");
            int visitedCount = 0;
            int failedCount = 0;
            if (stats != null) {
                visitedCount = getIntValue(stats, "total_visited");
                failedCount = getIntValue(stats, "total_failed");
            }
            
            // 从 discovery 结果获取子域名数量
            List<?> subdomains = (List<?>) discovery.get("subdomains");
            int subdomainCount = subdomains != null ? subdomains.size() : 0;
            
            // 从 crawl 结果计算登录页数量
            List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawl.get("visited_pages");
            int loginCount = 0;
            if (visitedPages != null) {
                for (Map<String, Object> page : visitedPages) {
                    if (CrawlerService.isLoginPage(page.get("is_login_page"))) {
                        loginCount++;
                    }
                }
            }
            
            // 获取 landing URL
            Map<String, Object> metadata = (Map<String, Object>) crawl.get("metadata");
            String landingUrl = metadata != null ? (String) metadata.get("start_url") : null;
            
            // 创建域名信息
            TaskResponse.DomainInfo domainInfo = new TaskResponse.DomainInfo();
            domainInfo.setDomain(domain);
            domainInfo.setStatus("completed");
            domainInfo.setLandingUrl(landingUrl);
            domainInfo.setSubdomainCount(subdomainCount);
            domainInfo.setVisitedCount(visitedCount);
            domainInfo.setLoginCount(loginCount);
            domainInfo.setFailedCount(failedCount);
            domainInfo.setDiscovery(discovery);
            domainInfo.setCrawl(crawl);
            
            response.setDomains(java.util.Collections.singletonList(domainInfo));
            
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 安全获取整数值
     */
    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
    
    /**
     * 创建任务
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> create(
            @RequestBody TaskRequest request,
            HttpSession session) {
        try {
            // 获取当前用户ID（从session获取）
            Long userId = (Long) session.getAttribute("userId");
            
            Task task = taskService.create(request, userId);
            return ResponseEntity.ok(ApiResponse.success(TaskResponse.fromEntity(task)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 启动任务
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<TaskResponse>> start(@PathVariable String id) {
        try {
            Task task = taskService.start(id);
            return ResponseEntity.ok(ApiResponse.success(TaskResponse.fromEntity(task)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 停止任务
     */
    @PostMapping("/{id}/stop")
    public ResponseEntity<ApiResponse<TaskResponse>> stop(@PathVariable String id) {
        try {
            Task task = taskService.stop(id);
            return ResponseEntity.ok(ApiResponse.success(TaskResponse.fromEntity(task)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 删除任务
     * @param id 任务ID
     * @param cleanupFiles 是否清理生成的文件（默认true）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String id,
            @RequestParam(defaultValue = "true") boolean cleanupFiles) {
        try {
            taskService.delete(id, cleanupFiles);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 清理已完成的任务
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cleanupCompletedTasks(
            @RequestParam(defaultValue = "true") boolean cleanupFiles) {
        try {
            int deleted = taskService.deleteCompletedTasks(cleanupFiles);
            
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("deleted", deleted);
            
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 获取任务的实时日志
     * @param id 任务ID
     * @param fromLine 从第几行开始（默认0）
     * @param lines 读取多少行（默认100，-1表示全部）
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int fromLine,
            @RequestParam(defaultValue = "100") int lines) {
        try {
            Map<String, Object> logs = crawlerService.getTaskLogs(id, fromLine, lines);
            return ResponseEntity.ok(ApiResponse.success(logs));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 获取任务的最新日志（从末尾读取）
     * @param id 任务ID
     * @param lines 读取最后多少行（默认50）
     */
    @GetMapping("/{id}/logs/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int lines) {
        try {
            Map<String, Object> logs = crawlerService.getLatestLogs(id, lines);
            return ResponseEntity.ok(ApiResponse.success(logs));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 手动同步任务进度（从 Python 输出文件读取）
     */
    @PostMapping("/{id}/sync")
    public ResponseEntity<ApiResponse<TaskResponse>> syncProgress(@PathVariable String id) {
        try {
            return taskService.findById(id)
                .map(task -> {
                    crawlerService.syncTaskProgress(task);
                    // 重新加载任务以获取更新后的数据
                    Task updatedTask = taskService.findById(id).orElse(task);
                    TaskResponse response = TaskResponse.fromEntity(updatedTask);
                    
                    // 填充 discovery 和 crawl 数据
                    if (response.getDomains() != null) {
                        for (TaskResponse.DomainInfo domainInfo : response.getDomains()) {
                            try {
                                Map<String, Object> discovery = crawlerService.readDiscoveryResult(id, domainInfo.getDomain());
                                Map<String, Object> crawl = crawlerService.readCrawlResult(id, domainInfo.getDomain());
                                domainInfo.setDiscovery(discovery);
                                domainInfo.setCrawl(crawl);
                            } catch (Exception e) {
                                // 忽略读取失败
                            }
                        }
                    }
                    
                    return ResponseEntity.ok(ApiResponse.success(response));
                })
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
