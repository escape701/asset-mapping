package com.example.dto;

import com.example.entity.LlmConfig;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM配置响应
 */
@Data
public class LlmConfigResponse {
    
    private Long id;
    private String name;
    private String provider;
    private String apiKey; // 部分隐藏
    private String model;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    /**
     * 从实体转换（隐藏 API Key）
     */
    public static LlmConfigResponse fromEntity(LlmConfig config) {
        LlmConfigResponse response = new LlmConfigResponse();
        response.setId(config.getId());
        response.setName(config.getName());
        response.setProvider(config.getProvider());
        response.setApiKey(maskApiKey(config.getApiKey()));
        response.setModel(config.getModel());
        response.setIsActive(config.getIsActive());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }
    
    /**
     * 遮蔽 API Key
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
