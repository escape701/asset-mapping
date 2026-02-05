package com.example.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * LLM配置实体类
 */
@Data
@Entity
@Table(name = "llm_configs")
public class LlmConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String name;
    
    @Column(nullable = false, length = 50)
    private String provider; // google, openai
    
    @Column(nullable = false, length = 500)
    private String apiKey;
    
    @Column(nullable = false, length = 100)
    private String model;
    
    @Column(nullable = false)
    private Boolean isActive = false;
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    // Provider 常量
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_OPENAI = "openai";
}
