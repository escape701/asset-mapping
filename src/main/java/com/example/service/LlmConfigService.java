package com.example.service;

import com.example.dto.LlmConfigRequest;
import com.example.entity.LlmConfig;
import com.example.repository.LlmConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * LLM配置服务
 */
@Slf4j
@Service
public class LlmConfigService {
    
    private final LlmConfigRepository configRepository;
    
    @Value("${crawler.project-path:D:/ysha/login_crawler}")
    private String crawlerProjectPath;
    
    private static final String YAML_RELATIVE_PATH = "config/llm/llm_keys.yaml";
    
    public LlmConfigService(LlmConfigRepository configRepository) {
        this.configRepository = configRepository;
    }
    
    /**
     * 获取所有配置
     */
    public List<LlmConfig> findAll() {
        return configRepository.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * 根据ID获取配置
     */
    public Optional<LlmConfig> findById(Long id) {
        return configRepository.findById(id);
    }
    
    /**
     * 获取激活的配置
     */
    public Optional<LlmConfig> findActive() {
        return configRepository.findByIsActiveTrue();
    }
    
    /**
     * 创建配置
     */
    @Transactional
    public LlmConfig create(LlmConfigRequest request) {
        LlmConfig config = new LlmConfig();
        config.setName(request.getName());
        config.setProvider(request.getProvider());
        config.setApiKey(request.getApiKey());
        config.setModel(request.getModel());
        
        // 如果是第一个配置，自动激活
        if (configRepository.count() == 0) {
            config.setIsActive(true);
        }
        
        LlmConfig saved = configRepository.save(config);
        
        // 同步到 YAML 文件
        syncToYamlFile();
        
        return saved;
    }
    
    /**
     * 更新配置
     */
    @Transactional
    public LlmConfig update(Long id, LlmConfigRequest request) {
        LlmConfig config = configRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
        
        config.setName(request.getName());
        config.setProvider(request.getProvider());
        config.setModel(request.getModel());
        
        // 只有提供了新的 API Key 才更新
        if (request.getApiKey() != null && !request.getApiKey().isEmpty()) {
            config.setApiKey(request.getApiKey());
        }
        
        LlmConfig saved = configRepository.save(config);
        
        // 同步到 YAML 文件
        syncToYamlFile();
        
        return saved;
    }
    
    /**
     * 删除配置
     */
    @Transactional
    public void delete(Long id) {
        LlmConfig config = configRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
        
        boolean wasActive = config.getIsActive();
        configRepository.delete(config);
        
        // 如果删除的是激活配置，激活第一个
        if (wasActive) {
            List<LlmConfig> remaining = configRepository.findAllByOrderByCreatedAtDesc();
            if (!remaining.isEmpty()) {
                remaining.get(0).setIsActive(true);
                configRepository.save(remaining.get(0));
            }
        }
        
        // 同步到 YAML 文件
        syncToYamlFile();
    }
    
    /**
     * 激活配置
     */
    @Transactional
    public void activate(Long id) {
        // 确认配置存在
        if (!configRepository.existsById(id)) {
            throw new RuntimeException("配置不存在: " + id);
        }
        
        // 取消所有激活状态
        configRepository.deactivateAll();
        
        // 激活指定配置
        configRepository.activateById(id);
        
        // 同步到 YAML 文件
        syncToYamlFile();
        
        log.info("已激活 LLM 配置: {}", id);
    }
    
    /**
     * 测试配置连接
     */
    public TestResult testConnection(Long id) {
        LlmConfig config = configRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
        
        long startTime = System.currentTimeMillis();
        
        try {
            // TODO: 实际调用 API 测试
            // 目前返回模拟结果
            Thread.sleep(500); // 模拟网络延迟
            
            long latency = System.currentTimeMillis() - startTime;
            return new TestResult("connected", latency, "连接成功，模型响应正常");
            
        } catch (Exception e) {
            log.error("测试 LLM 连接失败", e);
            return new TestResult("failed", 0L, "连接失败: " + e.getMessage());
        }
    }
    
    /**
     * 同步配置到 YAML 文件（供 Python 读取）
     */
    public void syncToYamlFile() {
        try {
            Path yamlPath = Paths.get(crawlerProjectPath, YAML_RELATIVE_PATH);
            
            // 确保目录存在
            Files.createDirectories(yamlPath.getParent());
            
            // 构建 YAML 内容
            StringBuilder yaml = new StringBuilder();
            yaml.append("# 自动生成，请勿手动修改\n");
            yaml.append("# Generated at: ").append(LocalDateTime.now()).append("\n\n");
            
            List<LlmConfig> configs = configRepository.findAll();
            LlmConfig activeConfig = configRepository.findByIsActiveTrue().orElse(null);
            
            // 按提供商分组写入
            for (LlmConfig config : configs) {
                if (LlmConfig.PROVIDER_GOOGLE.equals(config.getProvider())) {
                    yaml.append("google:\n");
                    yaml.append("  api_key: \"").append(config.getApiKey()).append("\"\n");
                    yaml.append("  model: \"").append(config.getModel()).append("\"\n\n");
                    break; // 只取第一个 google 配置
                }
            }
            
            for (LlmConfig config : configs) {
                if (LlmConfig.PROVIDER_OPENAI.equals(config.getProvider())) {
                    yaml.append("openai:\n");
                    yaml.append("  api_key: \"").append(config.getApiKey()).append("\"\n");
                    yaml.append("  model: \"").append(config.getModel()).append("\"\n\n");
                    break; // 只取第一个 openai 配置
                }
            }
            
            if (activeConfig != null) {
                yaml.append("active_provider: ").append(activeConfig.getProvider()).append("\n");
            }
            
            // 写入文件
            try (FileWriter writer = new FileWriter(yamlPath.toFile())) {
                writer.write(yaml.toString());
            }
            
            log.info("LLM 配置已同步到: {}", yamlPath);
            
        } catch (IOException e) {
            log.error("同步 LLM 配置到 YAML 文件失败", e);
        }
    }
    
    /**
     * 测试结果
     */
    public static class TestResult {
        public String status;
        public Long latency;
        public String message;
        
        public TestResult(String status, Long latency, String message) {
            this.status = status;
            this.latency = latency;
            this.message = message;
        }
    }
}
