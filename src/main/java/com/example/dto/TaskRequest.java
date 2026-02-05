package com.example.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建任务请求
 */
@Data
public class TaskRequest {
    
    /**
     * 任务名称（可选）
     */
    private String name;
    
    /**
     * 域名列表
     */
    private List<String> domains;
    
    /**
     * 爬虫配置（可选）
     */
    private Map<String, Object> config;
}
