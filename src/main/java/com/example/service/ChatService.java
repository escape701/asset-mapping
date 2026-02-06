package com.example.service;

import com.example.dto.ChatRequest;
import com.example.dto.ChatResponse;
import com.example.entity.LlmConfig;
import com.example.entity.TaskDomain;
import com.example.repository.LlmConfigRepository;
import com.example.repository.TaskDomainRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI对话服务
 */
@Slf4j
@Service
public class ChatService {
    
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    
    private final LlmConfigRepository configRepository;
    private final TaskDomainRepository taskDomainRepository;
    private final CrawlerService crawlerService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    
    public ChatService(LlmConfigRepository configRepository,
                       TaskDomainRepository taskDomainRepository,
                       CrawlerService crawlerService,
                       ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.taskDomainRepository = taskDomainRepository;
        this.crawlerService = crawlerService;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    /**
     * 处理对话请求
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        
        // 获取激活的 LLM 配置
        LlmConfig config = configRepository.findByIsActiveTrue()
            .orElseThrow(() -> new RuntimeException("请先配置并激活 AI 服务"));
        
        // 构建上下文：以整个任务的所有域名作为知识库
        String context;
        if (request.getContext() != null && !request.getContext().isEmpty()) {
            context = request.getContext();
        } else {
            context = buildFullTaskContext(request.getTaskId(), request.getDomain());
        }
        
        // 构建 Prompt
        String systemPrompt = buildSystemPrompt(context);
        String userMessage = request.getMessage();
        
        // 调用 LLM API
        String reply;
        try {
            reply = callLlmApi(config, systemPrompt, userMessage, request.getHistory());
        } catch (Exception e) {
            log.error("调用 LLM API 失败: {}", e.getMessage(), e);
            // 返回模拟回复（开发阶段或 API 失败时）
            reply = generateMockReply(request, context);
        }
        
        long latency = System.currentTimeMillis() - startTime;
        
        return new ChatResponse(reply, config.getModel(), latency);
    }
    
    /**
     * 构建整个任务的上下文信息（以所有域名作为AI知识库）
     * @param taskId 任务ID
     * @param focusDomain 当前聚焦的域名（可为null，表示关注整个任务）
     */
    private String buildFullTaskContext(String taskId, String focusDomain) {
        StringBuilder context = new StringBuilder();
        context.append("=== 资产测绘任务知识库 ===\n");
        context.append("任务ID: ").append(taskId).append("\n");
        if (focusDomain != null && !focusDomain.isEmpty()) {
            context.append("当前聚焦域名: ").append(focusDomain).append("\n");
        }
        context.append("\n");
        
        // 获取任务下的所有域名
        List<TaskDomain> allDomains = taskDomainRepository.findByTaskId(taskId);
        
        if (allDomains.isEmpty()) {
            context.append("暂无域名数据。\n");
            return context.toString();
        }
        
        context.append("本次任务共包含 ").append(allDomains.size()).append(" 个域名：\n");
        for (TaskDomain td : allDomains) {
            context.append("- ").append(td.getDomain()).append(" (状态: ").append(td.getStatus()).append(")\n");
        }
        context.append("\n");
        
        // 遍历每个域名构建详细上下文
        for (TaskDomain taskDomain : allDomains) {
            String domain = taskDomain.getDomain();
            context.append("========================================\n");
            context.append("【域名: ").append(domain).append("】\n");
            context.append("========================================\n");
            buildDomainContext(context, taskId, taskDomain);
            context.append("\n");
        }
        
        return context.toString();
    }
    
    /**
     * 构建单个域名的上下文
     */
    @SuppressWarnings("unchecked")
    private void buildDomainContext(StringBuilder context, String taskId, TaskDomain taskDomain) {
        String domain = taskDomain.getDomain();
        
        // 基本统计
        context.append("状态: ").append(taskDomain.getStatus()).append("\n");
        context.append("子域名数: ").append(taskDomain.getSubdomainCount()).append("\n");
        context.append("URL数: ").append(taskDomain.getUrlCount()).append("\n");
        context.append("已访问页面数: ").append(taskDomain.getVisitedCount()).append("\n");
        context.append("登录入口数: ").append(taskDomain.getLoginCount()).append("\n");
        context.append("失败数: ").append(taskDomain.getFailedCount()).append("\n\n");
        
        // 子域名
        try {
            Map<String, Object> discovery = crawlerService.readDiscoveryResult(taskId, domain);
            if (!discovery.isEmpty()) {
                List<String> subdomains = (List<String>) discovery.get("subdomains");
                if (subdomains != null && !subdomains.isEmpty()) {
                    context.append("发现的子域名(共 ").append(subdomains.size()).append(" 个):\n");
                    int count = 0;
                    for (String sub : subdomains) {
                        context.append("  - ").append(sub).append("\n");
                        if (++count >= 50) {
                            context.append("  ... 还有 ").append(subdomains.size() - 50).append(" 个\n");
                            break;
                        }
                    }
                    context.append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("读取 {} 发现结果失败: {}", domain, e.getMessage());
        }
        
        // 爬取结果
        try {
            Map<String, Object> crawlResult = crawlerService.readCrawlResult(taskId, domain);
            if (!crawlResult.isEmpty()) {
                // 统计
                Map<String, Object> statistics = (Map<String, Object>) crawlResult.get("statistics");
                if (statistics != null) {
                    context.append("爬取统计: ");
                    context.append("发现URL ").append(statistics.getOrDefault("total_discovered", 0));
                    context.append(", 已访问 ").append(statistics.getOrDefault("total_visited", 0));
                    context.append(", 失败 ").append(statistics.getOrDefault("total_failed", 0));
                    context.append("\n\n");
                }
                
                // 访问的页面
                List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawlResult.get("visited_pages");
                if (visitedPages != null && !visitedPages.isEmpty()) {
                    context.append("访问的页面(共 ").append(visitedPages.size()).append(" 个):\n");
                    int count = 0;
                    for (Map<String, Object> page : visitedPages) {
                        context.append("  - ").append(page.getOrDefault("url", "")).append("\n");
                        context.append("    标题: ").append(page.getOrDefault("title", "无")).append("\n");
                        if (CrawlerService.isLoginPage(page.get("is_login_page"))) {
                            context.append("    [登录页面]\n");
                            // 追加登录检测详情
                            Map<String, Object> loginDet = (Map<String, Object>) page.get("login_detection");
                            if (loginDet != null) {
                                List<String> authTypes = (List<String>) loginDet.get("auth_types");
                                if (authTypes != null && !authTypes.isEmpty()) {
                                    context.append("    认证方式: ").append(String.join(", ", authTypes)).append("\n");
                                }
                                Object mfa = loginDet.get("mfa_confirmation");
                                if (mfa != null) {
                                    context.append("    MFA: ").append(mfa).append("\n");
                                }
                            }
                        }
                        if (++count >= 30) {
                            context.append("  ... 还有 ").append(visitedPages.size() - 30).append(" 个页面\n");
                            break;
                        }
                    }
                    context.append("\n");
                }
                
                // 敏感路径
                List<String> sensitivePaths = (List<String>) crawlResult.get("sensitive_paths");
                if (sensitivePaths != null && !sensitivePaths.isEmpty()) {
                    context.append("敏感路径(共 ").append(sensitivePaths.size()).append(" 个):\n");
                    for (String path : sensitivePaths) {
                        context.append("  - ").append(path).append("\n");
                    }
                    context.append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("读取 {} 爬取结果失败: {}", domain, e.getMessage());
        }
        
        // 登录入口
        try {
            List<Map<String, Object>> logins = crawlerService.extractLoginPages(taskId, domain);
            if (!logins.isEmpty()) {
                context.append("登录入口(共 ").append(logins.size()).append(" 个):\n");
                for (Map<String, Object> login : logins) {
                    context.append("  - URL: ").append(login.get("url")).append("\n");
                    context.append("    标题: ").append(login.getOrDefault("title", "无")).append("\n");
                    // 登录检测详情
                    Map<String, Object> loginDet = (Map<String, Object>) login.get("login_detection");
                    if (loginDet != null) {
                        List<String> authTypes = (List<String>) loginDet.get("auth_types");
                        if (authTypes != null && !authTypes.isEmpty()) {
                            context.append("    认证方式: ").append(String.join(", ", authTypes)).append("\n");
                        }
                        Object mfa = loginDet.get("mfa_confirmation");
                        if (mfa != null) {
                            context.append("    MFA: ").append(mfa).append("\n");
                        }
                    } else {
                        // 旧版格式回退
                        context.append("    识别结果: ").append(login.get("is_login_page")).append("\n");
                    }
                }
                context.append("\n");
            }
        } catch (Exception e) {
            log.debug("读取 {} 登录页面失败: {}", domain, e.getMessage());
        }
    }
    
    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        return String.format(
            "你是一个专业的安全分析助手，专门分析资产测绘结果。\n\n" +
            "以下是本次资产测绘任务的完整数据，包含任务中所有域名的测绘结果。" +
            "请基于这些数据回答用户的问题：\n\n" +
            "%s\n\n" +
            "请注意：\n" +
            "1. 你拥有整个任务所有域名的测绘数据作为知识库\n" +
            "2. 用户可能问某个具体域名，也可能问整个任务的综合分析\n" +
            "3. 回答要专业、准确、有帮助，基于实际数据给出分析\n" +
            "4. 重点关注安全风险和潜在漏洞\n" +
            "5. 给出具体的安全建议\n" +
            "6. 使用 Markdown 格式组织回答\n" +
            "7. 如果某个域名的数据不足，说明需要哪些额外信息", context);
    }
    
    /**
     * 调用 LLM API
     */
    private String callLlmApi(LlmConfig config, String systemPrompt, String userMessage, 
                              List<Map<String, String>> history) throws IOException {
        String provider = config.getProvider();
        
        if (LlmConfig.PROVIDER_OPENAI.equals(provider)) {
            return callOpenAI(config, systemPrompt, userMessage, history);
        } else if (LlmConfig.PROVIDER_GOOGLE.equals(provider)) {
            return callGemini(config, systemPrompt, userMessage, history);
        } else {
            throw new IllegalArgumentException("不支持的 LLM 提供商: " + provider);
        }
    }
    
    /**
     * 调用 OpenAI API
     */
    private String callOpenAI(LlmConfig config, String systemPrompt, String userMessage,
                              List<Map<String, String>> history) throws IOException {
        // 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        
        // 添加系统消息
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);
        messages.add(systemMessage);
        
        // 添加历史对话
        if (history != null && !history.isEmpty()) {
            for (Map<String, String> msg : history) {
                Map<String, Object> historyMessage = new HashMap<>();
                historyMessage.put("role", msg.get("role"));
                historyMessage.put("content", msg.get("content"));
                messages.add(historyMessage);
            }
        }
        
        // 添加用户消息
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("OpenAI 请求体: {}", jsonBody);
        
        Request request = new Request.Builder()
            .url(OPENAI_API_URL)
            .addHeader("Authorization", "Bearer " + config.getApiKey())
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                log.error("OpenAI API 错误: {} - {}", response.code(), responseBody);
                throw new IOException("OpenAI API 调用失败: " + response.code() + " - " + responseBody);
            }
            
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode choices = jsonNode.get("choices");
            
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
            
            throw new IOException("OpenAI API 返回格式异常: " + responseBody);
        }
    }
    
    /**
     * 调用 Google Gemini API
     */
    private String callGemini(LlmConfig config, String systemPrompt, String userMessage,
                              List<Map<String, String>> history) throws IOException {
        // 构建 Gemini 请求
        List<Map<String, Object>> contents = new ArrayList<>();
        
        // Gemini 使用 parts 格式
        // 添加系统指令作为第一个用户消息的上下文
        StringBuilder fullMessage = new StringBuilder();
        fullMessage.append("系统指令：\n").append(systemPrompt).append("\n\n");
        
        // 添加历史对话
        if (history != null && !history.isEmpty()) {
            for (Map<String, String> msg : history) {
                Map<String, Object> content = new HashMap<>();
                String role = "user".equals(msg.get("role")) ? "user" : "model";
                content.put("role", role);
                
                List<Map<String, String>> parts = new ArrayList<>();
                Map<String, String> part = new HashMap<>();
                part.put("text", msg.get("content"));
                parts.add(part);
                content.put("parts", parts);
                
                contents.add(content);
            }
        }
        
        // 添加当前用户消息
        Map<String, Object> userContent = new HashMap<>();
        userContent.put("role", "user");
        List<Map<String, String>> userParts = new ArrayList<>();
        Map<String, String> userPart = new HashMap<>();
        // 如果没有历史记录，将系统提示词包含在用户消息中
        if (history == null || history.isEmpty()) {
            userPart.put("text", fullMessage.toString() + "用户问题：" + userMessage);
        } else {
            userPart.put("text", userMessage);
        }
        userParts.add(userPart);
        userContent.put("parts", userParts);
        contents.add(userContent);
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", contents);
        
        // 添加生成配置
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.7);
        generationConfig.put("maxOutputTokens", 4096);
        requestBody.put("generationConfig", generationConfig);
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        String url = String.format(GEMINI_API_URL, config.getModel(), config.getApiKey());
        
        log.debug("Gemini 请求 URL: {}", url.replaceAll("key=.*", "key=***"));
        log.debug("Gemini 请求体: {}", jsonBody);
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                log.error("Gemini API 错误: {} - {}", response.code(), responseBody);
                throw new IOException("Gemini API 调用失败: " + response.code() + " - " + responseBody);
            }
            
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode candidates = jsonNode.get("candidates");
            
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode content = candidates.get(0).get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray() && parts.size() > 0) {
                        JsonNode text = parts.get(0).get("text");
                        if (text != null) {
                            return text.asText();
                        }
                    }
                }
            }
            
            throw new IOException("Gemini API 返回格式异常: " + responseBody);
        }
    }
    
    /**
     * 生成模拟回复（开发阶段使用）
     */
    private String generateMockReply(ChatRequest request, String context) {
        String message = request.getMessage().toLowerCase();
        String domain = request.getDomain();
        
        if (message.contains("安全") || message.contains("风险")) {
            return String.format(
                "## %s 安全分析报告\n\n" +
                "基于测绘结果，我发现以下安全风险点：\n\n" +
                "**高风险：**\n" +
                "1. 存在多个登录入口，建议检查是否有弱密码或暴力破解防护\n" +
                "2. 部分子域名可能存在未授权访问风险\n\n" +
                "**中风险：**\n" +
                "1. 发现敏感目录可能暴露\n" +
                "2. 部分页面缺少 HTTPS\n\n" +
                "**建议措施：**\n" +
                "- 对所有登录入口启用多因素认证\n" +
                "- 审查子域名的访问控制策略\n" +
                "- 定期进行安全审计\n\n" +
                "*注意：这是基于自动化测绘的初步分析，建议进行深入的人工安全评估。*", domain);
        }
        
        if (message.contains("子域名") || message.contains("subdomain")) {
            return String.format(
                "## %s 子域名分析\n\n" +
                "子域名爆破发现了多个子域名，其中需要重点关注：\n\n" +
                "1. **管理类子域名**：admin、manage、portal 等可能暴露管理后台\n" +
                "2. **API子域名**：api、gateway 等可能存在接口安全问题\n" +
                "3. **测试环境**：dev、test、staging 等可能配置不当\n\n" +
                "**建议：**\n" +
                "- 检查每个子域名的用途和安全配置\n" +
                "- 关闭不必要的测试环境公网访问\n" +
                "- 确保敏感子域名有适当的访问控制", domain);
        }
        
        if (message.contains("登录")) {
            return String.format(
                "## %s 登录入口分析\n\n" +
                "发现的登录入口需要关注以下安全问题：\n\n" +
                "1. **认证方式**：检查是否使用安全的认证机制\n" +
                "2. **会话管理**：确保 Session/Token 安全配置\n" +
                "3. **暴力破解防护**：验证是否有登录频率限制\n" +
                "4. **密码策略**：确认密码复杂度要求\n\n" +
                "**测试建议：**\n" +
                "- 检查 SQL 注入漏洞\n" +
                "- 测试 XSS 漏洞\n" +
                "- 验证 CSRF 防护\n" +
                "- 检查密码重置流程安全性", domain);
        }
        
        return String.format(
            "感谢您的提问！\n\n" +
            "关于 **%s** 的测绘分析，我已收到您的问题。\n\n" +
            "您可以问我：\n" +
            "- \"分析这个域名的安全风险\"\n" +
            "- \"总结发现的所有子域名\"\n" +
            "- \"分析发现的登录入口\"\n" +
            "- \"有哪些敏感路径需要关注\"\n\n" +
            "请告诉我您想了解的具体内容。", domain);
    }
}
