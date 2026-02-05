package com.example.controller;

import com.example.dto.ApiResponse;
import com.example.dto.LlmConfigRequest;
import com.example.dto.LlmConfigResponse;
import com.example.entity.LlmConfig;
import com.example.service.LlmConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM配置控制器
 */
@RestController
@RequestMapping("/api/llm-configs")
public class LlmConfigController {
    
    private final LlmConfigService configService;
    
    public LlmConfigController(LlmConfigService configService) {
        this.configService = configService;
    }
    
    /**
     * 获取所有配置
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<LlmConfigResponse>>> getAll() {
        List<LlmConfig> configs = configService.findAll();
        List<LlmConfigResponse> responses = configs.stream()
            .map(LlmConfigResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
    
    /**
     * 获取激活的配置
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<LlmConfigResponse>> getActive() {
        return configService.findActive()
            .map(config -> ResponseEntity.ok(ApiResponse.success(LlmConfigResponse.fromEntity(config))))
            .orElse(ResponseEntity.ok(ApiResponse.success(null)));
    }
    
    /**
     * 根据ID获取配置
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LlmConfigResponse>> getById(@PathVariable Long id) {
        return configService.findById(id)
            .map(config -> ResponseEntity.ok(ApiResponse.success(LlmConfigResponse.fromEntity(config))))
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 创建配置
     */
    @PostMapping
    public ResponseEntity<ApiResponse<LlmConfigResponse>> create(@RequestBody LlmConfigRequest request) {
        try {
            LlmConfig config = configService.create(request);
            return ResponseEntity.ok(ApiResponse.success(LlmConfigResponse.fromEntity(config)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 更新配置
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LlmConfigResponse>> update(
            @PathVariable Long id, 
            @RequestBody LlmConfigRequest request) {
        try {
            LlmConfig config = configService.update(id, request);
            return ResponseEntity.ok(ApiResponse.success(LlmConfigResponse.fromEntity(config)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 删除配置
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            configService.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 激活配置
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activate(@PathVariable Long id) {
        try {
            configService.activate(id);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    /**
     * 测试配置连接
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testConnection(@PathVariable Long id) {
        try {
            LlmConfigService.TestResult result = configService.testConnection(id);
            Map<String, Object> data = new HashMap<>();
            data.put("status", result.status);
            data.put("latency", result.latency);
            data.put("message", result.message);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
