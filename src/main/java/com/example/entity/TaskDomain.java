package com.example.entity;

import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 任务域名结果实体类
 */
@Data
@Entity
@Table(name = "task_domains")
public class TaskDomain {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;
    
    @Column(nullable = false, length = 255)
    private String domain;
    
    @Column(nullable = false, length = 20)
    private String status = "pending"; // pending, discovering, crawling, completed, failed
    
    // 结果统计
    @Column(length = 500)
    private String landingUrl;
    
    @Column(nullable = false)
    private Integer subdomainCount = 0;
    
    @Column(nullable = false)
    private Integer urlCount = 0;
    
    @Column(nullable = false)
    private Integer visitedCount = 0;
    
    @Column(nullable = false)
    private Integer loginCount = 0;
    
    @Column(nullable = false)
    private Integer failedCount = 0;
    
    // JSON 文件路径
    @Column(length = 500)
    private String discoveryOutput;
    
    @Column(length = 500)
    private String crawlOutput;
    
    // 进程信息（用于并发执行时追踪每个域名的爬虫进程）
    @Column
    private Integer pid;
    
    // 域名独立的输出目录
    @Column(length = 500)
    private String outputDir;
    
    // 域名独立的日志文件路径
    @Column(length = 500)
    private String logFile;
    
    // 开始时间和结束时间
    @Column
    private LocalDateTime startedAt;
    
    @Column
    private LocalDateTime finishedAt;
    
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    // 状态常量
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DISCOVERING = "discovering";
    public static final String STATUS_CRAWLING = "crawling";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
}
