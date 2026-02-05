package com.example.dto;

import lombok.Data;

/**
 * AI对话响应
 */
@Data
public class ChatResponse {
    
    /**
     * AI回复内容
     */
    private String reply;
    
    /**
     * 使用的模型
     */
    private String model;
    
    /**
     * 响应时间（毫秒）
     */
    private Long latencyMs;
    
    public ChatResponse() {}
    
    public ChatResponse(String reply, String model, Long latencyMs) {
        this.reply = reply;
        this.model = model;
        this.latencyMs = latencyMs;
    }
}
