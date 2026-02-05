package com.example.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 爬虫任务实体类
 */
@Data
@Entity
@Table(name = "tasks")
public class Task {
    
    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;
    
    @Column(length = 255)
    private String name;
    
    @Column(nullable = false, length = 20)
    private String status = "pending"; // pending, running, completed, failed, stopped
    
    @Column(columnDefinition = "TEXT")
    private String domainsInput; // JSON数组格式
    
    @Column(nullable = false)
    private Integer totalDomains = 0;
    
    @Column(nullable = false)
    private Integer completedDomains = 0;
    
    // Python 进程信息
    private Integer pid;
    
    @Column(length = 500)
    private String configPath;
    
    @Column(length = 500)
    private String outputDir;
    
    // 时间戳
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime startedAt;
    
    private LocalDateTime finishedAt;
    
    // 创建者
    private Long createdBy;
    
    // 关联的域名结果
    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskDomain> taskDomains = new ArrayList<>();
    
    // 任务状态常量
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_STOPPED = "stopped";
    
    /**
     * 检查任务是否正在运行
     */
    public boolean isRunning() {
        return STATUS_RUNNING.equals(this.status);
    }
    
    /**
     * 检查任务是否已完成（包括成功、失败、停止）
     */
    public boolean isFinished() {
        return STATUS_COMPLETED.equals(this.status) 
            || STATUS_FAILED.equals(this.status) 
            || STATUS_STOPPED.equals(this.status);
    }
}
