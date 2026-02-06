package com.example.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * AI对话请求
 */
@Data
public class ChatRequest {
    
    /**
     * 任务ID
     */
    private String taskId;
    
    /**
     * 当前选中的域名
     */
    private String domain;
    
    /**
     * 用户消息
     */
    private String message;
    
    /**
     * 前端构建的上下文信息
     */
    private String context;
    
    /**
     * 选择的AI模型
     */
    private String model;
    
    /**
     * 对话历史
     */
    private List<Map<String, String>> history;
}
