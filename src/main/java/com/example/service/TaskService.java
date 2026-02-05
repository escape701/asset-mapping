package com.example.service;

import com.example.dto.TaskRequest;
import com.example.entity.Task;
import com.example.entity.TaskDomain;
import com.example.repository.TaskDomainRepository;
import com.example.repository.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 任务服务
 */
@Slf4j
@Service
public class TaskService {
    
    private final TaskRepository taskRepository;
    private final TaskDomainRepository taskDomainRepository;
    private final CrawlerService crawlerService;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public TaskService(TaskRepository taskRepository, 
                       TaskDomainRepository taskDomainRepository,
                       CrawlerService crawlerService,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskDomainRepository = taskDomainRepository;
        this.crawlerService = crawlerService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 获取所有任务（包含关联的域名数据）
     */
    public List<Task> findAll() {
        return taskRepository.findAllWithDomainsOrderByCreatedAtDesc();
    }
    
    /**
     * 根据ID获取任务（包含关联的域名数据）
     */
    public Optional<Task> findById(String id) {
        return taskRepository.findByIdWithDomains(id);
    }
    
    /**
     * 根据创建者获取任务
     */
    public List<Task> findByCreatedBy(Long userId) {
        return taskRepository.findByCreatedByOrderByCreatedAtDesc(userId);
    }
    
    /**
     * 创建任务
     */
    @Transactional
    public Task create(TaskRequest request, Long userId) {
        // 验证域名列表
        List<String> domains = request.getDomains();
        if (domains == null || domains.isEmpty()) {
            throw new RuntimeException("域名列表不能为空");
        }
        
        // 去重和清理域名（先规范化再去重）
        domains = domains.stream()
            .map(String::trim)
            .filter(d -> !d.isEmpty())
            .map(this::normalizeDomain)  // 先规范化
            .distinct()                   // 再去重
            .collect(Collectors.toList());
        
        if (domains.isEmpty()) {
            throw new RuntimeException("没有有效的域名");
        }
        
        // 创建任务
        Task task = new Task();
        task.setName(request.getName() != null ? request.getName() : generateTaskName(domains));
        task.setStatus(Task.STATUS_PENDING);
        task.setTotalDomains(domains.size());
        task.setCompletedDomains(0);
        task.setCreatedBy(userId);
        
        try {
            task.setDomainsInput(objectMapper.writeValueAsString(domains));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化域名列表失败", e);
        }
        
        Task savedTask = taskRepository.save(task);
        
        // 创建域名记录
        for (String domain : domains) {
            TaskDomain taskDomain = new TaskDomain();
            taskDomain.setTask(savedTask);
            taskDomain.setDomain(normalizeDomain(domain));
            taskDomain.setStatus(TaskDomain.STATUS_PENDING);
            taskDomainRepository.save(taskDomain);
        }
        
        log.info("创建任务: {}, 域名数: {}", savedTask.getId(), domains.size());
        
        return savedTask;
    }
    
    /**
     * 启动任务
     */
    @Transactional
    public Task start(String taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        
        if (task.isRunning()) {
            throw new RuntimeException("任务已在运行中");
        }
        
        if (task.isFinished()) {
            throw new RuntimeException("任务已完成，请创建新任务");
        }
        
        // 更新任务状态（使用 saveAndFlush 确保立即写入数据库）
        task.setStatus(Task.STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task = taskRepository.saveAndFlush(task);
        log.info("任务状态已更新为 RUNNING: {}", taskId);
        
        // 从 TaskDomain 获取规范化后的域名列表（确保与数据库一致）
        List<String> domains = taskDomainRepository.findByTaskId(taskId).stream()
            .map(TaskDomain::getDomain)
            .distinct()
            .collect(Collectors.toList());
        
        if (domains.isEmpty()) {
            // 回退：从原始输入解析
            try {
                domains = objectMapper.readValue(task.getDomainsInput(), new TypeReference<List<String>>() {});
                domains = domains.stream()
                    .map(this::normalizeDomain)
                    .distinct()
                    .collect(Collectors.toList());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("解析域名列表失败", e);
            }
        }
        
        // 启动爬虫
        try {
            crawlerService.startCrawl(task, domains);
            log.info("任务已启动: {}, 域名数: {}", taskId, domains.size());
        } catch (Exception e) {
            task.setStatus(Task.STATUS_FAILED);
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
            throw new RuntimeException("启动爬虫失败: " + e.getMessage(), e);
        }
        
        return task;
    }
    
    /**
     * 停止任务
     */
    @Transactional
    public Task stop(String taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        
        if (!task.isRunning()) {
            throw new RuntimeException("任务未在运行中");
        }
        
        // 停止爬虫进程
        crawlerService.stopCrawl(task);
        
        // 更新任务状态
        task.setStatus(Task.STATUS_STOPPED);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
        
        log.info("任务已停止: {}", taskId);
        
        return task;
    }
    
    /**
     * 删除任务
     */
    @Transactional
    public void delete(String taskId) {
        delete(taskId, true);
    }
    
    /**
     * 删除任务
     * @param taskId 任务ID
     * @param cleanupFiles 是否清理生成的文件
     */
    @Transactional
    public void delete(String taskId, boolean cleanupFiles) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        
        // 如果任务正在运行，先停止
        if (task.isRunning()) {
            crawlerService.stopCrawl(task);
        }
        
        // 清理输出文件
        if (cleanupFiles) {
            boolean cleaned = crawlerService.cleanupTaskOutput(taskId);
            if (cleaned) {
                log.info("已清理任务输出文件: {}", taskId);
            }
        }
        
        // 删除任务（级联删除域名记录）
        taskRepository.delete(task);
        
        log.info("任务已删除: {}", taskId);
    }
    
    /**
     * 批量删除任务
     * @param taskIds 任务ID列表
     * @param cleanupFiles 是否清理生成的文件
     * @return 成功删除的数量
     */
    @Transactional
    public int deleteMany(List<String> taskIds, boolean cleanupFiles) {
        int deleted = 0;
        for (String taskId : taskIds) {
            try {
                delete(taskId, cleanupFiles);
                deleted++;
            } catch (Exception e) {
                log.warn("删除任务失败: {}", taskId, e);
            }
        }
        return deleted;
    }
    
    /**
     * 删除所有已完成的任务
     * @param cleanupFiles 是否清理生成的文件
     * @return 成功删除的数量
     */
    @Transactional
    public int deleteCompletedTasks(boolean cleanupFiles) {
        List<Task> completedTasks = taskRepository.findAll().stream()
            .filter(Task::isFinished)
            .collect(Collectors.toList());
        
        int deleted = 0;
        for (Task task : completedTasks) {
            try {
                delete(task.getId(), cleanupFiles);
                deleted++;
            } catch (Exception e) {
                log.warn("删除已完成任务失败: {}", task.getId(), e);
            }
        }
        
        log.info("已删除 {} 个已完成任务", deleted);
        return deleted;
    }
    
    /**
     * 生成任务名称
     */
    private String generateTaskName(List<String> domains) {
        if (domains.size() == 1) {
            return domains.get(0);
        }
        return domains.get(0) + " 等 " + domains.size() + " 个域名";
    }
    
    /**
     * 规范化域名
     */
    private String normalizeDomain(String domain) {
        domain = domain.trim().toLowerCase();
        // 移除协议前缀
        if (domain.startsWith("http://")) {
            domain = domain.substring(7);
        } else if (domain.startsWith("https://")) {
            domain = domain.substring(8);
        }
        // 移除路径
        int slashIndex = domain.indexOf('/');
        if (slashIndex > 0) {
            domain = domain.substring(0, slashIndex);
        }
        // 移除末尾的点
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain;
    }
}
