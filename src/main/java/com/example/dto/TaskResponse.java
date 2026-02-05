package com.example.dto;

import com.example.entity.Task;
import com.example.entity.TaskDomain;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务响应
 */
@Data
public class TaskResponse {
    
    private String taskId;  // 兼容前端的 taskId
    private String id;
    private String name;
    private String status;
    private Integer totalDomains;
    private Integer completedDomains;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<DomainInfo> domains;
    
    /**
     * 域名信息（包含 discovery 和 crawl 数据）
     */
    @Data
    public static class DomainInfo {
        private Long id;
        private String domain;
        private String status;
        private String landingUrl;
        private Integer subdomainCount;
        private Integer urlCount;
        private Integer visitedCount;
        private Integer loginCount;
        private Integer failedCount;
        
        // 进程信息（并发模式）
        private Integer pid;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        
        // 完整的 discovery 和 crawl 数据（从 JSON 文件读取）
        private Map<String, Object> discovery;
        private Map<String, Object> crawl;
        
        public static DomainInfo fromEntity(TaskDomain entity) {
            DomainInfo info = new DomainInfo();
            info.setId(entity.getId());
            info.setDomain(entity.getDomain());
            info.setStatus(entity.getStatus());
            info.setLandingUrl(entity.getLandingUrl());
            info.setSubdomainCount(entity.getSubdomainCount());
            info.setUrlCount(entity.getUrlCount());
            info.setVisitedCount(entity.getVisitedCount());
            info.setLoginCount(entity.getLoginCount());
            info.setFailedCount(entity.getFailedCount());
            info.setPid(entity.getPid());
            info.setStartedAt(entity.getStartedAt());
            info.setFinishedAt(entity.getFinishedAt());
            return info;
        }
    }
    
    /**
     * 从实体转换
     */
    public static TaskResponse fromEntity(Task task) {
        TaskResponse response = new TaskResponse();
        response.setId(task.getId());
        response.setTaskId(task.getId()); // 兼容前端
        response.setName(task.getName());
        response.setStatus(task.getStatus());
        response.setTotalDomains(task.getTotalDomains());
        response.setCompletedDomains(task.getCompletedDomains());
        response.setCreatedAt(task.getCreatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        
        if (task.getTaskDomains() != null) {
            response.setDomains(task.getTaskDomains().stream()
                .map(DomainInfo::fromEntity)
                .collect(Collectors.toList()));
        } else {
            response.setDomains(new ArrayList<>());
        }
        
        return response;
    }
}
