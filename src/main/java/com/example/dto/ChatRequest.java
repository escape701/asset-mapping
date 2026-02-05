package com.example.dto;

import lombok.Data;

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
}
