package com.example.dto;

import lombok.Data;

/**
 * LLM配置请求
 */
@Data
public class LlmConfigRequest {
    
    /**
     * 配置名称
     */
    private String name;
    
    /**
     * 提供商: google, openai
     */
    private String provider;
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 模型名称
     */
    private String model;
}
